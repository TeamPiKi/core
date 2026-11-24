package com.depromeet.piki.product.service.remote

import com.depromeet.piki.common.storage.S3Properties
import com.depromeet.piki.contracts.extraction.v1.ExtractionPolicy
import com.depromeet.piki.contracts.extraction.v1.ExtractionProto
import com.depromeet.piki.product.domain.ParseInputType
import com.depromeet.piki.product.routing.DomainAccessPolicy
import com.google.protobuf.util.JsonFormat
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

// extractor 가 스트림 요청을 처리할 때 쓰는 정책 값을 이 환경의 Redis 에 올림. 못 읽었을 때의 규칙은 extraction-api.md 8절
@Component
class ExtractionPolicyPublisher(
    private val redis: StringRedisTemplate,
    private val accessPolicy: DomainAccessPolicy,
    private val modelSettings: ExtractionModelSettings,
    private val identitySettings: ExtractionIdentitySettings,
    private val s3Properties: S3Properties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private var published: ExtractionPolicy? = null

    // 값이 그대로여도 다시 씀. 키가 지워졌을 때 되살리는 길이 이것뿐
    @Scheduled(fixedDelay = PUBLISH_INTERVAL_MS)
    @Synchronized
    fun publish() {
        try {
            val policy = versioned(current())
            redis.opsForValue().set(KEY, JsonFormat.printer().omittingInsignificantWhitespace().print(policy))
            published = policy
        } catch (e: Exception) {
            log.warn("extraction.policy.publish 정책 배포본을 올리지 못함. 다음 주기에 다시 올림: {}", e.toString())
        }
    }

    // 값이 바뀔 때만 판을 올림. 매번 올리면 extractor 가 주기마다 다시 해석함
    private fun versioned(content: ExtractionPolicy): ExtractionPolicy {
        val last = published ?: return content.toBuilder().setVersion(System.currentTimeMillis()).build()
        if (last.toBuilder().clearVersion().build() == content) return last
        return content.toBuilder().setVersion(maxOf(System.currentTimeMillis(), last.version + 1)).build()
    }

    private fun current(): ExtractionPolicy =
        ExtractionPolicy
            .newBuilder()
            .setLinkModel(modelSettings.modelOverrideOf(ParseInputType.LINK).orEmpty())
            .setImageModel(modelSettings.modelOverrideOf(ParseInputType.IMAGE).orEmpty())
            .setIdentity(identitySettings.identityOverride().orEmpty())
            .addAllBlockedDomains(accessPolicy.blockedDomains())
            .addAllAuthorizedDomains(accessPolicy.authorizedDomains())
            .setImageBucket(s3Properties.bucket)
            .build()

    companion object {
        val KEY: String = ExtractionPolicy.getDescriptor().options.getExtension(ExtractionProto.redisKey)
        private const val PUBLISH_INTERVAL_MS = 60_000L
    }
}
