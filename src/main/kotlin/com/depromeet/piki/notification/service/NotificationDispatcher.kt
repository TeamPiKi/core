package com.depromeet.piki.notification.service

import com.depromeet.piki.notification.domain.Notification
import com.depromeet.piki.notification.handler.NotificationEventHandler
import com.depromeet.piki.notification.handler.RecipientContext
import com.depromeet.piki.notification.service.dto.NotificationReadCommand
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import kotlin.reflect.KClass

// 도메인 이벤트를 핸들러로 라우팅하고, 수신자별로 알림을 저장한 뒤 모든 채널로 전달한다.
// 도메인 이벤트의 종류를 모른다 — when 분기 없이 handlers 맵으로만 라우팅하므로, 새 이벤트가 늘어도 이 클래스는 불변이다.
@Component
class NotificationDispatcher(
    handlers: List<NotificationEventHandler<*>>,
    private val templateProvider: NotificationTemplateProvider,
    private val renderer: NotificationTemplateRenderer,
    private val persistence: NotificationPersistenceService,
    private val channels: List<NotificationChannel>,
    // 전달 성공 후 읽음 전환 + badge 재동기화(#812). 읽음 API(#246)가 쓰는 그 경로를 그대로 재사용한다 —
    // 읽음 UPDATE·SSE UNREAD_COUNT_CHANGED·FCM silent badge 가 한 자리에 묶여 있어 여기서 따로 조립할 게 없다.
    private val readOrchestrator: NotificationReadOrchestrator,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 한 이벤트에 핸들러가 **여럿**일 수 있다(#1028). 파싱 완료라는 한 사실에서 "이 파싱을 기다린 사람" 알림과
    // "다른 버전에 멈춰 있다 남의 성공으로 해소된 사람" 알림이 갈리는데, 둘은 수신자가 배타적이라 각자 자기 타입으로
    // 나가야 한다. 옛 associateBy 는 충돌 시 마지막 핸들러로 조용히 덮어써서 부팅 require 로 중복을 막고 있었는데,
    // groupBy 로 전부 돌리면 그 소실 자체가 없어져 가드가 필요 없다.
    private val byType: Map<KClass<*>, List<NotificationEventHandler<*>>> =
        handlers.groupBy { it.eventType }

    fun dispatch(event: Any) {
        val matched =
            byType[event::class]
                ?: error("핸들러 미등록: ${event::class.simpleName}")
        matched.forEach { handler ->
            // 한 핸들러의 실패(수신자 역조회 예외 등)가 같은 이벤트의 다른 핸들러를 막지 않게 핸들러 단위로 격리한다 —
            // 수신자 단위 격리(아래)와 같은 결이다. 격리가 없으면 완료 알림의 조회 실패가 해소 통지까지 함께 삼킨다.
            // 타입 캐스팅은 여기 한 곳에 격리한다 — byType 매칭이 eventType 과 event::class 의 일치를 보장하므로 안전.
            @Suppress("UNCHECKED_CAST")
            runCatching { dispatchTo(handler as NotificationEventHandler<Any>, event) }
                .onFailure { e ->
                    log.warn("핸들러 디스패치 실패 type={} event={}", handler.notificationType, event::class.simpleName, e)
                }
        }
    }

    // 핸들러 하나가 자기 수신자에게 fan-out 한다.
    private fun dispatchTo(
        handler: NotificationEventHandler<Any>,
        event: Any,
    ) {
        val recipients = handler.resolveRecipients(event)
        // 이벤트 수신 → 수신자 도출(인원). 디스패치는 async 워커라 MDC userId 는 이벤트를 유발한 actor 의 것이고,
        // 수신자는 actor 와 다른 유저들이라 수신자 userId 는 아래 fan-out 에서 명시적으로 남긴다.
        log.info("알림 디스패치 type={} event={} 수신자={}명", handler.notificationType, event::class.simpleName, recipients.size)
        if (recipients.isEmpty()) return

        val refId = handler.resolveRefId(event)
        // actor 는 한 이벤트에 한 명이라 수신자 루프 밖에서 한 번만 해석해(actorId 조회 1회) 모든 수신자에 공유한다(#473).
        // 변수(actorName·itemName)와 프사 snapshot 을 한 컨텍스트로 함께 받는다.
        val actorContext = handler.resolveActorContext(event)
        val actorImageUrl = actorContext.imageUrl
        val template = templateProvider.find(handler.notificationType)
        val sharedVariables = actorContext.variables
        // 수신자별 라우팅·추가 변수(#933) — 배치 1회로 해석한다. 파싱 알림은 한 snapshot 에 위시 주인·토너먼트 등록자가
        // 함께 붙어 수신자마다 딥링크(자기 위시/자기 토너먼트)와 body 문구(출처별)가 갈리므로, 라우팅·문구 렌더를
        // 수신자 루프 **안**에서 한다(기존엔 루프 밖 1회 공유라 위시 주인이 남의 토너먼트 딥링크·문구를 받았다).
        // 나머지 알림은 default 로 전원 동일 라우팅·변수를 받아 동작이 그대로다.
        val recipientContexts = handler.resolveRecipientContexts(event, recipients)

        var delivered = 0
        recipients.forEach { userId ->
            // 한 수신자의 저장 실패가 나머지 수신자 fan-out 을 막지 않게 수신자 단위로 격리한다 (외부 전달은 트랜잭션 밖).
            runCatching {
                val context = recipientContexts[userId] ?: RecipientContext()
                // 공유 변수(itemName 등) + 수신자별 변수(completionMessage 등)를 합쳐 이 수신자용 title/body 를 렌더한다.
                val variables = sharedVariables + context.variables
                val title = renderer.render(template.title, variables)
                val body = renderer.render(template.body, variables)
                // 항상 안읽음으로 저장한다. 읽음 전환은 실제 전달에 성공한 뒤에만 일어난다(아래).
                val saved =
                    persistence.save(
                        Notification(
                            userId = userId,
                            type = handler.notificationType,
                            title = title,
                            body = body,
                            refId = refId,
                            routing = context.routing,
                            actorImageUrl = actorImageUrl,
                        ),
                    )
                // 한 채널의 실패도 다른 채널 전달을 막지 않게 추가로 격리한다.
                // 채널 선택(예: FCM 은 push 대상 타입만)은 각 채널이 send 안에서 자기-적용 판단한다.
                // 반환값은 "실시간 인앱 전달 건수" 다 — SSE 만 0 이 아닐 수 있고 FCM 은 항상 0 이다.
                val liveDeliveries =
                    channels.sumOf { channel ->
                        runCatching { channel.send(userId, saved) }
                            .onFailure { e -> log.warn("채널 {} 전송 실패 userId={}", channel::class.simpleName, userId, e) }
                            .getOrDefault(0)
                    }
                // 인앱(SSE)으로 실제 전달됐으면 사용자가 화면·토스트로 곧바로 인지하므로 읽음으로 전환해
                // 히스토리에 unread 로 쌓이지 않게 한다(#812). 타입은 가리지 않는다 — 인앱이면 어떤 알림이든 화면에 반영된다.
                //
                // **판정 근거는 "연결이 있었나" 가 아니라 "실제로 써 넣었나" 다.** 하트비트가 30초 주기라 끊긴 소켓이
                // 그동안 레지스트리에 살아 있고(half-open), 전달 시도 시점에야 write 실패로 드러난다. 연결 유무로 판정하면
                // 그 창에서 "전달 못 했는데 읽음" 이 되어 사용자가 알림을 영영 못 본다 — 특히 push_enabled=false 인 타입
                // (아이템 추가/삭제)은 FCM 보조가 없어 SSE·안읽음이 유일한 전달 수단이라 그대로 소실된다.
                //
                // 스케일아웃(#439)에서 다른 인스턴스에 붙은 연결은 여기서 0 으로 보이지만 그 방향은 안전하다
                // (안읽음으로 남아 사용자가 결국 본다).
                //
                // readAndSyncBadge 로 뒤집는 이유: 읽음 UPDATE(짧은 트랜잭션) 뿐 아니라 badge 재동기화까지 함께 한다.
                // 위 FCM 푸시는 읽음 전 카운트로 배지를 실어 보냈으므로, 그대로 두면 OS 배지만 1 크게 남는다.
                if (liveDeliveries > 0) {
                    runCatching { readOrchestrator.readAndSyncBadge(userId, NotificationReadCommand.Ids(listOf(saved.getId()))) }
                        .onFailure { e -> log.warn("인앱 전달 후 자동읽음 실패 userId={} notificationId={}", userId, saved.getId(), e) }
                }
            }.onFailure { e -> log.warn("알림 저장 실패로 수신자 건너뜀 userId={}", userId, e) }
                .onSuccess { delivered++ }
        }
        // fan-out 결과 요약 — 저장 실패로 누락된 수신자가 있으면 (수신자 인원 > 저장성공)으로 드러난다.
        log.info("알림 fan-out 완료 type={} 수신자={}명 저장성공={}건", handler.notificationType, recipients.size, delivered)
    }
}
