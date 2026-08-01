package com.depromeet.piki.notification.domain

import com.depromeet.piki.common.domain.LongBaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.util.UUID

// 알림 내역. title/body 는 발송 시점에 템플릿 변수 치환이 끝난 완성본을 저장한다
// (템플릿 문구가 나중에 바뀌어도 과거 알림 표시는 발송 당시 그대로 유지된다).
// 채널(SSE/FCM)은 이 엔티티를 그대로 받아 전달하고, 읽음/badge 조회(#246)도 이 테이블을 본다.
@Entity
@Table(name = "notifications")
class Notification(
    @Column(name = "user_id", nullable = false, columnDefinition = "BINARY(16)")
    val userId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 50)
    val type: NotificationType,
    @Column(name = "title", nullable = false, length = MAX_TEXT_LENGTH)
    val title: String,
    @Column(name = "body", nullable = false, length = MAX_TEXT_LENGTH)
    val body: String,
    @Column(name = "ref_id", nullable = false)
    val refId: Long,
    // 파싱 알림 딥링크 라우팅 컨텍스트(#408) — 아래 kind·tournamentId·tournamentItemId 컬럼으로 평탄화해 저장한다.
    // 라우팅이 없는 알림(토너먼트 알림 등)은 null 을 넘겨 세 컬럼이 모두 비워진다. 생성자 끝의 default 라 기존 호출은 안 깨진다.
    routing: NotificationRouting? = null,
    // 행위자(actor) 프로필 사진 snapshot(#473) — 발송 시점 actor 의 프사 URL 을 박아 고정한다. 이후 actor 가 프사를
    // 바꿔도 과거 알림은 그 시점 사진 유지(title 의 닉네임 snapshot 과 같은 결). actor 없는 시스템 알림은 null —
    // 직렬화 시 서버가 defaultPushImg 로 채운다(컬럼엔 저장 안 함). routing 뒤에 둬 기존 positional 호출(…,refId,routing)을 안 깬다.
    @Column(name = "actor_image_url", length = MAX_IMAGE_URL_LENGTH)
    val actorImageUrl: String? = null,
) : LongBaseEntity() {
    // 엔티티 불변식 — 최후의 보루. title/body 는 발송 시점에 렌더된 완성본이라, 정상 흐름에선
    // 입력 경계(닉네임 등 변수 길이 제한)가 먼저 걸러 VARCHAR(255) 안에 든다. 그래도 엔티티가
    // 스스로 길이를 보장해, 누가 어떤 경로로 만들든 DB 저장 시점이 아니라 생성 시점에 깨지게 한다.
    // (message 에 원문을 담지 않는다 — 응답·로그로 새지 않게 길이 사실만.)
    // kotlin-jpa(noarg) 가 합성하는 JPA 생성자는 이 init 을 우회하므로 DB 로딩 행은 검증 대상이 아니다.
    init {
        require(title.length <= MAX_TEXT_LENGTH) { "알림 title 길이가 ${MAX_TEXT_LENGTH}자를 초과했습니다." }
        require(body.length <= MAX_TEXT_LENGTH) { "알림 body 길이가 ${MAX_TEXT_LENGTH}자를 초과했습니다." }
    }

    // routing 을 평탄화한 라우팅 컬럼 — 채널(SSE/FCM)이 엔티티만 받아 클라에 식별자를 내려보내고,
    // 목록/badge 조회(#246)도 과거 알림의 딥링크를 이 컬럼들로 복원한다. WISH 는 식별자가 없어 tournament_* 가 null 이다.
    // (noarg JPA 생성자는 이 초기화를 우회하고 DB 값을 필드에 직접 주입한다.)
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", length = 20)
    val kind: NotificationKind? = routing?.kind

    @Column(name = "tournament_id")
    val tournamentId: Long? = (routing as? NotificationRouting.Tournament)?.tournamentId

    @Column(name = "tournament_item_id")
    val tournamentItemId: Long? = (routing as? NotificationRouting.Tournament)?.tournamentItemId

    @Column(name = "is_read", nullable = false)
    var isRead: Boolean = false
        protected set

    // 읽음 처리. 멱등 — 이미 읽음이어도 재호출 무해. (읽음 API 는 #246)
    fun markRead() {
        isRead = true
    }

    // 평면 저장된 라우팅 컬럼을 도메인 sealed 로 복원한다. 채널(SSE payload·FCM data)이 이걸로 셰입을 가른다 —
    // 평면 nullable 컬럼을 직접 들추는 대신 sealed 한 곳에서 "WISH 엔 식별자 없음, TOURNAMENT 만 둘 다" 를 보장한다.
    fun routing(): NotificationRouting? =
        kind?.let { k ->
            when (k) {
                NotificationKind.WISH -> NotificationRouting.Wish
                // kind=TOURNAMENT 면 두 식별자가 반드시 있다(엔티티 생성 시 sealed 에서 원자적으로 채워짐). 없으면 데이터 정합성
                // 위반이라 불변식(checkNotNull → IllegalStateException → 500)으로 다룬다 — 클라 입력 오류(400)가 아니다.
                NotificationKind.TOURNAMENT ->
                    NotificationRouting.Tournament(
                        checkNotNull(tournamentId) { "TOURNAMENT 알림에 tournamentId 가 없다" },
                        checkNotNull(tournamentItemId) { "TOURNAMENT 알림에 tournamentItemId 가 없다" },
                    )
                // 이 kind 컬럼은 routing?.kind 에서만 채워지고 NotificationRouting 은 Wish/Tournament 두 변형뿐이라
                // SYSTEM 은 여기 도달할 수 없다(#473 고도화로 NotificationKind 에 SYSTEM 이 추가되며 when 이 비전수가 돼
                // 강제로 다뤄야 하는 분기). 도달하면 불변식 위반이라 500.
                NotificationKind.SYSTEM -> error("Notification.kind 는 routing 파생값이라 SYSTEM 이 될 수 없다")
            }
        }

    companion object {
        // VARCHAR(255) 컬럼과 엔티티 불변식이 같은 상수를 공유한다.
        const val MAX_TEXT_LENGTH = 255

        // actor_image_url 컬럼 길이 — users.profile_image(2048)와 정합. 발송 시점 그 값을 그대로 snapshot 하므로 같은 상한.
        const val MAX_IMAGE_URL_LENGTH = 2048
    }
}
