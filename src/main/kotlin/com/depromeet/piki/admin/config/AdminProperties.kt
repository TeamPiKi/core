package com.depromeet.piki.admin.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * prod 운영 백오피스 설정. `@ConfigurationPropertiesScan`(PikiApplication)으로 자동 등록.
 *
 * 옛 백오피스의 @Profile("!prod") 숨김과 달리 명시 플래그(enabled)로 켠다. 슬랙-IP 접근 게이트(#526)가
 * 들어와 enabled=true 를 전 환경에 둬도 안전하다 — /admin 은 슬랙 세션, dev 도메인은 IP allowlist 로 막힌다.
 *
 * @property enabled 백오피스 빈 전체 게이트(ConditionalOnAdminEnabled). 전 환경 true(게이트가 보호).
 * @property environmentGate dev 도메인 전체를 allowlist IP 로만 여는 게이트(EnvironmentAccessFilter). prod 는 false(공개).
 * @property discordPublicKey Discord 슬래시커맨드 Ed25519 검증 공개키(hex). 비면 Discord 진입이 항상 거부돼 게이트를 못 연다.
 * @property discordAdminUserIds 백오피스 접근 허용 Discord userId 집합. 슬래시커맨드 실행자가 여기 없으면 링크 미발급.
 * @property discordAdminChannelId admin 인터랙션 허용 Discord 채널 id. 이 채널 밖 실행은 거부. 비면 전부 거부(fail-closed).
 * @property environment 이 앱이 도는 환경(dev/prod). grant 토큰의 env 바인딩 검증에 쓴다.
 * @property grantSigningKey grant 토큰 HMAC 서명 공유키(전 환경 동일값). 발급 env 와 소비 env 가 달라도 서명이 검증되게 한다.
 * @property grantHosts env → admin 접속 호스트(base URL) 맵. 선택한 env 의 grant 링크를 만든다.
 * @property allowlistTtl 허용 IP TTL(무활동 만료, IP 변동 흡수). /admin 게이트는 sliding 없이 이 시간만 살고 연장은 명시적
 *   버튼으로만 한다(#669) — dev 도메인 게이트만 접근마다 갱신한다. server.servlet.session.timeout 과 같은 값으로 맞춘다(#885).
 * @property grantTokenTtl 원타임 grant 링크 토큰 수명(짧게).
 * @property localBypass 로컬 개발에서 /admin 게이트(AdminAccessFilter)를 건너뛴다. 배포 환경은 false.
 * @property scheduleGraceWindow 예약 발송 유예시간. 예약시각을 이 시간보다 더 넘겨 도래하면(다운타임 등) 발송하지 않고 MISSED 로 정리한다.
 * @property discordBotToken Discord Bot API 인증 토큰(민감). 주간 지표 리포트를 채널에 게시할 때 Authorization: Bot 헤더로 쓴다. 비면 게시 skip.
 * @property discordMetricsChannelId 주간 지표 리포트를 게시할 Discord 채널 id. 비면 게시 skip(fail-safe).
 * @property discordReportChannelId 신고 알림이 게시되는 Discord 채널 id(#1291). 그 채널의 버튼 클릭만 받아들이는
 *   게이트에 쓴다 — admin 채널 하나만 허용하면 신고 채널의 버튼이 거부된다. 비면 버튼 클릭 전부 거부(fail-closed)고,
 *   알림 게시와 슬래시커맨드 진입은 그대로 동작한다.
 * @property discordReportWebhookUrl 사용자 신고 알림을 게시할 Discord 웹훅 URL(#1291, 민감 — URL 자체가 게시 권한).
 *   봇 토큰 대신 웹훅을 쓰는 이유는 보내는 이름을 메시지마다 지정할 수 있어서다(dev-신고알림 · prod-신고알림).
 *   주간 리포트와 다른 채널이다 — 운영이 신고만 보는 자리라 섞지 않는다. 비면 게시 skip(fail-safe)이고 신고 자체는 정상 접수된다.
 */
@ConfigurationProperties(prefix = "admin")
data class AdminProperties(
    val enabled: Boolean = false,
    val environmentGate: Boolean = false,
    val discordPublicKey: String = "",
    val discordAdminUserIds: Set<String> = emptySet(),
    val discordAdminChannelId: String = "",
    val environment: String = "",
    val grantSigningKey: String = "",
    val grantHosts: Map<String, String> = emptyMap(),
    val allowlistTtl: Duration = Duration.ofHours(24),
    val grantTokenTtl: Duration = Duration.ofMinutes(3),
    val localBypass: Boolean = false,
    val scheduleGraceWindow: Duration = Duration.ofHours(1),
    val discordBotToken: String = "",
    val discordMetricsChannelId: String = "",
    val discordReportChannelId: String = "",
    val discordReportWebhookUrl: String = "",
    val userMilestoneInterval: Long = 1000,
) {
    // 공개키·채널·호스트는 민감치 않아 노출하되 userId 는 개수만.
    // grantSigningKey·discordBotToken·discordReportWebhookUrl 은 크리덴셜이라 set 여부만 마스킹한다.
    override fun toString(): String =
        "AdminProperties(enabled=$enabled, environmentGate=$environmentGate, environment=$environment, " +
            "discordPublicKey=${if (discordPublicKey.isBlank()) "<none>" else "<set>"}, " +
            "discordAdminUserIds=${discordAdminUserIds.size} ids, discordAdminChannelId=$discordAdminChannelId, " +
            "discordMetricsChannelId=$discordMetricsChannelId, " +
            "discordBotToken=${if (discordBotToken.isBlank()) "<none>" else "<set>"}, " +
            "discordReportChannelId=$discordReportChannelId, " +
            "discordReportWebhookUrl=${if (discordReportWebhookUrl.isBlank()) "<none>" else "<set>"}, " +
            "grantSigningKey=${if (grantSigningKey.isBlank()) "<none>" else "<set>"}, " +
            "userMilestoneInterval=$userMilestoneInterval, " +
            "allowlistTtl=$allowlistTtl, grantTokenTtl=$grantTokenTtl, localBypass=$localBypass)"
}
