package com.depromeet.piki.notification.fcm.service

import com.depromeet.piki.common.logging.SensitiveData
import com.depromeet.piki.notification.fcm.domain.UserDevice
import com.depromeet.piki.notification.fcm.repository.UserDeviceRepository
import com.depromeet.piki.user.service.UserService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

// FCM 토큰 등록 upsert 의 "한 번의 트랜잭션 시도"(#244 upsert + #396 동시성). 실제 영속화를 UserDeviceService 에서
// 떼어내 별도 빈으로 둔다 — 같은 빈 안에서 @Transactional 메서드를 호출하면 self-invocation 으로 proxy 를 못 거쳐
// 매 시도가 독립 트랜잭션이 되지 못하기 때문이다. UserDeviceService.register 가 이 빈을 proxy 경유로 호출해,
// 동시 UNIQUE 충돌 시 실패한 트랜잭션을 버리고 새 트랜잭션으로 재시도할 수 있게 한다.
@Component
class UserDeviceWriter(
    private val userDeviceRepository: UserDeviceRepository,
    private val userService: UserService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 토큰 등록/갱신 upsert 한 번 — 앱 진입·토큰 회전 어느 경로로 와도 기기당 1 row 로 수렴한다.
    // 배달 정확성을 위해 같은 토큰을 들고 있던 다른 사용자 row 는 먼저 해제하고, 내 (user,device) row 가
    // 있으면 토큰만 교체, 없으면 신규 생성한다. 동시 등록으로 INSERT 가 UNIQUE 충돌나면
    // DataIntegrityViolationException 이 나며, 호출자(UserDeviceService.register)가 재시도로 흡수한다.
    @Transactional
    fun upsert(
        userId: UUID,
        deviceId: String,
        fcmToken: String,
    ): UserDevice {
        // 활성 유저 확인·쓰기 경합 차단(#776) — user 행을 잠가 tombstone 이면 거부한다. 이 락을 upsert 의 첫 걸음으로
        // 잡아, 탈퇴 cascade(user 행 UPDATE 로 시작 → user_devices 삭제)와 직렬화한다: 탈퇴가 먼저면 여기서 409,
        // 등록이 먼저면 탈퇴 cascade 가 뒤에서 이 기기를 지운다 — 어느 순서든 tombstone 유저의 기기 행이 남지 않는다.
        // users 행이 없으면(인증만 된 미가입 등 기존 계약) 통과시킨다 — 유효 토큰이면 prod 엔 항상 행이 있다.
        userService.rejectIfWithdrawnForUpdate(userId)
        // deviceId·fcmToken 은 유니크 키다. 앞뒤 공백을 입력 경계에서 정규화해야 "x"/"x " 가 다른 키로
        // 갈려 upsert 가 쪼개지거나 죽은 토큰 정리가 빗나가는 일을 막는다. (@NotBlank 는 공백-only 만 거른다)
        val device = deviceId.trim()
        val token = fcmToken.trim()
        val mine = userDeviceRepository.findByUserIdAndDeviceId(userId, device)
        releaseStaleTokenHolder(token, keep = mine)
        // 토큰 원문은 크리덴셜이라 지문으로만. deviceId 는 기기 식별자라 그대로(같은 기기의 등록/해제 상관추적 키).
        mine ?: run {
            log.info("FCM 토큰 등록(신규) userId={} deviceId={} token={}", userId, device, SensitiveData.maskToken(token))
            return userDeviceRepository.save(UserDevice(userId = userId, deviceId = device, fcmToken = token))
        }
        log.info("FCM 토큰 등록(갱신) userId={} deviceId={} token={}", userId, device, SensitiveData.maskToken(token))
        mine.refreshToken(token)
        return userDeviceRepository.save(mine)
    }

    // 같은 토큰을 들고 있던 다른 row 를 해제한다 — 토큰당 1 row(배달 정확성)를 유지하기 위함.
    // keep(내 기기 row)과 동일 row 면 두고, 다르면 holder 를 PK 기준 bulk delete 로 지운다.
    // 엔티티 delete+flush(정확히 1 row 단언) 대신 PK bulk delete 라 멱등하다 — 동시 등록으로 다른 트랜잭션이
    // 같은 holder 를 먼저 지워 0 row 가 돼도 StaleStateException(ObjectOptimisticLockingFailureException → 500)
    // 없이 무해하게 끝난다(#396 후속, Sentry delete 경쟁). PK 조건이라 fcm_token 갭락을 안 잡아 데드락도 없고,
    // bulk delete 는 즉시 실행돼 후속 save 보다 먼저 DB 에 반영되므로 UNIQUE(fcm_token) 충돌을 막던 flush 도 불필요하다.
    private fun releaseStaleTokenHolder(
        fcmToken: String,
        keep: UserDevice?,
    ) {
        val holder = userDeviceRepository.findByFcmToken(fcmToken) ?: return
        keep?.let { if (holder.getId() == it.getId()) return }
        userDeviceRepository.deleteByIdBulk(holder.getId())
    }
}
