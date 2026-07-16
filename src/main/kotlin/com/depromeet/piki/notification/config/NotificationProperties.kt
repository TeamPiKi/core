package com.depromeet.piki.notification.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 알림 도메인 설정. `@ConfigurationPropertiesScan`(PikiApplication)으로 자동 등록.
 *
 * @property retentionDays 알림 보존 기간(일). 생성 후 이 기간을 넘긴 알림은 N일 자동삭제 스케줄러가 하드삭제한다. 하드코딩 금지 — 여기서 협의값을 둔다.
 */
@ConfigurationProperties(prefix = "notification")
data class NotificationProperties(
    val retentionDays: Long = 30,
) {
    init {
        // 불변식 — 잘못 구성한 env(0·음수)면 cutoff(now-retentionDays)가 현재/미래가 되어 자동삭제가 알림을 대량 삭제한다.
        // ops 오설정이라 클라가 닿는 계약이 아니므로 커스텀 예외가 아니라 require 로 부팅 시점에 실패시킨다.
        require(retentionDays > 0) { "notification.retention-days 는 양수여야 한다 (현재=$retentionDays)" }
    }
}
