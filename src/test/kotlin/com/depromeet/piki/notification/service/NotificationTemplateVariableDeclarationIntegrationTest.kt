package com.depromeet.piki.notification.service

import com.depromeet.piki.notification.repository.NotificationTemplateJpaRepository
import com.depromeet.piki.support.IntegrationTestSupport
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import kotlin.test.assertEquals

// 시드된 각 템플릿이 쓰는 ${변수} 가 전부 NotificationTemplateVariables 카탈로그에 선언돼 있는지 검증한다.
// 이 불변식은 AdminTemplateService.validateVariables 와 같다 — 선언 안 된 변수를 쓰는 템플릿은 어드민에서
// 저장이 400 으로 막힌다. 즉 "시드 문구가 자기 재저장 검증을 통과하는가" 를 CI 에서 강제한다.
//
// 실측 배경(#944 회귀): ITEM_PARSING_INCOMPLETE 템플릿은 title=${itemName} 이고 핸들러도 그 변수를 채우는데,
// 타입 추가 시 카탈로그 선언을 빠뜨려 어드민이 그 템플릿을 저장조차 못 했다. 그 클래스의 버그를 이 테스트가 잡는다.
@Transactional
class NotificationTemplateVariableDeclarationIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var templateRepository: NotificationTemplateJpaRepository

    @Test
    fun `시드된 모든 템플릿의 변수는 카탈로그에 선언돼 있다`() {
        // 타입별로 "쓰였지만 선언 안 된 변수" 를 모은다. 정상이면 전 타입이 빈 집합이어야 한다.
        val undeclaredByType =
            templateRepository
                .findAll()
                .associate { template ->
                    val used = NotificationTemplateVariables.usedIn(template.titleTemplate, template.bodyTemplate)
                    template.type to (used - NotificationTemplateVariables.names(template.type))
                }.filterValues { it.isNotEmpty() }

        // 실패 시 어느 타입의 어떤 변수가 미선언인지 그대로 드러난다.
        assertEquals(emptyMap(), undeclaredByType)
    }
}
