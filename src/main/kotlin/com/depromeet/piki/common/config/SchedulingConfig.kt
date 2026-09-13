package com.depromeet.piki.common.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

// 통합 테스트가 scheduling.enabled=false 로 끈다 — 배경 tick 이 다른 테스트가 커밋한 큐 행을 선점해
// 결정적 검증이 불가능하다(#1080).
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["scheduling.enabled"], havingValue = "true", matchIfMissing = true)
@EnableScheduling
class SchedulingConfig
