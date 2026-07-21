package com.depromeet.piki.user.service

import com.depromeet.piki.auth.infrastructure.redis.RefreshTokenStore
import com.depromeet.piki.auth.infrastructure.redis.WithdrawnTokenStore
import com.depromeet.piki.common.storage.ImageStorage
import com.depromeet.piki.notification.sse.LocalSseDelivery
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.user.domain.UserException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

// 회원 탈퇴 오케스트레이터. DB cascade(WithdrawalPersistenceService.@Transactional)를 먼저 끝내고,
// 그 다음 외부 의존성(Redis refresh token 무효화 · SSE 연결 종료)을 트랜잭션 밖에서 처리한다.
// 외부 호출을 트랜잭션 안에 넣지 않는 이유는 트랜잭션 경계 규약과 동일 — DB 커넥션을 외부 latency 동안 잡지 않기 위함.
@Service
class WithdrawalService(
    private val userService: UserService,
    private val withdrawalPersistenceService: WithdrawalPersistenceService,
    private val refreshTokenStore: RefreshTokenStore,
    private val withdrawnTokenStore: WithdrawnTokenStore,
    private val localSseDelivery: LocalSseDelivery,
    private val imageStorage: ImageStorage,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun withdraw(userId: UUID) {
        // 탈퇴는 MEMBER 전용. 게스트는 PII 도 없고 공유 토너먼트 참조 때문에 하드삭제도 불가하며, 스토어 요건은 계정 기준 → 403.
        // 멀쩡한 게스트 클라이언트가 정상 요청으로 닿을 수 있는 계약이라 커스텀 예외로 막는다(도메인 check 가 아닌 경계 검증).
        // 활성 조회(findActiveById)를 쓰지 않는다 — 재탈퇴는 이미 목표 상태이므로 409 가 아니라 멱등 성공이어야 한다.
        val user = userService.findById(userId)
        if (user.identityType == IdentityType.GUEST) throw UserException.guestCannotWithdraw()

        // 1. DB cascade (짧은 트랜잭션). 이미 tombstone 이면 멱등하게 통과.
        withdrawalPersistenceService.withdraw(userId)

        // 2. refresh token(Redis) 무효화 — 트랜잭션 밖(외부 의존성). 재발급 경로를 끊는다.
        refreshTokenStore.delete(userId)

        // 2-1. access token 무효화 — 탈퇴 회원을 denylist 에 마킹. JwtAuthenticationFilter 가 이를 확인해
        //      만료(최대 access token 수명) 전까지 남은 access token 을 즉시 거부한다. refresh 만 끊으면
        //      access token 이 만료까지 살아있어 탈퇴 회원이 계속 접근 가능한 구멍이 생긴다.
        withdrawnTokenStore.markWithdrawn(userId)

        // 3. SSE 연결 종료 — best-effort, 트랜잭션 밖. 인스턴스-로컬 연결만 끊는다.
        localSseDelivery.closeAll(userId)

        // 4. S3 프로필 이미지 파기 — best-effort, 트랜잭션 밖. tombstone 이 users.profile_image 를 기본 아바타로
        //    덮어 DB 포인터는 이미 정리됐고, 여기선 S3 객체 자체(얼굴 등 PII)를 prefix 통째로 삭제한다(PIPA·Apple 5.1.1(v)).
        //    객체가 없으면 no-op 이라 항상 호출해도 안전. 실패해도 탈퇴 자체는 성공 처리하고 후속 정리 대상으로 로그만 남긴다.
        runCatching { imageStorage.deleteByPrefix("profiles/$userId/") }
            .onFailure { e -> log.warn("탈퇴 S3 프로필 정리 실패(후속 정리 대상) userId={}", userId, e) }

        log.info("회원 탈퇴 완료 userId={}", userId)
    }
}
