package com.depromeet.piki.user.service

import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.user.domain.User
import com.depromeet.piki.user.domain.UserException
import com.depromeet.piki.user.repository.UserDetailRepository
import com.depromeet.piki.user.repository.UserRepository
import com.depromeet.piki.user.service.dto.UserProfile
import org.hibernate.exception.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class UserService(
    private val userRepository: UserRepository,
    private val userDetailRepository: UserDetailRepository,
    private val defaultProfileImages: DefaultProfileImages,
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
        // 64: subset 전부 taken 이라 재생성 실패로 오인될 확률이 풀 ~90% 소진까지 ≈0.1%(0.9^64). createGuest 의
        // 재시도 루프가 매 시도 새 subset 을 뽑아 한 번 더 완충한다.
        private const val GUEST_NICKNAME_SAMPLE_SIZE = 64
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
                )
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
    @Transactional
    fun createSocialUser(profileImage: String?): User =
        saveNewUser(generateUniqueGuestNickname(), profileImage ?: defaultProfileImages.random(), IdentityType.MEMBER)

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
            )
        } catch (e: DataIntegrityViolationException) {
            if (isNicknameUniqueViolation(e)) throw UserException.duplicateNickname()
            throw e
        }

    // DataIntegrityViolationException 이 닉네임 unique 제약(uq_users_nickname) 위반인지 판별한다.
    // cause 체인을 끝까지 훑는다 — PersistenceException 같은 래퍼가 한 겹 더 끼면 e.cause 만 봐서는 ConstraintViolationException
    // 을 놓쳐 닉네임 충돌이 409 대신 500 으로 샐 수 있다. 각 단계에서 ConstraintViolationException 의 constraintName(Hibernate 가
    // 못 채우면 null)과 예외 메시지(드라이버가 제약명을 담는다) 둘 다 본다.
    private fun isNicknameUniqueViolation(e: DataIntegrityViolationException): Boolean =
        generateSequence(e as Throwable) { it.cause }.any { t ->
            (t as? ConstraintViolationException)?.constraintName?.contains(USERS_NICKNAME_CONSTRAINT, ignoreCase = true) == true ||
                t.message?.contains(USERS_NICKNAME_CONSTRAINT, ignoreCase = true) == true
        }

    @Transactional(readOnly = true)
    fun findById(userId: UUID): User = userRepository.findById(userId) ?: throw UserException.notFound()

    // 마이페이지(GET /me) 조회 — User(정체성)와 UserDetail 의 email 을 한 트랜잭션에서 모은다.
    // email 은 미수집(게스트)·미동의·backfill 전이면 UserDetail 이 없거나 null 이라 그대로 null 로 내려간다.
    @Transactional(readOnly = true)
    fun getMyProfile(userId: UUID): UserProfile {
        val user = findById(userId)
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
        val user = findById(userId)
        user.deletedAt?.let { throw UserException.deletedUser() }
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
        val user = findById(userId)
        user.deletedAt?.let { throw UserException.deletedUser() }
        user.updateProfileImage(profileImageUrl)
        return userRepository.save(user)
    }

    @Transactional
    fun promoteToMember(userId: UUID): User {
        val user = findById(userId)
        user.deletedAt?.let { throw UserException.deletedUser() }
        user.promoteToMember()
        return userRepository.save(user)
    }

    @Transactional
    fun softDelete(userId: UUID) {
        val user = findById(userId)
        user.softDelete()
        userRepository.save(user)
    }

    private fun generateUniqueGuestNickname(): String {
        val sample = NICKNAME_POOL.shuffled().take(GUEST_NICKNAME_SAMPLE_SIZE)
        val taken = userRepository.findNicknamesIn(sample).toSet()
        return (sample - taken).randomOrNull() ?: throw UserException.nicknameGenerationFailed()
    }
}
