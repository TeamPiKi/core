package com.depromeet.piki.notification.handler

import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.event.ItemParsingCompleted
import com.depromeet.piki.item.event.ItemParsingFailed
import com.depromeet.piki.item.repository.ItemSnapshotRepository
import com.depromeet.piki.notification.domain.NotificationRouting
import com.depromeet.piki.notification.domain.NotificationType
import com.depromeet.piki.notification.repository.NotificationRepository
import com.depromeet.piki.notification.service.NotificationDispatcher
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.tournament.domain.Tournament
import com.depromeet.piki.tournament.domain.TournamentItem
import com.depromeet.piki.tournament.domain.TournamentUser
import com.depromeet.piki.tournament.event.TournamentCompleted
import com.depromeet.piki.tournament.event.TournamentItemAdded
import com.depromeet.piki.tournament.event.TournamentItemDeleted
import com.depromeet.piki.tournament.event.TournamentJoined
import com.depromeet.piki.tournament.event.TournamentPlayedFromLink
import com.depromeet.piki.tournament.event.TournamentResultReady
import com.depromeet.piki.tournament.event.TournamentStarted
import com.depromeet.piki.tournament.repository.TournamentItemRepository
import com.depromeet.piki.tournament.repository.TournamentRepository
import com.depromeet.piki.tournament.repository.TournamentUserRepository
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.user.domain.User
import com.depromeet.piki.user.repository.UserRepository
import com.depromeet.piki.wishlist.domain.Wish
import com.depromeet.piki.wishlist.repository.WishRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// 핸들러의 수신자(resolveRecipients)·actor 컨텍스트(resolveActorContext) 도출은 DB 역조회에 의존하므로 통합으로 검증한다.
// 영속 fixture(참가자·위시·토너먼트 아이템·유저)를 깔고 실제 빈으로 도출 결과를 단언한다. @Transactional 자동 롤백.
@Transactional
class NotificationRecipientResolutionIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var itemAddedHandler: TournamentItemAddedHandler

    @Autowired private lateinit var itemDeletedHandler: TournamentItemDeletedHandler

    @Autowired private lateinit var joinedHandler: TournamentJoinedHandler

    @Autowired private lateinit var startedHandler: TournamentStartedHandler

    @Autowired private lateinit var playedFromLinkHandler: TournamentPlayedFromLinkHandler

    @Autowired private lateinit var completedHandler: TournamentCompletedHandler

    @Autowired private lateinit var resultReadyHandler: TournamentResultReadyHandler

    @Autowired private lateinit var tournamentRepository: TournamentRepository

    @Autowired private lateinit var parsingCompletedHandler: ItemParsingCompletedHandler

    @Autowired private lateinit var parsingFailedHandler: ItemParsingFailedHandler

    @Autowired private lateinit var tournamentUserRepository: TournamentUserRepository

    @Autowired private lateinit var tournamentItemRepository: TournamentItemRepository

    @Autowired private lateinit var wishRepository: WishRepository

    @Autowired private lateinit var itemSnapshotRepository: ItemSnapshotRepository

    @Autowired private lateinit var userRepository: UserRepository

    @Autowired private lateinit var notificationDispatcher: NotificationDispatcher

    @Autowired private lateinit var notificationRepository: NotificationRepository

    @Test
    fun `토너먼트 아이템 추가 수신자는 참가자에서 추가한 본인을 뺀 집합이다`() {
        val tournamentId = 1001L
        val actor = UUID.randomUUID()
        val other1 = UUID.randomUUID()
        val other2 = UUID.randomUUID()
        listOf(actor, other1, other2).forEach { tournamentUserRepository.save(TournamentUser(tournamentId, it)) }

        val recipients = itemAddedHandler.resolveRecipients(TournamentItemAdded(tournamentId, actor))

        assertEquals(setOf(other1, other2), recipients)
    }

    @Test
    fun `토너먼트 아이템 삭제 수신자는 참가자에서 삭제한 본인을 뺀 집합이다`() {
        val tournamentId = 1201L
        val actor = UUID.randomUUID()
        val other1 = UUID.randomUUID()
        val other2 = UUID.randomUUID()
        // 삭제한 본인(등록자 또는 주최자)은 자기 화면이 이미 알고 있어 제외 — 추가 알림과 동일 규칙.
        listOf(actor, other1, other2).forEach { tournamentUserRepository.save(TournamentUser(tournamentId, it)) }

        val recipients = itemDeletedHandler.resolveRecipients(TournamentItemDeleted(tournamentId, tournamentItemId = 1L, snapshotId = 1L, actorId = actor))

        assertEquals(setOf(other1, other2), recipients)
    }

    @Test
    fun `토너먼트 아이템 삭제 라우팅은 그 토너먼트와 빠진 아이템 좌표다`() {
        val routing =
            itemDeletedHandler.resolveRouting(
                TournamentItemDeleted(tournamentId = 1204L, tournamentItemId = 5555L, snapshotId = 1L, actorId = UUID.randomUUID()),
            )

        assertEquals(NotificationRouting.Tournament(tournamentId = 1204L, tournamentItemId = 5555L), routing)
    }

    @Test
    fun `토너먼트 아이템 삭제 변수는 actorName 과 snapshot 에서 끌어온 itemName 을 함께 담는다`() {
        val tournamentId = 1202L
        val actor = UUID.randomUUID()
        userRepository.save(User(id = actor, nickname = "홍길동", profileImage = "https://x/p.jpg", identityType = IdentityType.GUEST))
        val snapshotId = itemSnapshotRepository.save(ItemSnapshot(itemId = 7202L, name = "나이키 에어맥스")).getId()

        val variables =
            itemDeletedHandler.resolveActorContext(
                TournamentItemDeleted(tournamentId, tournamentItemId = 1L, snapshotId = snapshotId, actorId = actor),
            ).variables

        assertEquals(
            mapOf("actorName" to "홍길동", "tournamentId" to "1202", "tournamentName" to "토너먼트", "itemName" to "나이키 에어맥스"),
            variables,
        )
    }

    // 삭제 알림은 이름 앞뒤로 닉네임과 "…을(를) 삭제했어요" 가 붙어, 이름을 안 자르면 엔티티 불변식(title 255자)에
    // 걸려 dispatcher 의 runCatching 이 예외를 삼키고 알림이 조용히 누락된다(상품명은 512자까지 허용).
    // 절단 규칙 자체는 ItemDisplayNameTest 가 망라하고, 여기선 핸들러가 그 규칙에 이름을 실제로 통과시키는지만 본다.
    @Test
    fun `토너먼트 아이템 삭제 변수의 itemName 은 표시 길이로 잘린다`() {
        val actor = UUID.randomUUID()
        userRepository.save(User(id = actor, nickname = "홍길동", profileImage = "https://x/p.jpg", identityType = IdentityType.GUEST))
        val longName = "가".repeat(30)
        val snapshotId = itemSnapshotRepository.save(ItemSnapshot(itemId = 7205L, name = longName)).getId()

        val variables =
            itemDeletedHandler.resolveActorContext(
                TournamentItemDeleted(tournamentId = 1205L, tournamentItemId = 1L, snapshotId = snapshotId, actorId = actor),
            ).variables

        // "짧아졌나" 가 아니라 정확한 결과를 못 박는다 — 길이만 보면 핸들러가 캡을 10 대신 20 으로 바꿔도 통과하고,
        // ItemDisplayNameTest 는 캡을 인자로 받아 검증하므로 핸들러가 고른 캡 값은 여기서만 고정된다.
        assertEquals("가".repeat(10) + "…", variables.getValue("itemName"))
    }

    // 이모지는 UTF-16 코드 유닛으로 자르면 surrogate pair 가 쪼개져 깨진다. 핸들러가 grapheme 기준 절단을 거치는지 본다.
    // 입력을 단순 이모지(1 grapheme = 2 char)로 둬 절단 결과가 char 안전망(ItemDisplayName.MAX_CHARS) 아래에 남게 한다 —
    // 그 안전망은 조합 부호를 쌓은 비정상 입력에 한해 글자 깨짐보다 알림 누락 방지를 택하는 의도된 트레이드오프라,
    // 여기서 검증할 대상은 그 앞단의 grapheme 절단이다.
    @Test
    fun `토너먼트 아이템 삭제 변수의 itemName 은 이모지를 쪼개지 않는다`() {
        val actor = UUID.randomUUID()
        userRepository.save(User(id = actor, nickname = "홍길동", profileImage = "https://x/p.jpg", identityType = IdentityType.GUEST))
        // 앞에 1 char 를 둬 절단 경계를 홀수로 민다 — 이모지(2 char)만 있으면 코드 유닛으로 잘라도 짝이 맞아떨어져
        // 회귀가 드러나지 않는다. 이 한 글자가 경계를 surrogate pair 한가운데로 옮긴다.
        val snapshotId = itemSnapshotRepository.save(ItemSnapshot(itemId = 7206L, name = "가" + "😀".repeat(20))).getId()

        val variables =
            itemDeletedHandler.resolveActorContext(
                TournamentItemDeleted(tournamentId = 1206L, tournamentItemId = 1L, snapshotId = snapshotId, actorId = actor),
            ).variables

        val itemName = variables.getValue("itemName")
        // 기대값을 통째로 못 박는다 — 코드 유닛으로 잘랐다면 10번째 경계가 surrogate pair 한가운데라 다른 문자열이 나온다.
        // "짝 잃은 surrogate 없음" 만 보면 아예 안 자르는 구현도 통과한다(원본도 짝이 맞아서다).
        assertEquals("가" + "😀".repeat(9) + "…", itemName)
        // 위 단언이 깨졌을 때 원인이 절단 경계임을 바로 읽히게 남긴다.
        val orphanSurrogate =
            itemName.withIndex().any { (i, c) ->
                (c.isHighSurrogate() && (i + 1 >= itemName.length || !itemName[i + 1].isLowSurrogate())) ||
                    (c.isLowSurrogate() && (i == 0 || !itemName[i - 1].isHighSurrogate()))
            }
        assertFalse(orphanSurrogate, "잘린 이름에 짝 잃은 surrogate 가 남았다 (실제=$itemName)")
    }

    @Test
    fun `토너먼트 아이템 삭제 변수 itemName 은 상품명이 아직 없으면 fallback 이다`() {
        // 파싱 전(PROCESSING) 아이템을 지운 경우 — snapshot.name 이 null 이라 fallback 문구로 채운다.
        val snapshotId = itemSnapshotRepository.save(ItemSnapshot.pending(7203L).apply { markProcessing() }).getId()

        val variables =
            itemDeletedHandler.resolveActorContext(
                TournamentItemDeleted(tournamentId = 1203L, tournamentItemId = 1L, snapshotId = snapshotId, actorId = UUID.randomUUID()),
            ).variables

        assertEquals("상품", variables["itemName"])
    }

    @Test
    fun `토너먼트 참여 수신자는 기존 참가자에서 새로 들어온 본인을 뺀 집합이다`() {
        val tournamentId = 1002L
        val joiner = UUID.randomUUID()
        val existing1 = UUID.randomUUID()
        val existing2 = UUID.randomUUID()
        // AFTER_COMMIT 라 참여자 본인도 이미 목록에 있다고 보고, 본인을 제외하는지 본다.
        listOf(joiner, existing1, existing2).forEach { tournamentUserRepository.save(TournamentUser(tournamentId, it)) }

        val recipients = joinedHandler.resolveRecipients(TournamentJoined(tournamentId, joiner))

        assertEquals(setOf(existing1, existing2), recipients)
    }

    @Test
    fun `토너먼트 시작 수신자는 참가자에서 시작시킨 주최자(actor)를 뺀 집합이다`() {
        val tournamentId = 1007L
        val owner = UUID.randomUUID()
        val participant1 = UUID.randomUUID()
        val participant2 = UUID.randomUUID()
        // 주최자는 본인 화면이 이미 시작 상태로 넘어가므로, 나머지 참가자에게만 시작을 알린다.
        listOf(owner, participant1, participant2).forEach { tournamentUserRepository.save(TournamentUser(tournamentId, it)) }

        val recipients = startedHandler.resolveRecipients(TournamentStarted(tournamentId, owner))

        assertEquals(setOf(participant1, participant2), recipients)
    }

    @Test
    fun `토너먼트 시작 변수 actorName 은 시작시킨 주최자 닉네임이다`() {
        val tournamentId = 1008L
        val owner = UUID.randomUUID()
        userRepository.save(User(id = owner, nickname = "주최자", profileImage = "https://x/p.jpg", identityType = IdentityType.GUEST))

        val variables = startedHandler.resolveActorContext(TournamentStarted(tournamentId, owner)).variables

        // 변수 계약: actorName + tournamentId + tournamentName(토너먼트 미생성이라 fallback). 카탈로그·미리보기와 일치해야 한다.
        assertEquals(mapOf("actorName" to "주최자", "tournamentId" to "1008", "tournamentName" to "토너먼트"), variables)
    }

    @Test
    fun `토너먼트 아이템 추가 변수 actorName 은 행위자 닉네임이다`() {
        val tournamentId = 1003L
        val actor = UUID.randomUUID()
        userRepository.save(User(id = actor, nickname = "홍길동", profileImage = "https://x/p.jpg", identityType = IdentityType.GUEST))

        val variables = itemAddedHandler.resolveActorContext(TournamentItemAdded(tournamentId, actor)).variables

        assertEquals(mapOf("actorName" to "홍길동", "tournamentId" to "1003", "tournamentName" to "토너먼트"), variables)
    }

    @Test
    fun `행위자 유저를 못 찾으면 actorName 은 fallback 으로 채운다`() {
        val variables = itemAddedHandler.resolveActorContext(TournamentItemAdded(1004L, UUID.randomUUID())).variables

        assertEquals(mapOf("actorName" to ActorNameResolver.UNKNOWN_ACTOR, "tournamentId" to "1004", "tournamentName" to "토너먼트"), variables)
    }

    @Test
    fun `actor 알림은 발송 시점 행위자 프로필 이미지를 snapshot 한다`() {
        val actor = UUID.randomUUID()
        userRepository.save(User(id = actor, nickname = "홍길동", profileImage = "https://x/actor-now.png", identityType = IdentityType.GUEST))

        // 핸들러가 actorId 로 현재 프사 URL 을 뽑아 온다(이 값이 dispatcher 를 통해 Notification.actorImageUrl 로 박힌다).
        assertEquals("https://x/actor-now.png", joinedHandler.resolveActorContext(TournamentJoined(1009L, actor)).imageUrl)
        assertEquals("https://x/actor-now.png", itemAddedHandler.resolveActorContext(TournamentItemAdded(1009L, actor)).imageUrl)
        assertEquals("https://x/actor-now.png", startedHandler.resolveActorContext(TournamentStarted(1009L, actor)).imageUrl)
    }

    @Test
    fun `행위자 유저를 못 찾으면 actorImageUrl 은 null 이다`() {
        assertEquals(null, joinedHandler.resolveActorContext(TournamentJoined(1010L, UUID.randomUUID())).imageUrl)
    }

    @Test
    fun `시스템 알림(파싱)은 actor 가 없어 actorImageUrl 이 null 이다 - negative control`() {
        // 파싱 핸들러는 resolveActorContext 를 override 하지 않는다 → 기본 빈 컨텍스트라 imageUrl 이 null 이다.
        assertEquals(null, parsingCompletedHandler.resolveActorContext(ItemParsingCompleted(2099L, 20990L)).imageUrl)
        assertEquals(null, parsingFailedHandler.resolveActorContext(ItemParsingFailed(2099L, 20990L)).imageUrl)
    }

    @Test
    fun `dispatch 가 actor 이벤트의 프사와 닉네임을 한 컨텍스트에서 수신자 알림에 박는다 - end-to-end`() {
        val tournamentId = 1011L
        val actor = UUID.randomUUID()
        val recipient = UUID.randomUUID()
        listOf(actor, recipient).forEach { tournamentUserRepository.save(TournamentUser(tournamentId, it)) }
        userRepository.save(User(id = actor, nickname = "행위자", profileImage = "https://x/snap.png", identityType = IdentityType.GUEST))

        // 이벤트 발행 → dispatch → 핸들러 resolveActorContext(한 번의 actor 조회) → 변수(actorName) 렌더 + 프사 snapshot 까지 실제 체인을 탄다.
        notificationDispatcher.dispatch(TournamentItemAdded(tournamentId, actor))

        val saved = notificationRepository.findPage(recipient, cursor = null, limit = 10)
        assertEquals(1, saved.size)
        assertEquals("https://x/snap.png", saved.first().actorImageUrl) // 발송 시점 actor 프사가 그대로 박혔다
        // 같은 컨텍스트의 변수(actorName)가 템플릿에 렌더돼 제목에 박힌다 — 변수·프사가 한 조회에서 함께 흐르는지 end-to-end 로 가드한다.
        assertEquals("행위자님이 아이템을 추가했어요", saved.first().title)
    }

    @Test
    fun `dispatch 가 아이템 삭제 알림을 상품명까지 렌더하고 아이템 좌표를 라우팅에 박는다 - end-to-end`() {
        val tournamentId = 1205L
        val tournamentItemId = 6001L
        val actor = UUID.randomUUID()
        val recipient = UUID.randomUUID()
        listOf(actor, recipient).forEach { tournamentUserRepository.save(TournamentUser(tournamentId, it)) }
        userRepository.save(User(id = actor, nickname = "행위자", profileImage = "https://x/snap.png", identityType = IdentityType.GUEST))
        val snapshotId = itemSnapshotRepository.save(ItemSnapshot(itemId = 7205L, name = "나이키 에어맥스")).getId()

        // 이벤트 발행 → dispatch → 삭제 핸들러(snapshot 으로 상품명 해석) → 삭제 템플릿 렌더 + 라우팅까지 실제 체인을 탄다.
        notificationDispatcher.dispatch(
            TournamentItemDeleted(tournamentId, tournamentItemId = tournamentItemId, snapshotId = snapshotId, actorId = actor),
        )

        val saved = notificationRepository.findPage(recipient, cursor = null, limit = 10)
        assertEquals(1, saved.size)
        assertEquals(NotificationType.TOURNAMENT_ITEM_DELETED, saved.first().type)
        assertEquals(tournamentId, saved.first().refId)
        // 상품명이 템플릿에 렌더돼 제목에 박힌다.
        assertEquals("행위자님이 '나이키 에어맥스'을(를) 삭제했어요", saved.first().title)
        assertEquals("https://x/snap.png", saved.first().actorImageUrl)
        // 어느 토너먼트의 어느 아이템이 빠졌는지 좌표가 payload 로 흐를 라우팅에 박힌다(클라가 그 항목만 제거).
        assertEquals(NotificationRouting.Tournament(tournamentId, tournamentItemId), saved.first().routing())
    }

    @Test
    fun `파싱 완료 수신자 - 그 버전을 활성으로 가리키는 위시 주인들이다 (다른 버전을 보는 위시는 제외)`() {
        // 라우팅 키는 버전(#576) — 공유(#825)로 한 버전을 여러 위시가 가리키면 전원이 받고,
        // 같은 item 이라도 다른 버전(갱신 전 등)을 보는 위시는 이 파싱을 기다린 적이 없어 받지 않는다.
        val itemId = 2001L
        val sharedVersion = snapshotIdFor(itemId)
        val otherVersion = snapshotIdFor(itemId)
        val owner1 = UUID.randomUUID()
        val owner2 = UUID.randomUUID()
        val otherVersionOwner = UUID.randomUUID()
        wishRepository.save(Wish(owner1, sharedVersion))
        wishRepository.save(Wish(owner2, sharedVersion))
        wishRepository.save(Wish(otherVersionOwner, otherVersion))

        val recipients = parsingCompletedHandler.resolveRecipients(ItemParsingCompleted(itemId, sharedVersion))

        assertEquals(setOf(owner1, owner2), recipients)
    }

    @Test
    fun `파싱 완료 수신자 - 토너먼트로 담긴 아이템은 올린 본인(adder)에게만 가고 다른 참가자는 제외된다`() {
        val itemId = 2002L
        val tournamentId = 1005L
        val adder = UUID.randomUUID()
        val otherParticipant = UUID.randomUUID()
        // otherParticipant 는 추가 시점에 TOURNAMENT_ITEM_ADDED 로 갱신하므로 파싱완료는 안 받는다 — 참가자로 깔아두고 제외를 확인.
        listOf(adder, otherParticipant).forEach { tournamentUserRepository.save(TournamentUser(tournamentId, it)) }
        val snapshotId = snapshotIdFor(itemId)
        tournamentItemRepository.saveAll(listOf(TournamentItem(tournamentId, adder, snapshotId)))

        val recipients = parsingCompletedHandler.resolveRecipients(ItemParsingCompleted(itemId, snapshotId))

        assertEquals(setOf(adder), recipients)
    }

    @Test
    fun `파싱 완료 수신자 - 위시 주인과 토너먼트 adder 의 합집합이다 (그냥 참가자는 제외)`() {
        val itemId = 2003L
        val tournamentId = 1006L
        val wishOwner = UUID.randomUUID()
        val adder = UUID.randomUUID()
        val otherParticipant = UUID.randomUUID()
        // 공유(#825)의 세계 — 위시와 토너먼트 출전이 같은 버전을 가리킨다.
        val snapshotId = snapshotIdFor(itemId)
        wishRepository.save(Wish(wishOwner, snapshotId))
        listOf(adder, otherParticipant).forEach { tournamentUserRepository.save(TournamentUser(tournamentId, it)) }
        tournamentItemRepository.saveAll(listOf(TournamentItem(tournamentId, adder, snapshotId)))

        val recipients = parsingCompletedHandler.resolveRecipients(ItemParsingCompleted(itemId, snapshotId))

        assertEquals(setOf(wishOwner, adder), recipients)
    }

    @Test
    fun `파싱 완료 수신자 - 어디에도 안 담긴 아이템은 빈 집합이다`() {
        val recipients = parsingCompletedHandler.resolveRecipients(ItemParsingCompleted(999_999L, 999_999L))

        assertTrue(recipients.isEmpty())
    }

    @Test
    fun `파싱 실패 핸들러도 완료와 동일한 역조회 규칙을 쓴다`() {
        val itemId = 2004L
        val owner = UUID.randomUUID()
        val snapshotId = snapshotIdFor(itemId)
        wishRepository.save(Wish(owner, snapshotId))

        val recipients = parsingFailedHandler.resolveRecipients(ItemParsingFailed(itemId, snapshotId))

        assertEquals(setOf(owner), recipients)
    }

    @Test
    fun `파싱 완료 라우팅 - 위시로 담긴 아이템은 WISH 다 (토너먼트 식별자 없음)`() {
        val itemId = 3001L
        val snapshotId = snapshotIdFor(itemId)
        wishRepository.save(Wish(UUID.randomUUID(), snapshotId))

        val routing = parsingCompletedHandler.resolveRouting(ItemParsingCompleted(itemId, snapshotId))

        assertEquals(NotificationRouting.Wish(null), routing)
    }

    @Test
    fun `파싱 완료 라우팅 - 토너먼트로 담긴 아이템은 TOURNAMENT 와 그 출전 좌표(tournamentId·tournamentItemId)다`() {
        val itemId = 3002L
        val tournamentId = 1100L
        val snapshotId = snapshotIdFor(itemId)
        val tournamentItem =
            tournamentItemRepository.saveAll(listOf(TournamentItem(tournamentId, UUID.randomUUID(), snapshotId))).first()

        val routing = parsingCompletedHandler.resolveRouting(ItemParsingCompleted(itemId, snapshotId))

        assertEquals(NotificationRouting.Tournament(tournamentId, tournamentItem.getId()), routing)
    }

    @Test
    fun `파싱 실패 라우팅도 완료와 동일 규칙으로 토너먼트 출전 좌표를 싣는다`() {
        val itemId = 3003L
        val tournamentId = 1101L
        val snapshotId = snapshotIdFor(itemId)
        val tournamentItem =
            tournamentItemRepository.saveAll(listOf(TournamentItem(tournamentId, UUID.randomUUID(), snapshotId))).first()

        val routing = parsingFailedHandler.resolveRouting(ItemParsingFailed(itemId, snapshotId))

        assertEquals(NotificationRouting.Tournament(tournamentId, tournamentItem.getId()), routing)
    }

    @Test
    fun `파싱 실패 라우팅 - 위시로 담긴 아이템은 WISH 다`() {
        val itemId = 3004L
        val snapshotId = snapshotIdFor(itemId)
        wishRepository.save(Wish(UUID.randomUUID(), snapshotId))

        val routing = parsingFailedHandler.resolveRouting(ItemParsingFailed(itemId, snapshotId))

        assertEquals(NotificationRouting.Wish(null), routing)
    }

    // ── 신규 토너먼트 알림(#473): 플레이링크 플레이 · 완료 · 결과 ──────────────────────────

    @Test
    fun `플레이링크 플레이 알림 수신자는 ROOT 주최자다`() {
        val owner = UUID.randomUUID()
        val player = UUID.randomUUID()
        val rootId = createRootWithOwner(owner)

        val recipients = playedFromLinkHandler.resolveRecipients(TournamentPlayedFromLink(rootId, player))

        assertEquals(setOf(owner), recipients)
    }

    @Test
    fun `완료 알림 수신자는 ROOT 주최자이고, 완료자가 곧 주최자면 제외된다`() {
        val owner = UUID.randomUUID()
        val member = UUID.randomUUID()
        val rootId = createRootWithOwner(owner)

        assertEquals(setOf(owner), completedHandler.resolveRecipients(TournamentCompleted(rootId, member)))
        // actor(완료자)가 주최자 본인이면 자기 알림을 막는다 → 빈 집합.
        assertTrue(completedHandler.resolveRecipients(TournamentCompleted(rootId, owner)).isEmpty())
    }

    @Test
    fun `결과 알림 수신자는 ROOT 참가자와 플레이링크 클론 소유자 합집합에서 주최자를 뺀 집합이다`() {
        val owner = UUID.randomUUID()
        val participant = UUID.randomUUID()
        val guest = UUID.randomUUID()
        val rootId = createRootWithOwner(owner)
        tournamentUserRepository.save(TournamentUser(rootId, participant)) // ROOT 참가자(아이템 등록·합류)
        createClone(rootId, guest) // 플레이링크 클론 소유자(게스트)

        val recipients = resultReadyHandler.resolveRecipients(TournamentResultReady(rootId, owner))

        // 주최자(actor)는 빠지고, ROOT 참가자 + 클론 소유자만 남는다.
        assertEquals(setOf(participant, guest), recipients)
    }

    @Test
    fun `존재하지 않는 ROOT 면 플레이·완료 알림 수신자는 빈 집합이다 (resolver not-found 분기)`() {
        val absentRootId = 987_654L
        assertTrue(playedFromLinkHandler.resolveRecipients(TournamentPlayedFromLink(absentRootId, UUID.randomUUID())).isEmpty())
        assertTrue(completedHandler.resolveRecipients(TournamentCompleted(absentRootId, UUID.randomUUID())).isEmpty())
        // 결과 알림도 참여자·클론이 하나도 없으면 빈 집합 → dispatch 가 early return 으로 떨군다.
        assertTrue(resultReadyHandler.resolveRecipients(TournamentResultReady(absentRootId, UUID.randomUUID())).isEmpty())
    }

    @Test
    fun `완료 알림 actor 컨텍스트는 완료한 사람의 닉네임과 프사다`() {
        val actor = UUID.randomUUID()
        userRepository.save(User(id = actor, nickname = "행위자", profileImage = "https://x/p.png", identityType = IdentityType.GUEST))

        val context = completedHandler.resolveActorContext(TournamentCompleted(1234L, actor))

        assertEquals(mapOf("actorName" to "행위자", "tournamentId" to "1234", "tournamentName" to "토너먼트"), context.variables)
        assertEquals("https://x/p.png", context.imageUrl)
    }

    // ROOT 토너먼트 + 주최자(TournamentUser) fixture. ownerTournamentUserId 를 실제 TU id 로 연결한다.
    private fun createRootWithOwner(ownerUserId: UUID): Long {
        val root = tournamentRepository.saveTournament(
            Tournament(ownerTournamentUserId = 0L, name = "t", inviteCode = nextInviteCode(), inviteExpiresAt = LocalDateTime.now().plusDays(1)),
        )
        val ownerTu = tournamentUserRepository.save(TournamentUser(root.getId(), ownerUserId))
        root.assignOwner(ownerTu.getId())
        tournamentRepository.saveTournament(root)
        return root.getId()
    }

    // ROOT 의 CLONE(sourceTournamentId 연결) + 그 소유자(TournamentUser) fixture.
    private fun createClone(
        rootId: Long,
        ownerUserId: UUID,
    ): Long {
        val clone = tournamentRepository.saveTournament(
            Tournament(
                ownerTournamentUserId = 0L,
                name = "t",
                inviteCode = nextInviteCode(),
                inviteExpiresAt = LocalDateTime.now().plusDays(1),
                sourceTournamentId = rootId,
            ),
        )
        val tu = tournamentUserRepository.save(TournamentUser(clone.getId(), ownerUserId))
        clone.assignOwner(tu.getId())
        tournamentRepository.saveTournament(clone)
        return clone.getId()
    }

    // 알림 역조회는 wish/tournament_item→item_snapshots 를 snapshot_id 로 조인해 s.item_id 로 매칭한다.
    // 따라서 그 itemId 로 시딩한 snapshot 의 id 를 wish/tournament_item 의 snapshotId 로 넘겨야 역조회가 맞아떨어진다.
    private fun snapshotIdFor(itemId: Long): Long = itemSnapshotRepository.save(ItemSnapshot.pending(itemId).apply { markProcessing() }).getId()

    private var inviteSeq = 0

    private fun nextInviteCode(): String = "T%05d".format(inviteSeq++)
}
