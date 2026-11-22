package com.depromeet.piki.product.service.remote

import jakarta.annotation.PostConstruct
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

interface ExtractionIdentitySettings {
    // 지정이 없으면 null. 호출자는 요청에 싣지 않아 기본 신원으로 동작한다
    fun identityOverride(): String?
}

// DB(extraction_identity_settings) 기반 구현. 캐시와 갱신 경로의 이유는 DbExtractionModelSettings 와 같다.
@Component
class DbExtractionIdentitySettings(
    private val repository: ExtractionIdentityJpaRepository,
) : ExtractionIdentitySettings {
    @Volatile
    private var identity: String? = null

    @PostConstruct
    @Synchronized
    fun load() {
        identity = findOverride()?.identity
    }

    @Scheduled(fixedDelay = RELOAD_INTERVAL_MS)
    fun reload() = load()

    override fun identityOverride(): String? = identity

    fun findOverride(): ExtractionIdentityEntity? = repository.findById(ExtractionIdentityEntity.SINGLE_ROW_ID).orElse(null)

    companion object {
        private const val RELOAD_INTERVAL_MS = 300_000L
    }
}
