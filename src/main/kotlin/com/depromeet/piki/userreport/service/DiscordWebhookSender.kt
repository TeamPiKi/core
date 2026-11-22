package com.depromeet.piki.userreport.service

import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Duration

/**
 * 디스코드 웹훅에 embed 를 게시하는 외부 호출 경계. 통합 테스트는 stub 으로 격리한다.
 *
 * 봇 토큰 경로([com.depromeet.piki.metrics.report.DiscordMessageSender])와 따로 두는 이유: 봇 API 는
 * 보내는 이름을 지정할 수 없어 앱 이름·서버 별명이 그대로 뜬다. 웹훅은 메시지마다 `username` 을 받으므로
 * 환경별로 다른 이름(`dev-신고알림` · `prod-신고알림`)을 띄울 수 있다. 운영이 쓰는 Grafana 알림도 같은 방식이다.
 *
 * 웹훅 URL 은 그 자체가 게시 권한이다 — 로그에 절대 남기지 않는다.
 *
 * components(버튼)는 **앱이 만든 웹훅에서만** 실린다. 콘솔에서 사람이 만든 웹훅으로 보내면 디스코드가 그 필드를
 * 거부하므로, 버튼을 쓰려면 봇 API 로 만든 웹훅 URL 을 넣어야 한다.
 */
interface DiscordWebhookSender {
    fun send(
        webhookUrl: String,
        username: String,
        embeds: List<Map<String, Any>>,
        components: List<Map<String, Any>> = emptyList(),
    ): Boolean
}

@Component
class HttpDiscordWebhookSender : DiscordWebhookSender {
    private val log = LoggerFactory.getLogger(javaClass)

    // baseUrl 을 고정하지 않는다 — 웹훅 URL 자체가 전체 주소다
    private val client =
        RestClient
            .builder()
            .requestFactory(
                SimpleClientHttpRequestFactory().apply {
                    setConnectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    setReadTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                },
            ).build()

    override fun send(
        webhookUrl: String,
        username: String,
        embeds: List<Map<String, Any>>,
        components: List<Map<String, Any>>,
    ): Boolean =
        try {
            // 빈 배열을 보내지 않는다 — 사람이 만든 웹훅은 components 필드 자체를 거부한다
            val payload =
                buildMap<String, Any> {
                    put("username", username)
                    put("embeds", embeds)
                    if (components.isNotEmpty()) put("components", components)
                }
            client
                .post()
                .uri(webhookUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .toBodilessEntity()
            true
        } catch (e: Exception) {
            // 외부 의존성 실패 — 호출자가 삼킨다. URL 은 남기지 않고 보내는 이름만 남겨 어느 환경인지 드러낸다
            log.warn("디스코드 웹훅 게시 실패 username={} error={}", username, e.message)
            false
        }

    companion object {
        private const val TIMEOUT_SECONDS = 5L
    }
}
