package com.depromeet.piki.user.service

import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.user.domain.User
import com.depromeet.piki.user.domain.UserException
import com.depromeet.piki.user.event.UserCreated
import com.depromeet.piki.user.repository.UserDetailRepository
import com.depromeet.piki.user.repository.UserRepository
import com.depromeet.piki.user.service.dto.UserProfile
import org.hibernate.exception.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

@Service
class UserService(
    private val userRepository: UserRepository,
    private val userDetailRepository: UserDetailRepository,
    private val defaultProfileImages: DefaultProfileImages,
    private val eventPublisher: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        // 형용사 64 × 동물 64 = 4096 조합. 모든 조합이 닉네임 10자 제한 이하가 되도록
        // 형용사는 5자 이하, 동물은 3자 이하로 유지한다(최대 5+1+3=9자). 풀 정합성(크기·글자수·중복)은 단위 테스트가 검증한다.
        private val NICKNAME_PREFIXES =
            listOf(
                "날뛰는",
                "졸린",
                "배고픈",
                "춤추는",
                "달리는",
                "헤엄치는",
                "잠자는",
                "신나는",
                "빠른",
                "느린",
                "배부른",
                "웃는",
                "뛰어다니는",
                "구르는",
                "노래하는",
                "울부짖는",
                "용감한",
                "귀여운",
                "똑똑한",
                "엉뚱한",
                "수줍은",
                "행복한",
                "게으른",
                "화난",
                "신비한",
                "우아한",
                "까칠한",
                "명랑한",
                "차분한",
                "도도한",
                "엉큼한",
                "발랄한",
                "멋진",
                "착한",
                "슬픈",
                "기쁜",
                "작은",
                "통통한",
                "포동한",
                "폭신한",
                "말랑한",
                "몽글한",
                "보송한",
                "매끈한",
                "나른한",
                "해맑은",
                "또렷한",
                "당당한",
                "늠름한",
                "씩씩한",
                "의젓한",
                "점잖은",
                "진지한",
                "새침한",
                "토라진",
                "심술난",
                "장난친",
                "굶주린",
                "설레는",
                "반짝이는",
                "빛나는",
                "날렵한",
                "천진한",
                "영리한",
            )
        private val NICKNAME_ANIMALS =
            listOf(
                "하마",
                "토끼",
                "고양이",
                "강아지",
                "코끼리",
                "기린",
                "펭귄",
                "판다",
                "사자",
                "호랑이",
                "여우",
                "늑대",
                "독수리",
                "돌고래",
                "부엉이",
                "다람쥐",
                "너구리",
                "수달",
                "거북이",
                "두더지",
                "햄스터",
                "코알라",
                "캥거루",
                "미어캣",
                "알파카",
                "치타",
                "표범",
                "오리",
                "거위",
                "두루미",
                "청설모",
                "살쾡이",
                "사슴",
                "곰",
                "말",
                "양",
                "닭",
                "쥐",
                "학",
                "잉어",
                "붕어",
                "상어",
                "고래",
                "물개",
                "원숭이",
                "재규어",
                "퓨마",
                "라마",
                "야크",
                "들소",
                "산양",
                "염소",
                "병아리",
                "두꺼비",
                "개구리",
                "도마뱀",
                "앵무새",
                "까마귀",
                "비둘기",
                "참새",
                "까치",
                "갈매기",
                "올빼미",
                "타조",
            )
        internal val NICKNAME_POOL: List<String> by lazy {
            NICKNAME_PREFIXES.flatMap { prefix -> NICKNAME_ANIMALS.map { animal -> "$prefix $animal" } }
        }

        // 닉네임 unique 제약 이름(V20260521234243 의 uq_users_nickname). DataIntegrityViolationException 중
        // 이 제약 위반만 닉네임 중복으로 다룬다 — 다른 DB 오류(NOT NULL·길이 등)를 409 로 숨기지 않기 위해.
        private const val USERS_NICKNAME_CONSTRAINT = "uq_users_nickname"

        // 게스트 닉네임 자동 생성 시 save 직전 race 로 unique 충돌이 나면 닉네임을 다시 뽑아 재시도하는 최대 횟수.
        private const val GUEST_NICKNAME_MAX_ATTEMPTS = 5

        // 게스트 닉네임 자동 생성 시 4096 풀 전체를 IN 조회하지 않고 랜덤 64개 subset 만 조회한다(#685).
        // 실측(Testcontainers MySQL): 호출당 ~6–8ms → ~0.2–1ms (풀 소진도에 따라 8–29×). 풀이 텅 빈 상태에서도
        // IN(4096)은 4096 인덱스 probe 로 ~6ms 고정인데, subset 은 그 확인을 ~0.2ms 에 끝낸다.
        // 64: 흔한 경우 subset 1회로 끝난다. subset 이 전부 taken(near-exhaustion)일 때만 전체 풀을 한 번 더
        // 조회해 진짜 소진을 확인하므로(generateUniqueGuestNickname 의 fallback), 샘플 고갈을 풀 고갈로 오인한 실패는 없다.
        private const val GUEST_NICKNAME_SAMPLE_SIZE = 64

        // 풀(4096)이 소진되면 조합 뒤에 숫자를 붙여 확장한다(#920). 여기가 그 자릿수 상한 — 최단 조합에 붙일 수
        // 있는 최대 자릿수다. 조합이 길수록 여유가 좁아 자릿수 단계마다 basesFor 가 걸러낸다.
        internal val NICKNAME_SUFFIX_MAX_WIDTH: Int by lazy {
            User.NICKNAME_MAX_LENGTH - NICKNAME_POOL.minOf { it.length }
        }

        // 자릿수 width 가 표현하는 숫자 범위 — 앞자리 0 없이 정확히 그 자릿수다. 1→1..9, 2→10..99, 3→100..999.
        // 단계마다 범위가 겹치지 않아, 좁은 자릿수에서 이미 소진된 숫자를 다음 단계가 다시 뽑는 헛일이 없다.
        internal fun suffixRange(width: Int): IntRange {
            var first = 1
            repeat(width - 1) { first *= 10 }
            return first..(first * 10 - 1)
        }

        // 그 자릿수를 붙여도 닉네임 길이 제한을 넘지 않는 조합만 남긴다. 길이 검증을 분기로 흩지 않고
        // 후보 생성 단계에서 한 번에 거른다 — 9자 조합은 1자리 단계에만, 8자는 2자리까지만 후보가 된다.
        internal fun basesFor(width: Int): List<String> = NICKNAME_POOL.filter { it.length + width <= User.NICKNAME_MAX_LENGTH }
    }

    // 게스트는 닉네임을 자동 생성하므로 '닉네임 중복' 이라는 사용자 입력 오류가 없다. 다만 generateUniqueGuestNickname()
    // 와 save 사이 race 로 다른 요청이 같은 닉네임을 선점하면 unique 충돌이 날 수 있어, 닉네임을 다시 뽑아 재시도한다.
    // @Transactional 을 두지 않아 각 save 가 독립 트랜잭션으로 돈다 — 한 시도의 충돌이 다음 시도를 오염시키지 않게.
    fun createGuest(): User {
        repeat(GUEST_NICKNAME_MAX_ATTEMPTS) {
            try {
                // saveAndFlush — 충돌을 이 try 안에서 결정적으로 끌어올려(비트랜잭션이라 save 만 해도 즉시 커밋되지만,
                // 명시 flush 로 시점을 못 박는다) 재시도 분기가 항상 동작하게 한다.
                return userRepository.saveAndFlush(
                    User(
                        id = UUID.randomUUID(),
                        nickname = generateUniqueGuestNickname(),
                        profileImage = defaultProfileImages.random(),
                        identityType = IdentityType.GUEST,
                    ),
                ).also { eventPublisher.publishEvent(UserCreated(it.getId())) }
            } catch (e: DataIntegrityViolationException) {
                if (!isNicknameUniqueViolation(e)) throw e
                // 닉네임 unique 충돌(race) → 닉네임을 다시 뽑아 재시도
            }
        }
        throw UserException.nicknameGenerationFailed()
    }

    @Transactional
    fun createGuestWithNickname(nickname: String): User =
        saveNewUser(nickname, defaultProfileImages.random(), IdentityType.GUEST)

    @Transactional
    fun createMember(nickname: String): User {
        if (userRepository.existsByNickname(nickname)) throw UserException.duplicateNickname()
        return saveNewUser(nickname, defaultProfileImages.random(), IdentityType.MEMBER)
    }

    // 소셜 신규 가입용 MEMBER 생성. 닉네임은 게스트와 동일하게 자동 생성하고 사용자가 나중에 수정한다.
    // 프로필 이미지는 provider 가 준 게 있으면 쓰고, 없으면(동의 거부 등) 기본 아바타 중 랜덤.
    //
    // saveNewUser 를 쓰지 않는다 — 그쪽은 닉네임 unique 충돌을 duplicateNickname(409, "다른 걸 입력해 주세요")으로
    // 바꾸는데, 여기 닉네임은 사용자가 입력한 게 아니라 서버가 뽑은 값이라 그 문구가 거짓이 된다. 자동 생성 경로의
    // 충돌은 race 일 뿐이므로 원본 예외를 그대로 올려, 호출부(SocialAccountService)가 닉네임을 다시 뽑아 재시도한다.
    // createGuest 가 같은 이유로 saveNewUser 를 우회하는 것과 같은 결.
    @Transactional
    fun createSocialUser(profileImage: String?): User =
        userRepository.saveAndFlush(
            User(
                id = UUID.randomUUID(),
                nickname = generateUniqueGuestNickname(),
                profileImage = profileImage ?: defaultProfileImages.random(),
                identityType = IdentityType.MEMBER,
            ),
        ).also { eventPublisher.publishEvent(UserCreated(it.getId())) }

    // 신규 user 영속화 공통 경로. 닉네임 unique 충돌(uq_users_nickname)만 duplicateNickname 으로 변환하고,
    // 그 외 DB 제약 위반(NOT NULL·길이 등)은 원본 예외를 그대로 던져 500 으로 드러나게 한다.
    private fun saveNewUser(
        nickname: String,
        profileImage: String,
        identityType: IdentityType,
    ): User =
        try {
            // saveAndFlush — @Transactional 호출자(createMember 등) 안에서 save 만 하면 클라 할당 UUID 라 INSERT 가
            // 커밋 시점까지 미뤄져, unique 충돌이 이 catch 밖(커밋)에서 터져 409 변환을 못 한다(→ 500). flush 로 같은
            // 메서드 안에서 제약 위반을 끌어올려, 닉네임 충돌을 여기서 잡아 duplicateNickname(409)으로 변환한다.
            userRepository.saveAndFlush(
                User(id = UUID.randomUUID(), nickname = nickname, profileImage = profileImage, identityType = identityType),
            ).also { eventPublisher.publishEvent(UserCreated(it.getId())) }
        } catch (e: DataIntegrityViolationException) {
            if (isNicknameUniqueViolation(e)) throw UserException.duplicateNickname()
            throw e
        }

    // 닉네임 unique 충돌 판별을 소셜 가입 경로(SocialAccountService)와 공유한다 — 그쪽은 트랜잭션 밖에서
    // 재시도해야 해(안에서 재시도하면 rollback-only 로 마킹돼 무의미) 이 판별이 서비스 밖에서도 필요하다.
    internal fun isNicknameConflict(e: DataIntegrityViolationException): Boolean = isNicknameUniqueViolation(e)

    // DataIntegrityViolationException 이 닉네임 unique 제약(uq_users_nickname) 위반인지 판별한다.
    // cause 체인을 끝까지 훑는다 — PersistenceException 같은 래퍼가 한 겹 더 끼면 e.cause 만 봐서는 ConstraintViolationException
    // 을 놓쳐 닉네임 충돌이 409 대신 500 으로 샐 수 있다. 각 단계에서 ConstraintViolationException 의 constraintName(Hibernate 가
    // 못 채우면 null)과 예외 메시지(드라이버가 제약명을 담는다) 둘 다 본다.
    private fun isNicknameUniqueViolation(e: DataIntegrityViolationException): Boolean =
        generateSequence(e as Throwable) { it.cause }.any { t ->
            (t as? ConstraintViolationException)?.constraintName?.contains(USERS_NICKNAME_CONSTRAINT, ignoreCase = true) == true ||
                t.message?.contains(USERS_NICKNAME_CONSTRAINT, ignoreCase = true) == true
        }

    // tombstone(탈퇴) 유저도 그대로 반환한다. 탈퇴 상태 자체를 읽어야 하는 경로 — 재로그인 판정(죽은 계정을
    // 되살리지 않기), 멱등 탈퇴, refresh 거부(409 가 아니라 401 로 응답) — 전용이다.
    // 활성 유저를 기대하는 경로는 이걸 쓰면 안 된다. findActiveById 를 쓴다.
    @Transactional(readOnly = true)
    fun findById(userId: UUID): User = userRepository.findById(userId) ?: throw UserException.notFound()

    // 활성(미탈퇴) 유저 전용 조회. tombstone 접근이 계약 위반인 경로는 전부 이걸 쓴다.
    // 호출부마다 흩어져 있던 deletedAt 확인을 한 자리로 모아, 새 경로가 그 체크를 빠뜨려 탈퇴 유저가
    // 되살아나는 조용한 버그를 구조적으로 막는다 (#691).
    //
    // 전역 @SQLRestriction 으로 거르지 않는 이유: 탈퇴 cascade 와 위 findById 의 의도적 tombstone 읽기가
    // 같은 엔티티를 필요로 해, 전역 필터를 걸면 그 경로들이 함께 깨진다.
    @Transactional(readOnly = true)
    fun findActiveById(userId: UUID): User {
        val user = findById(userId)
        user.deletedAt?.let { throw UserException.deletedUser() }
        return user
    }

    // 잠긴 활성 조회 — 활성 확인과 쓰기를 같은 트랜잭션에서 원자화해야 하는 쓰기 경로 전용(#776).
    // findActiveById(비잠금)와 달리 user 행을 FOR UPDATE 로 잠가, 확인~쓰기 사이에 탈퇴 cascade 가 끼어들어
    // tombstone 위에 쓰기가 반영되는(계정 부활·PII 복원·orphan 자식 행) 경합을 닫는다. 탈퇴 cascade 도 user 행
    // UPDATE 로 시작해 같은 락을 잡으므로, user 행을 첫 락으로 잡는 이 규약 아래 두 트랜잭션이 직렬화된다.
    //
    // 반드시 트랜잭션 안에서 호출한다 — FOR UPDATE 락은 트랜잭션 경계까지만 유지되므로, 트랜잭션 밖 호출은
    // 락이 즉시 풀려 무의미한 코드 버그다(불변식 위반 → 500). 자기 트랜잭션을 열지 않고 호출부(REQUIRED)에 합류한다.
    fun findActiveByIdForUpdate(userId: UUID): User {
        checkInTransaction()
        val user = userRepository.findByIdForUpdate(userId) ?: throw UserException.notFound()
        user.deletedAt?.let { throw UserException.deletedUser() }
        return user
    }

    // users 행 존재를 강제하지 않는 경로(FCM 토큰 등록 등, 인증만 되면 users 행 없이도 호출되던 계약)용 잠금 가드.
    // 행이 있으면 잠그고 tombstone 이면 거부하되, 없으면 통과시킨다(기존 계약 보존). 유효한 토큰이면 prod 엔 항상
    // users 행이 있으므로 탈퇴 유저는 여기서 걸리고, 탈퇴 cascade 와 user 행 락으로 직렬화돼 죽은 자식 행이 남지 않는다.
    fun rejectIfWithdrawnForUpdate(userId: UUID) {
        checkInTransaction()
        userRepository.findByIdForUpdate(userId)?.deletedAt?.let { throw UserException.deletedUser() }
    }

    // 잠긴 활성 여부 조회(예외 없이 boolean) — 예외 대신 "조용히 건너뛰기"가 맞는 경로용(예: 스케줄러가 처리하는
    // 지연 wish 등록). 스케줄러 경로는 tombstone 이라고 예외를 던지면 트랜잭션이 롤백돼 claim 이 되살아나 무한
    // 재시도가 되므로, 예외 대신 이 boolean 으로 판별해 claim 은 소비하되 쓰기만 건너뛴다. 행이 없어도 false(대상 아님).
    fun isActiveForUpdate(userId: UUID): Boolean {
        checkInTransaction()
        return userRepository.findByIdForUpdate(userId)?.isActive() ?: false
    }

    private fun checkInTransaction() =
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "잠긴 활성 조회는 트랜잭션 안에서만 호출해야 한다 — FOR UPDATE 락은 트랜잭션 경계까지만 유지된다."
        }

    // 마이페이지(GET /me) 조회 — User(정체성)와 UserDetail 의 email 을 한 트랜잭션에서 모은다.
    // email 은 미수집(게스트)·미동의·backfill 전이면 UserDetail 이 없거나 null 이라 그대로 null 로 내려간다.
    @Transactional(readOnly = true)
    fun getMyProfile(userId: UUID): UserProfile {
        val user = findActiveById(userId)
        val email = userDetailRepository.findByUserId(userId)?.email
        return UserProfile(user, email)
    }

    // 본인 닉네임은 중복으로 잡지 않는다 (#230). 게스트가 자기 닉네임 그대로 유지하거나, 본인이
    // 자기 닉네임으로 다시 변경하는 흐름이 자연스럽게 통과되도록 본인 제외 후 검사.
    @Transactional(readOnly = true)
    fun isNicknameAvailable(
        nickname: String,
        userId: UUID?,
    ): Boolean =
        userId
            ?.let { !userRepository.existsByNicknameAndIdNot(nickname, it) }
            ?: !userRepository.existsByNickname(nickname)

    // 내 정보(닉네임·프로필 이미지) 부분 수정 영속화 — 들어온 필드만 갱신한다. 둘을 한 트랜잭션에 묶어
    // "닉네임은 됐는데 이미지는 실패" 같은 부분 성공을 막는다. 이미지 S3 업로드(외부 호출)는 ProfileUpdateService 가
    // 트랜잭션 밖에서 끝낸 뒤 그 결과 URL 만 여기로 위임한다 (## 트랜잭션 경계 — 외부 호출은 트랜잭션 밖).
    @Transactional
    fun updateProfile(
        userId: UUID,
        nickname: String?,
        profileImageUrl: String?,
    ): User {
        val user = findActiveByIdForUpdate(userId)
        // 무엇이 바뀌었는지만 남긴다 — 닉네임 원문은 PII 라 값이 아니라 "어떤 필드가 변경됐나"만 로깅한다.
        val changedFields =
            buildList {
                nickname?.let {
                    if (userRepository.existsByNicknameAndIdNot(it, userId)) throw UserException.duplicateNickname()
                    user.updateNickname(it)
                    add("nickname")
                }
                profileImageUrl?.let {
                    user.updateProfileImage(it)
                    add("profileImage")
                }
            }
        // existsByNicknameAndIdNot 체크와 saveAndFlush 사이에 다른 트랜잭션이 같은 nickname 으로 update / insert
        // 하면 DB unique constraint (uq_users_nickname) 위반이 떠 race 케이스가 생긴다. saveNewUser 와 같은 패턴 —
        // save 만 하면 UPDATE 가 커밋까지 미뤄져 위반이 이 catch 밖(커밋)에서 터지므로 saveAndFlush 로 끌어와 잡는다.
        // 닉네임 unique 충돌만 duplicateNickname(409)으로 변환하고, 그 외 DB 위반(NOT NULL·길이 등)은 원본 예외를
        // 그대로 던져 진짜 서버 버그가 500 으로 드러나게 한다.
        val saved =
            try {
                userRepository.saveAndFlush(user)
            } catch (e: DataIntegrityViolationException) {
                if (isNicknameUniqueViolation(e)) throw UserException.duplicateNickname()
                throw e
            }
        log.info("내 정보 수정 userId={} 변경필드={}", userId, changedFields)
        return saved
    }

    // 소셜 가입/연동 시 provider 가 준 프로필 이미지 URL 영속화만 담당하는 짧은 트랜잭션 (SocialAccountWriter 전용).
    // 내 정보 수정(PATCH /me) 경로는 ProfileUpdateService → updateProfile 로 가며, 이 메서드는 닉네임과 무관한 auth 경로다.
    @Transactional
    fun updateProfileImageUrl(
        userId: UUID,
        profileImageUrl: String,
    ): User {
        val user = findActiveByIdForUpdate(userId)
        user.updateProfileImage(profileImageUrl)
        return userRepository.save(user)
    }

    @Transactional
    fun promoteToMember(userId: UUID): User {
        val user = findActiveByIdForUpdate(userId)
        user.promoteToMember()
        return userRepository.save(user)
    }

    // 재탈퇴는 멱등하게 통과시켜야 하므로 tombstone 을 읽는 findById 를 그대로 쓴다 — softDelete() 가
    // deletedAt ?:= now 로 첫 값을 유지하므로 2회차는 무해한 no-op 이다.
    @Transactional
    fun softDelete(userId: UUID) {
        val user = findById(userId)
        user.softDelete()
        userRepository.save(user)
    }

    private fun generateUniqueGuestNickname(): String {
        val sample = NICKNAME_POOL.shuffled().take(GUEST_NICKNAME_SAMPLE_SIZE)
        val takenInSample = userRepository.findNicknamesIn(sample).toSet()
        (sample - takenInSample).randomOrNull()?.let { return it }

        // subset 이 전부 taken(near-exhaustion)이면 진짜 소진인지 전체 풀로 확인한다 — 샘플 고갈을 풀 고갈로
        // 오인해, 사용 가능한 닉네임이 남았는데도 재생성 실패로 던지는 것을 막는다.
        val takenInPool = userRepository.findNicknamesIn(NICKNAME_POOL).toSet()
        return (NICKNAME_POOL - takenInPool).randomOrNull() ?: generateSuffixedNickname()
    }

    // 풀(4096)이 소진된 뒤의 확장 경로(#920) — 조합 뒤에 숫자를 붙여 발급한다. 여기 닿기 전까지는 동작이
    // 이전과 완전히 같다(위 두 단계가 먼저 처리하므로 4096명 이전 사용자는 영향을 받지 않는다).
    //
    // 자릿수를 1부터 넓히며 각 단계에서 subset 조회를 한 번씩 한다 — 위 정상 경로와 같은 모양이라
    // 조회 비용 특성이 같고, 작은 숫자부터 소비해 발급되는 닉네임이 필요 이상으로 길어지지 않는다.
    private fun generateSuffixedNickname(): String {
        (1..NICKNAME_SUFFIX_MAX_WIDTH).forEach { width ->
            val range = suffixRange(width)
            val candidates = basesFor(width).shuffled().take(GUEST_NICKNAME_SAMPLE_SIZE).map { "$it${range.random()}" }
            val taken = userRepository.findNicknamesIn(candidates).toSet()
            (candidates - taken).randomOrNull()?.let { return it }
        }
        // 최대 자릿수까지 소진 — 용량이 사실상 무한(수백만)이라 도달할 수 없지만, 무한 재시도 대신 실패로 끝낸다.
        throw UserException.nicknameGenerationFailed()
    }
}
