package com.depromeet.piki.notification.service

import com.depromeet.piki.notification.domain.NotificationType
import com.depromeet.piki.notification.repository.NotificationTemplateJpaRepository
import com.depromeet.piki.support.IntegrationTestSupport
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import kotlin.test.assertEquals

// 옛 NotificationChannelPolicy.pushable() 의 전수 when(else 없음)은 새 NotificationType 이 늘 때마다 push 분류를
// 컴파일로 강제했다. 그 정책을 DB 시드(notification_templates.push_enabled)로 옮기면서 이 컴파일 강제가 사라졌다 —
// 부팅 require 는 "row 누락"만 잡고 "잘못된/빠뜨린 push 분류"는 통과시킨다(새 타입이 DEFAULT TRUE 로 조용히 push-on).
// 이 테스트가 그 안전망을 회복한다:
//   (1) expectedPushEnabled 가 전 타입을 망라하는지 → 새 타입 추가 시 분류 명시를 강제(옛 when(no else) 역할).
//   (2) DB 에 시드된 push_enabled 가 그 의도와 정확히 일치하는지 → backfill 오타·시드 누락을 CI 에서 잡는다.
@Transactional
class NotificationPushPolicySeedIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var templateRepository: NotificationTemplateJpaRepository

    // 의도된 타입별 OS 푸시(FCM) 발송 여부. 새 NotificationType 을 추가하면 여기에 분류를 명시해야 한다 —
    // 안 하면 아래 '전 타입 망라' 단언이 실패해 결정을 강제한다(옛 정적 when 의 누락 방지를 대체).
    // 값의 근거: 아이템 추가/삭제는 인앱 SSE 로 충분해 OS 트레이 푸시 제외(false), 그 외는 앱이 닫혀 있어도 알려야 해 푸시(true).
    private val expectedPushEnabled: Map<NotificationType, Boolean> =
        mapOf(
            NotificationType.TOURNAMENT_JOINED to true,
            NotificationType.TOURNAMENT_ITEM_ADDED to false,
            NotificationType.TOURNAMENT_ITEM_DELETED to false,
            NotificationType.TOURNAMENT_STARTED to true,
            NotificationType.TOURNAMENT_PLAYED_FROM_LINK to true,
            NotificationType.TOURNAMENT_COMPLETED to true,
            NotificationType.TOURNAMENT_RESULT_READY to true,
            NotificationType.ITEM_PARSING_COMPLETED to true,
            // 사용자가 나머지를 채워야 등록이 끝나므로, 앱이 닫혀 있어도 알린다(완료·실패와 같은 결).
            NotificationType.ITEM_PARSING_INCOMPLETE to true,
            NotificationType.ITEM_PARSING_FAILED to true,
            // 실패로 멈춰 있던 카드가 남의 성공으로 채워진 해소 통지(#1028) — 낡은 실패 알림을 이미 받아 둔
            // 사람에게 가므로, 앱이 닫혀 있어도 알려야 어긋남이 그대로 남지 않는다.
            NotificationType.ITEM_PARSING_RECOVERED to true,
            // 새로고침 결과(#1036)는 등록 완료·실패와 같은 결 — 사용자가 직접 누른 재추출의 끝을 앱이 닫혀 있어도 알린다.
            NotificationType.ITEM_REFRESH_COMPLETED to true,
            NotificationType.ITEM_REFRESH_FAILED to true,
            NotificationType.ANNOUNCEMENT to true,
        )

    @Test
    fun `expectedPushEnabled 는 모든 NotificationType 을 망라한다`() {
        // 새 타입을 추가하고 분류를 빠뜨리면 여기서 실패한다 — 옛 전수 when 의 컴파일 강제를 대체하는 안전망.
        assertEquals(NotificationType.entries.toSet(), expectedPushEnabled.keys)
    }

    @Test
    fun `시드된 push_enabled 가 의도한 분류와 정확히 일치한다`() {
        // backfill 오타(WHERE IN 에 잘못된 타입)·시드 누락으로 어떤 타입의 푸시가 의도와 달라지면 여기서 실패한다.
        val seeded = templateRepository.findAll().associate { it.type to it.pushEnabled }
        assertEquals(expectedPushEnabled, seeded)
    }
}
