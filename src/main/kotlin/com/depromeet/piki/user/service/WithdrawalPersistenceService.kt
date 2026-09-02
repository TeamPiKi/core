package com.depromeet.piki.user.service

import com.depromeet.piki.notification.fcm.repository.UserDeviceRepository
import com.depromeet.piki.notification.repository.NotificationRepository
import com.depromeet.piki.user.domain.UserException
import com.depromeet.piki.user.repository.UserDetailRepository
import com.depromeet.piki.user.repository.UserRepository
import com.depromeet.piki.wishlist.repository.WishRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

// 탈퇴의 DB 변경(cascade)만 짧은 단일 트랜잭션으로 묶는 빈. Redis/SSE 같은 외부 의존성은 여기서 다루지 않고
// 오케스트레이터(WithdrawalService)가 트랜잭션 밖에서 처리한다. 별도 빈으로 분리해 Spring AOP proxy 를 거치게 함으로써
// self-invocation 으로 @Transactional 이 무력화되는 함정을 피한다.
@Service
class WithdrawalPersistenceService(
    private val userRepository: UserRepository,
    private val userDetailRepository: UserDetailRepository,
    private val userDeviceRepository: UserDeviceRepository,
    private val wishRepository: WishRepository,
    private val notificationRepository: NotificationRepository,
    private val defaultProfileImages: DefaultProfileImages,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 회원 탈퇴 DB cascade. MEMBER 전용 — 게스트는 호출부가 사전에 거른다.
    // 멱등: 이미 tombstone(deletedAt 이 채워진 상태) 이면 아무 것도 하지 않고 즉시 반환(double-request 안전).
    //
    // 동시 탈퇴(double-request) 안전성: 아래 deletedAt 가드는 두 요청이 동시에 deletedAt=null 을 읽으면
    // 둘 다 통과할 수 있다(가드 자체는 race 를 완전히 막지 않는다 — fast-path 일 뿐). 그래도 안전한 이유는
    // cascade 의 각 단계가 전부 멱등이기 때문이다: withdraw()/softDelete() 는 deletedAt ?:= now 로 첫 값 유지,
    // user_details 파생 delete 는 0건 no-op, soft-delete 쿼리는 deletedAt IS NULL 가드로 2회차엔 0건.
    // 따라서 2회 실행돼도 같은 종단 상태가 되고 UNIQUE 충돌·예외가 나지 않는다. 완전 직렬화(락)는 불필요.
    @Transactional
    fun withdraw(userId: UUID) {
        // user 행을 FOR UPDATE 로 먼저 잠근다(#776). 활성 유저 쓰기 경로(프로필 수정·wish 등록·FCM 등록)는
        // 전부 user 행을 첫 락으로 잡으므로, 탈퇴 cascade 도 자식 테이블(wishes·user_devices 등)을 건드리기 전에
        // user 행을 먼저 잠가 락 순서를 "user → 자식" 하나로 통일한다. 이 통일이 없으면 cascade 는 자식부터 지우고
        // user UPDATE 는 커밋까지 미뤄져(save), 쓰기 경로(user→자식)와 락 순서가 엇갈려 데드락이 난다.
        val user = userRepository.findByIdForUpdate(userId) ?: throw UserException.notFound()
        user.deletedAt?.let {
            log.info("이미 탈퇴 처리된 유저 — cascade 생략(멱등) userId={}", userId)
            return
        }

        // 1. users 익명 tombstone 전이(softDelete + 닉네임/프로필 비식별화). 게스트면 도메인 check 위반 → 500.
        //    (정상 흐름은 호출부가 게스트를 403 으로 막아 여기 닿지 않는다.)
        //    프사는 탈퇴 전용 기본 아바타로 덮는다 — 공유 토너먼트 히스토리엔 참여자로 남으므로 빈 값이 아니라
        //    "탈퇴한 유저" 를 나타내는 이미지가 필요하다. URL 은 env 별 버킷을 흡수하도록 DefaultProfileImages 가 조립한다.
        user.withdraw(defaultProfileImages.deleted())
        userRepository.save(user)

        // 2. user_details 하드삭제 — socialId 즉시 파기(PIPA "지체없이 파기"), UNIQUE 풀려 재가입 가능.
        userDetailRepository.deleteByUserId(userId)

        // 3. user_devices 하드삭제 — 기기 토큰 제거.
        userDeviceRepository.deleteAllByUserId(userId)

        // 4. wishes 하드삭제 — 개인 데이터 즉시 파기(PIPA "지체없이 파기"). 위시는 다른 데이터가 참조하지 않아
        //    즉시 지워도 무결성 문제가 없다(공유 참조 보존은 tombstone users 행이 담당).
        val wishes = wishRepository.hardDeleteAllByUserId(userId)

        // 5. notifications 하드삭제 — 수신자 알림 즉시 파기.
        val notifications = notificationRepository.hardDeleteAllByUserId(userId)

        log.info("회원 탈퇴 cascade 완료 userId={} wishes={} notifications={}", userId, wishes, notifications)
    }
}
