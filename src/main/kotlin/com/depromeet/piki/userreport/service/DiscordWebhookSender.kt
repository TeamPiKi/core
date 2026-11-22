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
 */
interface DiscordWebhookSender {
    fun send(
        webhookUrl: String,
        username: String,
        embeds: List<Map<String, Any>>,
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
    ): Boolean =
        try {
            client
                .post()
                .uri(webhookUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("username" to username, "embeds" to embeds))
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
