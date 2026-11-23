package com.depromeet.piki.support

import com.depromeet.piki.common.exception.HttpMappable
import com.depromeet.piki.item.domain.ParseInput
import com.depromeet.piki.item.event.ItemParseRequested
import com.depromeet.piki.item.service.ParseResultMessage
import com.depromeet.piki.product.domain.ParseInputType
import com.depromeet.piki.product.service.ProductSnapshot
import com.depromeet.piki.product.service.remote.RemoteExtractionContract
import io.lettuce.core.RedisBusyException
import org.awaitility.Awaitility.await
import org.springframework.data.redis.RedisSystemException
import org.springframework.data.redis.connection.RedisStreamCommands.XDelOptions
import org.springframework.data.redis.connection.stream.Consumer
import org.springframework.data.redis.connection.stream.ReadOffset
import org.springframework.data.redis.connection.stream.StreamOffset
import org.springframework.data.redis.connection.stream.StreamReadOptions
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration

// extractor 자리. 요청 스트림을 읽어 stub 으로 추출하고 결과 스트림에 쓴다. 배경에서 돌지 않으므로 테스트가 awaitTicking 으로 돌린다
class FakeExtractor(
    private val redis: StringRedisTemplate,
    private val linkSnapshotExtractor: StubLinkSnapshotExtractor,
    private val imageSnapshotExtractor: StubImageSnapshotExtractor,
) {
    fun drain() {
        createGroupIfAbsent()
        val streams = redis.opsForStream<String, String>()
        streams
            .read(Consumer.from(GROUP, CONSUMER), StreamReadOptions.empty().count(BATCH), StreamOffset.create(ItemParseRequested.STREAM, ReadOffset.lastConsumed()))
            .orEmpty()
            .forEach { request ->
                streams.add(ParseResultMessage.STREAM, resultOf(request.value))
                streams.acknowledgeAndDelete(GROUP, request, XDelOptions.defaults())
            }
    }

    fun awaitTicking(
        timeout: Duration = Duration.ofSeconds(5),
        condition: () -> Boolean,
    ) {
        await().atMost(timeout).pollInterval(TICK_INTERVAL).until {
            drain()
            condition()
        }
    }

    private fun resultOf(request: Map<String, String>): Map<String, String> {
        val requestId = mapOf("requestId" to request.getValue("requestId"))
        return runCatching { extract(request) }.fold(
            onSuccess = { requestId + ("code" to ParseResultMessage.CODE_OK) + fieldsOf(it) },
            onFailure = { requestId + ("code" to codeOf(it)) },
        )
    }

    private fun extract(request: Map<String, String>): ProductSnapshot =
        when (val input = ParseInput.of(ParseInputType.valueOf(request.getValue("inputType")), request.getValue("inputValue"))) {
            is ParseInput.Link -> linkSnapshotExtractor.extract(input.link)
            is ParseInput.Image -> imageSnapshotExtractor.extract(input.key)
        }

    private fun fieldsOf(snapshot: ProductSnapshot): Map<String, String> =
        listOfNotNull(
            snapshot.name?.let { "name" to it },
            snapshot.imageUrl?.let { "imageUrl" to it },
            snapshot.price?.let { "currentPrice" to it.toString() },
            snapshot.originalPrice?.let { "originalPrice" to it.toString() },
            snapshot.currency?.let { "currency" to it },
            snapshot.finalUrl?.let { "finalUrl" to it.toString() },
            snapshot.method?.let { "method" to it.name },
        ).toMap()

    // 계약상 확정 실패 code 로 옮길 수 있는 예외만 그 code 로, 나머지는 일시 실패 code 로 보낸다
    private fun codeOf(cause: Throwable): String {
        val errorCode = (cause as? HttpMappable)?.errorCode ?: return TRANSIENT_CODE
        return RemoteExtractionContract.PERMANENT_TRANSLATIONS.entries
            .firstOrNull { (_, translation) -> (translation() as? HttpMappable)?.errorCode == errorCode }
            ?.key
            ?.name ?: TRANSIENT_CODE
    }

    private fun createGroupIfAbsent() {
        try {
            redis.opsForStream<String, String>().createGroup(ItemParseRequested.STREAM, ReadOffset.from("0"), GROUP)
        } catch (e: RedisSystemException) {
            if (e.cause !is RedisBusyException) throw e
        }
    }

    companion object {
        private const val GROUP = "extractor"
        private const val CONSUMER = "fake-extractor"
        private const val BATCH = 100L
        private const val TRANSIENT_CODE = "UPSTREAM_ERROR"
        private val TICK_INTERVAL = Duration.ofMillis(50)
    }
}
