package com.depromeet.piki.notification.service

import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.event.ItemParsingCompleted
import com.depromeet.piki.item.event.ItemParsingFailed
import com.depromeet.piki.item.repository.ItemSnapshotRepository
import com.depromeet.piki.notification.repository.NotificationRepository
import com.depromeet.piki.notification.sse.SseEmitterRegistry
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.tournament.domain.TournamentUser
import com.depromeet.piki.tournament.event.TournamentItemAdded
import com.depromeet.piki.tournament.repository.TournamentUserRepository
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.user.domain.User
import com.depromeet.piki.user.repository.UserRepository
import com.depromeet.piki.wishlist.domain.Wish
import com.depromeet.piki.wishlist.repository.WishRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// 인앱(SSE) 수신자의 알림은 **실제 전달에 성공한 뒤** 읽음으로 전환해 히스토리 unread 누적을 막는다(#812).
// 타입은 가리지 않는다 — 파싱이든 토너먼트(비-self)든 인앱이면 화면에 반영되므로 동일하게 적용된다.
// 핵심 계약 셋을 실제 dispatch 체인으로 검증한다: 전달 성공 → 읽음 / 연결 없음 → 안읽음 / **전달 실패 → 안읽음**.
// 마지막 것이 이 설계의 요점이다 — "연결이 있었나" 가 아니라 "써 넣었나" 로 판정해야 전달 실패한 알림이 소실되지 않는다.
// @Transactional 자동 롤백(persistence.save 는 기본 propagation 이라 이 트랜잭션에 합류한다).
@Transactional
class NotificationLiveAutoReadIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var notificationDispatcher: NotificationDispatcher

    @Autowired private lateinit var notificationRepository: NotificationRepository

    @Autowired private lateinit var sseEmitterRegistry: SseEmitterRegistry

    @Autowired private lateinit var itemSnapshotRepository: ItemSnapshotRepository

    @Autowired private lateinit var wishRepository: WishRepository

    @Autowired private lateinit var tournamentUserRepository: TournamentUserRepository

    @Autowired private lateinit var userRepository: UserRepository

    @Test
    fun `인앱(SSE 연결) 유저의 파싱 완료 알림은 자동 읽음으로 저장된다`() {
        val userId = UUID.randomUUID()
        val itemId = 8101L
        val snapshotId = itemSnapshotRepository.save(ItemSnapshot(itemId = itemId, name = "나이키")).getId()
        wishRepository.save(Wish(userId, snapshotId))
        val emitter = SseEmitter()
        sseEmitterRegistry.register(userId, emitter)
        try {
            notificationDispatcher.dispatch(ItemParsingCompleted(itemId, snapshotId))

            val saved = notificationRepository.findPage(userId, cursor = null, limit = 10)
            assertEquals(1, saved.size)
            assertTrue(saved.first().isRead)
        } finally {
            // 레지스트리는 인메모리 싱글턴이라 @Transactional 롤백 대상이 아니다 — 다음 테스트로 누수 안 되게 명시 해제.
            sseEmitterRegistry.unregister(userId, emitter)
        }
    }

    @Test
    fun `인앱(SSE 연결) 유저의 파싱 실패 알림도 자동 읽음으로 저장된다`() {
        // 파싱 실패는 별도 이벤트(ItemParsingFailed)·별도 핸들러라, 완료와 무관하게 자동읽음 경로를 따로 가드한다.
        val userId = UUID.randomUUID()
        val itemId = 8103L
        val snapshotId = itemSnapshotRepository.save(ItemSnapshot(itemId = itemId, name = "나이키")).getId()
        wishRepository.save(Wish(userId, snapshotId))
        val emitter = SseEmitter()
        sseEmitterRegistry.register(userId, emitter)
        try {
            notificationDispatcher.dispatch(ItemParsingFailed(itemId, snapshotId))

            val saved = notificationRepository.findPage(userId, cursor = null, limit = 10)
            assertEquals(1, saved.size)
            assertTrue(saved.first().isRead)
        } finally {
            sseEmitterRegistry.unregister(userId, emitter)
        }
    }

    @Test
    fun `레지스트리에 연결이 있어도 전달에 실패하면 안읽음으로 남는다`() {
        // 이 설계의 요점 — 하트비트가 30초 주기라 끊긴 소켓이 그동안 레지스트리에 살아 있다(half-open).
        // 연결 유무로 읽음을 판정하면 그 창에서 "전달 못 했는데 읽음" 이 되어 알림이 소실된다.
        // 여기선 이미 완료된 emitter 로 write 실패를 재현한다.
        val userId = UUID.randomUUID()
        val itemId = 8104L
        val snapshotId = itemSnapshotRepository.save(ItemSnapshot(itemId = itemId, name = "나이키")).getId()
        wishRepository.save(Wish(userId, snapshotId))
        val deadEmitter = SseEmitter().apply { complete() }
        sseEmitterRegistry.register(userId, deadEmitter)
        try {
            notificationDispatcher.dispatch(ItemParsingCompleted(itemId, snapshotId))

            val saved = notificationRepository.findPage(userId, cursor = null, limit = 10)
            assertEquals(1, saved.size)
            assertFalse(saved.first().isRead, "전달 실패한 알림은 안읽음으로 남아 사용자가 결국 봐야 한다")
        } finally {
            sseEmitterRegistry.unregister(userId, deadEmitter)
        }
    }

    @Test
    fun `연결 없는 유저의 파싱 완료 알림은 안읽음으로 저장된다`() {
        val userId = UUID.randomUUID()
        val itemId = 8102L
        val snapshotId = itemSnapshotRepository.save(ItemSnapshot(itemId = itemId, name = "나이키")).getId()
        wishRepository.save(Wish(userId, snapshotId))

        notificationDispatcher.dispatch(ItemParsingCompleted(itemId, snapshotId))

        val saved = notificationRepository.findPage(userId, cursor = null, limit = 10)
        assertEquals(1, saved.size)
        assertFalse(saved.first().isRead)
    }

    @Test
    fun `인앱(SSE 연결) 이면 파싱뿐 아니라 토너먼트 알림도 자동 읽음으로 저장된다`() {
        val userId = UUID.randomUUID()
        val actor = UUID.randomUUID()
        val tournamentId = 8201L
        listOf(userId, actor).forEach { tournamentUserRepository.save(TournamentUser(tournamentId, it)) }
        userRepository.save(User(id = actor, nickname = "행위자", profileImage = "https://x/p.jpg", identityType = IdentityType.GUEST))
        val emitter = SseEmitter()
        sseEmitterRegistry.register(userId, emitter)
        try {
            // 수신자 = 참가자 - actor = {userId}. 인앱이면 타입 무관 자동읽음이라 토너먼트 알림도 읽음으로 저장된다(#812).
            notificationDispatcher.dispatch(TournamentItemAdded(tournamentId, actor))

            val saved = notificationRepository.findPage(userId, cursor = null, limit = 10)
            assertEquals(1, saved.size)
            assertTrue(saved.first().isRead)
        } finally {
            sseEmitterRegistry.unregister(userId, emitter)
        }
    }
}
