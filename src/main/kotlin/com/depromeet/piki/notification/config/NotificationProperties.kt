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
)
