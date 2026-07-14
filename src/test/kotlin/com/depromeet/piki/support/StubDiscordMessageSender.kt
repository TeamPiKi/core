package com.depromeet.piki.support

import com.depromeet.piki.metrics.report.DiscordMessageSender

// Discord 게시 외부 경계 격리. 실제 HTTP 없이 게시된 (channelId, embeds) 를 캡처해 통합 테스트가 payload 를 단언한다.
class StubDiscordMessageSender : DiscordMessageSender {
    data class Sent(
        val channelId: String,
        val embeds: List<Map<String, Any>>,
    )

    val sent = mutableListOf<Sent>()

    // 게시 성공/실패 시나리오를 테스트가 제어할 수 있게 결과를 필드로 둔다(기본 성공).
    var result: Boolean = true

    override fun send(
        channelId: String,
        embeds: List<Map<String, Any>>,
    ): Boolean {
        sent += Sent(channelId, embeds)
        return result
    }
}
