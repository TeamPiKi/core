package com.depromeet.piki.product.service.remote

import com.depromeet.piki.common.storage.S3Properties
import com.depromeet.piki.contracts.extraction.v1.ExtractionPolicy
import com.depromeet.piki.product.routing.DbDomainAccessPolicy
import com.depromeet.piki.product.routing.DomainAccess
import com.depromeet.piki.product.routing.DomainAccessPolicyEntity
import com.depromeet.piki.product.routing.DomainAccessPolicyJpaRepository
import com.depromeet.piki.support.IntegrationTestSupport
import com.google.protobuf.util.JsonFormat
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// 정책 캐시는 롤백으로 되돌아가지 않아 @Transactional 을 쓰지 않고 자기 행을 지운 뒤 다시 적재한다
class ExtractionPolicyPublisherIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var publisher: ExtractionPolicyPublisher

    @Autowired private lateinit var redis: StringRedisTemplate

    @Autowired private lateinit var policyRepository: DomainAccessPolicyJpaRepository

    @Autowired private lateinit var accessPolicy: DbDomainAccessPolicy

    @Autowired private lateinit var s3Properties: S3Properties

    @Test
    fun `차단 도메인과 허락 도메인과 이미지 버킷을 계약의 키에 올린다`() {
        val blocked = "blocked-${UUID.randomUUID()}.example.com"
        val allowed = "allowed-${UUID.randomUUID()}.example.com"
        try {
            policyRepository.save(DomainAccessPolicyEntity(domain = blocked, access = DomainAccess.BLOCKED.name, reason = "테스트"))
            policyRepository.save(
                DomainAccessPolicyEntity(domain = allowed, access = DomainAccess.ALLOWED.name, reason = null, permissionRef = "test permission"),
            )
            accessPolicy.reload()

            publisher.publish()

            val policy = publishedPolicy()
            assertTrue(blocked in policy.blockedDomainsList)
            assertTrue(allowed in policy.authorizedDomainsList)
            assertFalse(allowed in policy.blockedDomainsList)
            assertEquals(s3Properties.bucket, policy.imageBucket)
        } finally {
            deletePolicies(blocked, allowed)
        }
    }

    @Test
    fun `값이 그대로면 판을 올리지 않고 바뀌면 올린다`() {
        val blocked = "blocked-${UUID.randomUUID()}.example.com"
        try {
            publisher.publish()
            val first = publishedPolicy().version
            publisher.publish()
            val unchanged = publishedPolicy().version
            policyRepository.save(DomainAccessPolicyEntity(domain = blocked, access = DomainAccess.BLOCKED.name, reason = "테스트"))
            accessPolicy.reload()

            publisher.publish()

            assertEquals(first, unchanged)
            assertTrue(publishedPolicy().version > first)
        } finally {
            deletePolicies(blocked)
        }
    }

    @Test
    fun `키가 지워져도 다음 올리기에 되살아난다`() {
        publisher.publish()
        redis.delete(ExtractionPolicyPublisher.KEY)

        publisher.publish()

        assertTrue(redis.hasKey(ExtractionPolicyPublisher.KEY))
    }

    private fun publishedPolicy(): ExtractionPolicy {
        val builder = ExtractionPolicy.newBuilder()
        JsonFormat.parser().merge(redis.opsForValue().get(ExtractionPolicyPublisher.KEY), builder)
        return builder.build()
    }

    private fun deletePolicies(vararg domains: String) {
        domains.forEach { domain -> policyRepository.findById(domain).ifPresent { policyRepository.delete(it) } }
        accessPolicy.reload()
        publisher.publish()
    }
}
