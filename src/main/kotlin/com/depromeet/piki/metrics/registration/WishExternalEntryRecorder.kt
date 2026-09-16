package com.depromeet.piki.metrics.registration

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class WishExternalEntryRecorder(
    private val repository: WishExternalEntryRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun record(
        wishId: Long,
        rawEntryPoint: String?,
    ) {
        val entryPoint = ExternalEntry.from(rawEntryPoint) ?: return
        runCatching {
            repository.save(WishExternalEntry(wishId = wishId, entryPoint = entryPoint))
        }.onFailure { log.warn("위시 외부 유입 기록 실패 — 등록에는 영향 없음 (wishId=$wishId)", it) }
    }
}
