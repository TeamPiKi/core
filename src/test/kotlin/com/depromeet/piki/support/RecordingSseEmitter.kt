package com.depromeet.piki.support

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.CopyOnWriteArrayList

// send(SseEventBuilder) 를 가로채 실제 IO 없이 전송 내용을 기록하는 공유 test double (#578 의 파일별 중복 통합).
// build() 가 내놓는 항목들을 send 단위로 보존한다 — [메타 라인 문자열("id:..\nevent:..\ndata:"), payload, 종결 개행].
// 운영 경로(LocalSseDelivery)가 payload 를 JSON 문자열로 직렬화해 실으므로, payload 항목은 JSON 문자열이다.
class RecordingSseEmitter : SseEmitter() {
    // send 1회 = 항목 리스트 1개. 이벤트 간 순서와 한 이벤트의 메타·payload 짝을 함께 보존한다.
    val sends = CopyOnWriteArrayList<List<Any?>>()

    override fun send(builder: SseEmitter.SseEventBuilder) {
        sends.add(builder.build().map { it.data })
    }

    fun hasEvent(eventName: String): Boolean = metasOf(eventName).isNotEmpty()

    // 그 이름의 이벤트로 실린 payload JSON 문자열들 (send 순서 보존).
    fun payloadsOf(eventName: String): List<String> =
        sends
            .filter { it.metaOrNull()?.contains("event:$eventName") == true }
            .mapNotNull { send -> send.getOrNull(1) as? String }

    // 그 이름의 이벤트에 실린 SSE id 들 — 메타 문자열의 "id:" 라인에서 뽑는다. id 없는 이벤트는 제외된다.
    fun idsOf(eventName: String): List<String> =
        metasOf(eventName).mapNotNull { meta ->
            meta.lineSequence().firstOrNull { it.startsWith("id:") }?.removePrefix("id:")
        }

    private fun metasOf(eventName: String): List<String> =
        sends.mapNotNull { it.metaOrNull() }.filter { it.contains("event:$eventName") }

    private fun List<Any?>.metaOrNull(): String? = firstOrNull() as? String
}
