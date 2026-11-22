package com.depromeet.piki.user.service

import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.user.domain.User
import com.depromeet.piki.user.domain.UserException
import com.depromeet.piki.user.event.UserCreated
import com.depromeet.piki.user.repository.UserDetailRepository
import com.depromeet.piki.user.repository.UserRepository
import com.depromeet.piki.user.service.dto.UserProfile
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
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
    }

    // 게스트 닉네임은 풀에서 뽑는다. 중복 허용(#1287)이라 유일성 확인도, 충돌 재시도도 없다.
    // 같은 조합이 여러 게스트에게 나가도 되고, 토너먼트 안 구분은 참여 닉네임이 그 안에서만 유일하면 충족된다.
    fun createGuest(): User =
        userRepository
            .save(
                User(
                    id = UUID.randomUUID(),
                    nickname = randomPoolNickname(),
                    profileImage = defaultProfileImages.random(),
                    identityType = IdentityType.GUEST,
                ),
            ).also { eventPublisher.publishEvent(UserCreated(it.getId())) }

    @Transactional
    fun createGuestWithNickname(nickname: String): User =
        saveNewUser(nickname, defaultProfileImages.random(), IdentityType.GUEST)

    @Transactional
    fun createMember(nickname: String): User = saveNewUser(nickname, defaultProfileImages.random(), IdentityType.MEMBER)

    // 소셜 신규 가입용 MEMBER 생성. 닉네임은 게스트와 동일하게 풀에서 뽑고 사용자가 나중에 수정한다.
    // 프로필 이미지는 provider 가 준 게 있으면 쓰고, 없으면(동의 거부 등) 기본 아바타 중 랜덤.
    @Transactional
    fun createSocialUser(profileImage: String?): User =
        userRepository
            .save(
                User(
                    id = UUID.randomUUID(),
                    nickname = randomPoolNickname(),
                    profileImage = profileImage ?: defaultProfileImages.random(),
                    identityType = IdentityType.MEMBER,
                ),
            ).also { eventPublisher.publishEvent(UserCreated(it.getId())) }

    // 신규 user 영속화 공통 경로. 중복 허용(#1287) 이후 닉네임이 제약을 걸지 않으므로 충돌 변환이 없다.
    private fun saveNewUser(
        nickname: String,
        profileImage: String,
        identityType: IdentityType,
    ): User =
        userRepository
            .save(
                User(id = UUID.randomUUID(), nickname = nickname, profileImage = profileImage, identityType = identityType),
            ).also { eventPublisher.publishEvent(UserCreated(it.getId())) }

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

    // 여러 사용자를 한 번에 읽되 탈퇴자는 뺀다. 목록 화면이 행마다 조회하면 N+1 이 되므로 묶어서 받는다(#1288).
    //
    // 없는 id·탈퇴한 id 는 예외가 아니라 결과에서 빠진다 — 단건 findActiveById 와 계약이 다르다.
    // 목록은 "그 사이 탈퇴한 사람" 을 정상 흐름으로 다뤄야 해서, 한 명 때문에 전체가 404 가 되면 안 된다.
    @Transactional(readOnly = true)
    fun findActiveByIds(userIds: Collection<UUID>): List<User> {
        if (userIds.isEmpty()) return emptyList()
        return userRepository.findByIds(userIds).filter { it.isActive() }
    }

    // 두 사용자 행을 함께 잠근다(#1288 친구 추가). 활성 판정은 하지 않고 찾은 것만 돌려준다 —
    // 같은 "탈퇴함" 이라도 경로마다 응답이 달라서다(요청자 본인은 409, 상대는 404).
    // 없는 id 는 결과에 없다.
    //
    // 잠금 순서는 질의가 id 오름차순으로 고정한다 — A→B 와 B→A 가 동시에 와도 같은 순서로 잡아 교착하지 않는다.
    // 탈퇴 cascade 도 user 행을 먼저 잠그므로 이 순서와 맞물린다.
    //
    // 반드시 트랜잭션 안에서 호출한다 — 락은 트랜잭션 경계까지만 유지된다.
    fun lockPairForUpdate(
        first: UUID,
        second: UUID,
    ): Map<UUID, User> {
        checkInTransaction()
        return userRepository.findAllByIdsForUpdate(setOf(first, second)).associateBy { it.id }
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

    // 중복 허용(#1287) 이후 프로필 닉네임에 "이미 쓰는 사람이 있다" 는 사유가 없다. 형식이 맞으면 항상 사용 가능이다.
    // 엔드포인트는 앱이 아직 호출하므로 남긴다 - 형식 검증은 요청 DTO(NicknameCheckRequest)와 예약 접두사 검사가
    // 담당하고, 이 메서드는 그 뒤에 남는 판정이 없음을 뜻한다. 앱이 호출을 멈추면 엔드포인트째 제거한다.
    //
    // DB 를 보지 않으므로 @Transactional 도 두지 않는다.
    fun isNicknameAvailable(
        nickname: String,
        userId: UUID?,
    ): Boolean {
        User.checkNicknameFormat(nickname)
        return true
    }

    // 내 정보(닉네임·프로필 이미지) 부분 수정 영속화 — 들어온 필드만 갱신한다. 둘을 한 트랜잭션에 묶어
    // "닉네임은 됐는데 이미지는 실패" 같은 부분 성공을 막는다. 이미지 S3 업로드(외부 호출)는 ProfileUpdateService 가
    // 트랜잭션 밖에서 끝낸 뒤 그 결과 URL 만 여기로 위임한다 (## 트랜잭션 경계 — 외부 호출은 트랜잭션 밖).
    @Transactional
    fun updateProfile(
        userId: UUID,
        nickname: String?,
        profileImageUrl: String?,
        isProfilePublic: Boolean? = null,
    ): User {
        val user = findActiveByIdForUpdate(userId)
        // 무엇이 바뀌었는지만 남긴다 — 닉네임 원문은 PII 라 값이 아니라 "어떤 필드가 변경됐나"만 로깅한다.
        val changedFields =
            buildList {
                // 게스트 거절(403)이 닉네임 변경보다 먼저 나도록 맨 앞에 둔다
                isProfilePublic?.let {
                    user.changeProfileVisibility(it)
                    add("isProfilePublic")
                }
                nickname?.let {
                    // 중복 검사가 없다(#1287) — 같은 닉네임을 여러 사람이 쓸 수 있다. 형식·예약 접두사는 updateNickname 이 본다.
                    user.updateNickname(it)
                    add("nickname")
                }
                profileImageUrl?.let {
                    user.updateProfileImage(it)
                    add("profileImage")
                }
            }
        // 닉네임에 유일 제약이 없어(#1287) 검사와 저장 사이의 race 창도 사라졌다. 충돌 변환 없이 그대로 저장한다.
        val saved = userRepository.save(user)
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

    // 풀에서 하나 뽑는다. 중복 허용(#1287)이라 소진 개념이 없어 조회도 확장도 필요 없다.
    // 이전에는 풀 소진 대비로 숫자 접미사를 붙이는 단계(#920)가 있었는데, 중복이 허용되면서 그 전제가 사라졌다.
    private fun randomPoolNickname(): String = NICKNAME_POOL.random()
}
