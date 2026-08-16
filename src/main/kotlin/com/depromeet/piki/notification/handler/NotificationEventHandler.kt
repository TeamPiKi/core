package com.depromeet.piki.notification.handler

import com.depromeet.piki.notification.domain.NotificationRouting
import com.depromeet.piki.notification.domain.NotificationType
import org.springframework.core.GenericTypeResolver
import java.util.UUID
import kotlin.reflect.KClass

// 도메인 이벤트(item/tournament 등이 발행하는 사실)를 알림으로 변환하는 단위.
// 도메인은 자기 이벤트만 발행하고, 알림 도메인이 이 핸들러로 구독해 수신자·변수·refId 를 결정한다.
// 결합 방향: 알림 -> 도메인 (단방향). 도메인은 알림 패키지를 import 하지 않는다.
//
// eventType(Dispatcher 라우팅 키)은 타입 인자 E 에서 자동 도출한다 — 구현체가 `::class` 로 같은 타입을
// 한 번 더 적던 중복을 없앤다. 제네릭이 (1) 메서드 시그니처의 컴파일타임 타입 안전과 (2) eventType 도출을
// 모두 담당하므로, 둘이 어긋날 수 없다(같은 한 소스에서 나온다).
abstract class NotificationEventHandler<E : Any>(
    // 템플릿 조회 키.
    val notificationType: NotificationType,
) {
    // Dispatcher 라우팅 키 — 타입 인자 E 를 도출해 채운다. (도출 로직은 resolveEventType 에 격리)
    val eventType: KClass<E> = resolveEventType()

    // 도메인 이벤트의 어느 필드가 알림의 딥링크·역조회 키(refId)인지 결정한다.
    // 도메인 이벤트는 itemId·tournamentId 등 자기 식별자만 알 뿐 refId 개념을 모른다 —
    // 알림 도메인이 자기 표현(Notification.refId)으로 끌어내는 책임을 진다.
    abstract fun resolveRefId(event: E): Long

    // 수신자 (개인=본인 / 협업=참가자 fan-out). refId 로 위시·토너먼트를 역조회해 결정한다.
    // Set 으로 둬 "수신자는 중복 없는 집합"을 타입에 박는다 — owner + 참가자를 합쳐도 같은 유저에게
    // 알림 row 가 중복 저장되지 않는다(#236 fan-out 안전).
    abstract fun resolveRecipients(event: E): Set<UUID>

    // 딥링크 라우팅 컨텍스트(#408). 파싱 알림처럼 출처(위시/토너먼트)별로 이동 화면이 갈리는 알림은 이를 override 해
    // 도메인 식별자(kind·tournamentId·tournamentItemId)를 싣는다. refId 만으로 충분한 알림(토너먼트 알림 등)은
    // 기본값 null 을 그대로 쓰고, 그러면 Notification 의 라우팅 컬럼이 비워진다(채널에서 키가 생략된다).
    open fun resolveRouting(event: E): NotificationRouting? = null

    // 수신자별 라우팅 + 추가 템플릿 변수(#933). 기본값은 전 수신자가 resolveRouting(event) 을 공유하고 추가 변수는 없다 —
    // 대부분의 알림은 한 이벤트의 전 수신자가 같은 화면으로 가므로 이대로 충분하다(기존 핸들러 불변).
    // 파싱 알림처럼 한 snapshot 에 위시 주인·토너먼트 등록자가 함께 붙어(공유 #825 의 "진행 중 합류") 수신자마다
    // 딥링크(자기 위시/자기 토너먼트)와 body 문구(출처별)가 갈리는 경우만 override 한다.
    // 배치(전 수신자 한 번)로 받는 이유: 수신자 수만큼 조회가 늘지 않게 핸들러가 한 번에 해석하게 한다(N+1 방지).
    open fun resolveRecipientContexts(
        event: E,
        recipients: Set<UUID>,
    ): Map<UUID, RecipientContext> {
        val shared = resolveRouting(event)
        return recipients.associateWith { RecipientContext(routing = shared) }
    }

    // 행위자(actor) 표시 컨텍스트 — 템플릿 변수(actorName)와 발송 시점 프사 snapshot 을 한 번에 해석한다(#473).
    // 변수와 프사를 따로 hook 으로 두면 같은 actor 에 findById 가 두 번 나가므로, 한 조회로 합쳐 돌려준다.
    // actor 가 있는 알림(TOURNAMENT_* 등 "OO님이 …")만 override 한다. actor 없는 시스템 알림(파싱·공지)은
    // 기본값(빈 컨텍스트): 변수 없음 + imageUrl null.
    // imageUrl 은 actor_image_url 컬럼에 snapshot 될 뿐 응답에는 실리지 않는다 — 알림 카드가 아바타 대신
    // kind 라벨을 쓰게 되며 payload 의 imageUrl 이 사라졌다(컬럼은 아바타 복귀 대비로 유지).
    open fun resolveActorContext(event: E): ActorContext = ActorContext()

    // 타입 인자 E 를 Spring 의 제네릭 추출 헬퍼로 풀어낸다. 서브클래스 생성 시 javaClass 는 실제 구현체라,
    // 그 슈퍼타입 NotificationEventHandler<E> 의 첫 타입 인자가 E 다. (private 라 가상 호출이 아니므로 init 안전)
    @Suppress("UNCHECKED_CAST")
    private fun resolveEventType(): KClass<E> =
        (
            GenericTypeResolver.resolveTypeArgument(javaClass, NotificationEventHandler::class.java)
                ?: error("${javaClass.simpleName} 의 이벤트 타입 인자(E)를 해석할 수 없습니다")
        ).kotlin as KClass<E>
}

// actor 알림이 actor 1명에서 함께 끌어내는 표시 컨텍스트 — 템플릿 변수(예: actorName)와 프사 snapshot(imageUrl).
// 둘을 한 hook(resolveActorContext)으로 묶어 actorId→User 조회를 한 번으로 합친다(#473).
// 기본값(빈 컨텍스트)은 actor 없는 알림(파싱·공지)이 쓴다 — 변수 없음 + imageUrl null.
data class ActorContext(
    val variables: Map<String, String> = emptyMap(),
    val imageUrl: String? = null,
)

// 수신자별 딥링크 라우팅 + 추가 템플릿 변수(#933). 파싱 알림이 수신자마다 다른 딥링크(자기 위시 wishId / 자기 토너먼트
// 좌표)와 출처별 body 문구를 갖도록, dispatcher 가 이 컨텍스트를 수신자별로 받아 알림을 렌더·저장한다.
// variables 는 actor 공유 변수(예: itemName)에 더해지는 수신자별 변수다 — 파싱은 completionMessage(출처별 완료 문구).
data class RecipientContext(
    val routing: NotificationRouting? = null,
    val variables: Map<String, String> = emptyMap(),
)
