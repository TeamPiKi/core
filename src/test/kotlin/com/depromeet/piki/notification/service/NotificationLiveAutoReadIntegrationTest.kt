package com.depromeet.piki.notification.service

import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.event.ItemParsingCompleted
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

// 인앱(SSE 연결) 수신자의 "내 동작 결과" 알림(파싱)은 저장 시 자동 읽음 처리해 히스토리 unread 누적을 막는다(#812).
// 연결 판정·타입 스코프(파싱만)·비-self 알림 제외를 실제 dispatch 체인으로 검증한다. @Transactional 자동 롤백.
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
