package com.depromeet.piki.metrics.report

import com.depromeet.piki.admin.config.AdminProperties
import com.depromeet.piki.admin.config.ConditionalOnAdminEnabled
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Duration

// Discord 채널에 embed 메시지를 게시하는 외부 호출 경계. 통합 테스트는 StubDiscordMessageSender 로 격리한다.
interface DiscordMessageSender {
    fun send(
        channelId: String,
        embeds: List<Map<String, Any>>,
    )
}

// Discord Bot API(POST /channels/{id}/messages)로 게시. 인증은 Authorization: Bot <token>.
// admin 켜진 환경에서만 뜬다(운영 백오피스 기능). 봇 토큰은 로그에 절대 raw 로 남기지 않는다.
@Component
@ConditionalOnAdminEnabled
class HttpDiscordMessageSender(
    private val adminProperties: AdminProperties,
) : DiscordMessageSender {
    private val log = LoggerFactory.getLogger(javaClass)

    private val client =
        RestClient
            .builder()
            .baseUrl(DISCORD_API_BASE)
            .requestFactory(
                org.springframework.http.client.SimpleClientHttpRequestFactory().apply {
                    setConnectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    setReadTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                },
            ).build()

    override fun send(
        channelId: String,
        embeds: List<Map<String, Any>>,
    ) {
        val token = adminProperties.discordBotToken
        if (token.isBlank()) {
            log.warn("Discord 봇 토큰 미설정 — 주간 리포트 게시 skip")
            return
        }
        try {
            client
                .post()
                .uri("/channels/{channelId}/messages", channelId)
                .header("Authorization", "Bot $token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("embeds" to embeds))
                .retrieve()
                .toBodilessEntity()
            log.info("주간 리포트 Discord 게시 완료 — channelId={}", channelId)
        } catch (e: Exception) {
            // 외부 의존성 실패 — 스케줄러·요청 스레드를 죽이지 않고 다음 주기/수동 재발송에 맡긴다. 토큰은 로그에 안 남긴다.
            log.warn("주간 리포트 Discord 게시 실패 — channelId={}, error={}", channelId, e.message)
        }
    }

    companion object {
        private const val DISCORD_API_BASE = "https://discord.com/api/v10"
        private const val TIMEOUT_SECONDS = 5L
    }
}
