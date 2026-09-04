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

// 클라 confirm 이 오지 않아도 서버가 S3 를 확인해 등록을 마치는 백스톱.
//
// **@Async 로 바꾸지 말 것.** @Async 와 @Scheduled 를 같은 메서드에 걸면 메서드가 즉시 반환돼
// 재진입 가드가 async body 안으로 들어가 무력해지고, fixedDelay 가 fixedRate 처럼 동작한다.
// 가드는 스케줄러 스레드에서 확인해야 실효가 있다.
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

    fun pollOnce() {
        val now = LocalDateTime.now()
        registerUploaded(now)
        expireStale(now)
    }

    private fun registerUploaded(now: LocalDateTime) {
        pendingUploadRepository
            .findLiveForPolling(now, now.minus(POLL_GRACE), BATCH_SIZE)
            .groupBy { RegisterGroup(it.userId, it.context, it.tournamentId) }
            .forEach { (group, uploads) ->
                // 하나라도 판단이 안 되면 그룹 전체를 보류한다 - 일시 오류로 배치가 쪼개지면 정원 판정이 부분적으로 갈린다.
                val checked = uploads.mapNotNull { up -> uploadedOrUnknown(up.imageKey)?.let { up.imageKey to it } }
                if (checked.size < uploads.size) return@forEach
                val uploadedKeys = checked.filter { it.second }.map { it.first }
                if (uploadedKeys.isEmpty()) return@forEach
                runCatching { registerGroup(group, uploadedKeys) }
                    .onFailure { e -> log.warn("pending 그룹(user={}, ctx={}) 등록 실패(다음 폴링 재시도): {}", group.userId, group.context, e.message) }
            }
    }

    private fun expireStale(now: LocalDateTime) {
        val checked =
            pendingUploadRepository.findExpired(now, BATCH_SIZE).mapNotNull { upload ->
                val exists = uploadedOrUnknown(upload.imageKey) ?: return@mapNotNull null
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
                            log.warn("업로드됐으나 등록 못 한 채 만료된 pending 폐기(영구 사유): {}", e.message)
                            pendingUploadRepository.deleteAll(uploads)
                        } else {
                            log.warn("만료 pending 등록 일시 실패, 다음 폴링 재시도: {}", e.message)
                        }
                    }
            }
    }

    private fun uploadedOrUnknown(imageKey: String): Boolean? =
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
                    group.tournamentId ?: error("TOURNAMENT pending 그룹에 tournamentId 가 없다"),
                )
        }
    }

    private data class RegisterGroup(
        val userId: UUID,
        val context: PendingUploadContext,
        val tournamentId: Long?,
    )

    companion object {
        private const val BATCH_SIZE = 100

        // confirm 이 먼저 처리할 시간을 준다. 같은 key 를 다투는 레이스를 시간으로 갈라 줄인다.
        private val POLL_GRACE: Duration = Duration.ofSeconds(15)
    }
}
