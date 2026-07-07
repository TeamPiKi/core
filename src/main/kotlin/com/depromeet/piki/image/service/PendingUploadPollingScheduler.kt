package com.depromeet.piki.image.service

import com.depromeet.piki.common.config.AsyncConfig
import com.depromeet.piki.common.exception.HttpMappable
import com.depromeet.piki.common.storage.ImageStorage
import com.depromeet.piki.image.domain.PendingUpload
import com.depromeet.piki.image.domain.PendingUploadContext
import com.depromeet.piki.image.repository.PendingUploadRepository
import com.depromeet.piki.tournament.service.TournamentItemPersistenceService
import com.depromeet.piki.wishlist.service.WishPersistenceService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

// 이미지 등록 v2 폴링 백스톱 — 클라 confirm 에 의존하지 않고 "업로드된 pending 을 서버가 스스로 확인해 등록"한다.
// SQS 같은 이벤트 인프라 없이, 기존 outbox 폴링(ItemParsingScheduler)과 같은 방식으로 동작한다:
//   1. 아직 안 만료됐고 grace 가 지난 pending 을 집어 S3 HEAD(exists)로 업로드 여부 확인 → 올라온 것을 confirm 과 같은 배치로 등록.
//   2. 유효기간이 지난 pending 은 정리하되, 업로드는 됐는데 등록이 밀린 것은 유실 대신 마지막으로 배치 등록을 시도한다.
// 등록은 confirm 과 같은 registerClaimedImages(claim = FOR UPDATE 삭제)를 거치므로 멱등이다.
//
// 스케줄러 스레드는 재진입 가드만 확인하고 실제 폴링(HEAD·등록)을 전용 executor 에 넘긴 뒤 즉시 반환한다 — 외부 호출이
// 공유 스케줄러 스레드를 막아 파싱 dispatch·SSE heartbeat 를 굶기는 것을 방지한다(ItemParsingScheduler 가 파싱을 @Async 로
// 빼는 것과 같은 결). @Async 대신 executor.execute 를 직접 쓰는 이유: @Async + @Scheduled 를 같은 메서드에 걸면 메서드가
// 즉시 반환돼 AtomicBoolean 재진입 가드가 async body 안으로 들어가 무력해지고, fixedDelay 가 fixedRate 처럼 동작한다.
// 스케줄러 스레드에서 가드를 확인해야 이전 폴링이 아직 도는 동안 새 폴링을 확실히 건너뛴다.
//
// enabled=false 로 두면 @Scheduled 자동 실행만 끈다(통합 테스트가 stub exists 로 발급 매핑을 조용히 등록해 오염되는 것을 막고,
// 폴링 테스트는 pollOnce() 를 직접 호출해 결정적으로 검증한다).
@Component
class PendingUploadPollingScheduler(
    private val pendingUploadRepository: PendingUploadRepository,
    private val imageStorage: ImageStorage,
    private val wishPersistenceService: WishPersistenceService,
    private val tournamentItemPersistenceService: TournamentItemPersistenceService,
    @Qualifier(AsyncConfig.IMAGE_POLLING_EXECUTOR) private val pollingExecutor: Executor,
    @Value("\${image.upload-polling-enabled:true}") private val enabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 이전 폴링이 아직 도는 중이면(느린 S3 등) 겹쳐 돌지 않게 한다. 스케줄러 스레드에서 확인하므로 실효가 있다.
    private val running = AtomicBoolean(false)

    @Scheduled(fixedDelayString = "\${image.upload-poll-interval-ms:1000}")
    fun poll() {
        if (!enabled) return
        if (!running.compareAndSet(false, true)) return
        pollingExecutor.execute {
            try {
                pollOnce()
            } finally {
                running.set(false)
            }
        }
    }

    // 폴링 1회 — 자동 실행(poll)과 테스트 수동 호출이 공유하는 실제 로직.
    fun pollOnce() {
        val now = LocalDateTime.now()
        registerUploaded(now)
        expireStale(now)
    }

    // 대기 중 pending 을 confirm 과 같은 배치 단위(같은 user·context·tournament)로 묶어 등록한다.
    // 그룹 내 존재 확인 중 HEAD 가 일시 실패(S3 장애)하면 "안 올라옴(false)"으로 확정하지 않고 그룹 전체를 이번 주기에서 보류한다
    // — 일시 오류로 배치가 쪼개져 정원 판정이 부분적으로 갈리는 것을 막는다.
    private fun registerUploaded(now: LocalDateTime) {
        pendingUploadRepository
            .findLiveForPolling(now, now.minus(POLL_GRACE), BATCH_SIZE)
            .groupBy { RegisterGroup(it.userId, it.context, it.tournamentId) }
            .forEach { (group, uploads) ->
                // 그룹 내 각 pending 의 존재를 확인한다. HEAD 일시 실패(existsOrNull 가 null 을 돌려 판단 못 한 것)가
                // 하나라도 있으면 그룹 전체를 이번 주기에 보류한다(다음 폴링 재시도) — 일시 오류로 배치가 쪼개져
                // 정원 판정이 부분적으로 갈리는 것을 막는다. checked.size < uploads.size 면 보류 대상이 있다는 뜻이다.
                val checked = uploads.mapNotNull { up -> existsOrNull(up.imageKey)?.let { up.imageKey to it } }
                if (checked.size < uploads.size) return@forEach
                val uploadedKeys = checked.filter { it.second }.map { it.first }
                if (uploadedKeys.isEmpty()) return@forEach
                runCatching { registerGroup(group, uploadedKeys) }
                    .onFailure { e -> log.warn("pending 그룹(user={}, ctx={}) 등록 실패(다음 폴링 재시도): {}", group.userId, group.context, e.message) }
            }
    }

    // 만료된 pending 을 정리하되, 업로드는 됐는데 등록이 밀린 것은 유실 대신 배치로 마지막 등록을 시도한다:
    //   - 안 올라온 채 만료 → 삭제.
    //   - 등록 성공 → claim 으로 삭제됨.
    //   - 등록 실패가 영구 사유(정원 초과 등 계약 예외=HttpMappable) → 폐기하고 경고(다시 해도 같음. raw 는 lifecycle 이 정리).
    //   - 등록 실패가 일시 오류(DB deadlock·lock timeout 등 non-HttpMappable) → 삭제하지 않고 남겨 다음 폴링이 재시도(at-least-once 보존).
    //   - 존재 확인 자체가 실패(S3 장애) → 이번 정리 보류.
    // registerUploaded 와 같은 배치 단위(RegisterGroup)로 등록해 만료 경로에서도 정원 all-or-nothing 을 지킨다(단건 partial-fill 방지).
    private fun expireStale(now: LocalDateTime) {
        // 존재 확인 실패(S3 장애)는 이번 정리에서 제외(보류)한다 — uploaded / notUploaded 로만 가른다.
        val checked =
            pendingUploadRepository.findExpired(now, BATCH_SIZE).mapNotNull { upload ->
                val exists = existsOrNull(upload.imageKey) ?: return@mapNotNull null
                upload to exists
            }
        val notUploaded = checked.filter { !it.second }.map { it.first }
        if (notUploaded.isNotEmpty()) pendingUploadRepository.deleteAll(notUploaded)

        checked
            .filter { it.second }
            .map { it.first }
            .groupBy { RegisterGroup(it.userId, it.context, it.tournamentId) }
            .forEach { (group, uploads) ->
                runCatching { registerGroup(group, uploads.map { it.imageKey }) }
                    .onFailure { e ->
                        if (e is HttpMappable) {
                            // 영구 사유(정원 초과 등) — 다시 해도 같으니 폐기하고 경고한다(운영자 인지, raw 는 lifecycle 이 정리).
                            log.warn("업로드됐으나 등록 못 한 채 만료된 pending 폐기(영구 사유): {}", e.message)
                            pendingUploadRepository.deleteAll(uploads)
                        } else {
                            // 일시 오류(DB deadlock·lock timeout 등) — 유실 방지 위해 삭제하지 않고 남겨 다음 폴링이 재시도한다.
                            log.warn("만료 pending 등록 일시 실패, 다음 폴링 재시도: {}", e.message)
                        }
                    }
            }
    }

    // HEAD 는 외부 호출 — 실패(일시 장애)면 null 로 돌려, 호출부가 "안 올라옴(false)"과 구분해 판단을 보류하게 한다.
    private fun existsOrNull(imageKey: String): Boolean? =
        runCatching { imageStorage.exists(imageKey) }
            .getOrElse { e ->
                log.warn("pending {} 존재 확인 실패, 이번 주기 보류: {}", imageKey, e.message)
                null
            }

    private fun registerGroup(
        group: RegisterGroup,
        imageKeys: List<String>,
    ) {
        when (group.context) {
            PendingUploadContext.WISH ->
                wishPersistenceService.registerClaimedImages(imageKeys, group.userId)
            PendingUploadContext.TOURNAMENT ->
                tournamentItemPersistenceService.registerClaimedImages(
                    imageKeys,
                    group.userId,
                    // TOURNAMENT 매핑은 팩토리가 tournamentId 를 강제하므로 정상 흐름엔 항상 있다(없으면 코드 버그).
                    group.tournamentId ?: error("TOURNAMENT pending 그룹에 tournamentId 가 없다"),
                )
        }
    }

    // confirm 이 (user, context, tournament) 하나로 배치 등록하는 것과 같은 grouping key — 폴링도 이 단위로 묶어 원자성을 맞춘다.
    private data class RegisterGroup(
        val userId: UUID,
        val context: PendingUploadContext,
        val tournamentId: Long?,
    )

    companion object {
        private const val BATCH_SIZE = 100

        // 폴링은 confirm(빠른 경로)이 처리할 시간을 준 뒤에만 개입한다 — 발급 후 이 시간이 지난 pending 만 백스톱 대상으로 삼아,
        // confirm 과 같은 key 를 다투는 레이스(부분 응답·재시도 오탐·불필요한 HEAD)를 시간으로 분리해 줄인다.
        private val POLL_GRACE: Duration = Duration.ofSeconds(15)
    }
}
