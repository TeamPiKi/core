package com.depromeet.piki.notification.flow

import com.depromeet.piki.common.event.NotificationEvent
import com.depromeet.piki.notification.domain.NotificationType
import com.depromeet.piki.notification.handler.NotificationEventHandler
import com.depromeet.piki.notification.repository.NotificationRepository
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.uuidToBytes
import com.depromeet.piki.tournament.domain.Tournament
import com.depromeet.piki.tournament.domain.TournamentUser
import com.depromeet.piki.tournament.event.TournamentCompleted
import com.depromeet.piki.tournament.event.TournamentPlayedFromLink
import com.depromeet.piki.tournament.event.TournamentResultReady
import com.depromeet.piki.tournament.repository.TournamentRepository
import com.depromeet.piki.tournament.repository.TournamentUserRepository
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.user.domain.User
import com.depromeet.piki.user.repository.UserRepository
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AssignableTypeFilter
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 알림의 **구독 경로**를 지키는 테스트(#961). 여기가 비어 있던 탓에, 이벤트는 발행되고 핸들러도 있는데 리스너만
// 없어 알림이 로그 한 줄 없이 사라지는 상태가 prod 에서 37시간 넘게 초록불이었다.
//
// 기존 알림 테스트가 못 잡은 이유: 전부 dispatcher.dispatch(...) 를 직접 부르거나 핸들러를 직접 주입해 검증해서,
// "발행 → 리스너 → 디스패처" 구간만 정확히 사각이었다. 그래서 이 파일은 반드시 **실제 발행**으로 진입한다.
//
// @Transactional 을 쓰지 않는다 — 리스너가 AFTER_COMMIT 이라 자동 롤백 환경에선 아예 뜨지 않는다. 실제로 커밋하고
// 별도 워커 스레드(@Async)의 전달 완료를 Awaitility 로 기다린 뒤, 자기가 만든 행을 메서드 끝에서 직접 정리한다
// (CLAUDE.md '동시성·시간 의존' 별도 분류와 같은 패턴 — NotificationBadgeSyncAsyncIntegrationTest 참조).
class NotificationEventSubscriptionIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var eventPublisher: ApplicationEventPublisher

    @Autowired private lateinit var transactionTemplate: TransactionTemplate

    @Autowired private lateinit var userRepository: UserRepository

    @Autowired private lateinit var tournamentRepository: TournamentRepository

    @Autowired private lateinit var tournamentUserRepository: TournamentUserRepository

    @Autowired private lateinit var notificationRepository: NotificationRepository

    @Autowired private lateinit var handlers: List<NotificationEventHandler<*>>

    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    // 마커를 구현한 이벤트는 리스너가 타입 하나로 다 받으므로, 핸들러가 등록됐는데 마커가 없으면 그 알림은
    // 발행돼도 아무에게도 닿지 않는다 — #961 의 정확한 실패 모양이다. 사람 규율 대신 여기서 기계로 막는다.
    @Test
    fun `등록된 알림 핸들러의 이벤트는 모두 NotificationEvent 마커를 구현한다`() {
        val unsubscribed =
            handlers
                .map { it.eventType }
                .filterNot { NotificationEvent::class.java.isAssignableFrom(it.java) }
        assertTrue(
            unsubscribed.isEmpty(),
            "핸들러는 있는데 NotificationEvent 마커가 없어 구독되지 않는 이벤트: " +
                "${unsubscribed.map { it.simpleName }} — 마커를 구현하거나 핸들러를 지워라.",
        )
    }

    // 반대 방향 — 마커만 붙이고 핸들러를 안 만들면 리스너는 받지만 디스패처가 '핸들러 미등록' 으로 터진다(500).
    // 그 폭발을 런타임이 아니라 CI 에서 본다.
    @Test
    fun `NotificationEvent 마커를 구현한 이벤트는 모두 대응 핸들러가 있다`() {
        val scanner = ClassPathScanningCandidateComponentProvider(false)
        scanner.addIncludeFilter(AssignableTypeFilter(NotificationEvent::class.java))
        val markedEvents =
            scanner
                .findCandidateComponents("com.depromeet.piki")
                .mapNotNull { it.beanClassName }
                .map { Class.forName(it) }
        val handledEvents = handlers.map { it.eventType.java }.toSet()
        val unhandled = markedEvents.filterNot { it in handledEvents }
        assertTrue(
            unhandled.isEmpty(),
            "마커는 붙었는데 대응 NotificationEventHandler 빈이 없는 이벤트: " +
                "${unhandled.map { it.simpleName }} — 핸들러를 만들거나 마커를 떼라.",
        )
        // 스캐너가 아무것도 못 찾으면 위 단언이 공허하게 통과한다. 최소 한 건은 잡혀야 검사가 살아 있는 것이다.
        assertTrue(markedEvents.isNotEmpty(), "마커를 구현한 이벤트를 하나도 스캔하지 못했다 — 검사 자체가 죽었다.")
    }

    @Test
    fun `플레이링크로 남이 내 토너먼트를 플레이하면 ROOT 주최자에게 알림이 저장된다`() {
        val owner = UUID.randomUUID()
        val friend = UUID.randomUUID()
        var rootId: Long? = null
        try {
            rootId =
                transactionTemplate.execute {
                    saveUser(owner)
                    saveUser(friend)
                    val root = saveRootWithOwner(owner)
                    // 발행은 트랜잭션 **안**에서 — AFTER_COMMIT 리스너는 커밋 성공 후에만 뜬다(롤백 시 미발송).
                    eventPublisher.publishEvent(TournamentPlayedFromLink(rootTournamentId = root, actorId = friend))
                    root
                }
            assertDelivered(owner, NotificationType.TOURNAMENT_PLAYED_FROM_LINK)
            assertTrue(notificationRepository.findPage(friend, cursor = null, limit = 10).isEmpty()) // actor 본인은 제외
        } finally {
            cleanUp(rootId, owner, friend)
        }
    }

    @Test
    fun `친구가 내 토너먼트 클론을 완료하면 ROOT 주최자에게 알림이 저장된다`() {
        val owner = UUID.randomUUID()
        val friend = UUID.randomUUID()
        var rootId: Long? = null
        try {
            rootId =
                transactionTemplate.execute {
                    saveUser(owner)
                    saveUser(friend)
                    val root = saveRootWithOwner(owner)
                    eventPublisher.publishEvent(TournamentCompleted(rootTournamentId = root, actorId = friend))
                    root
                }
            assertDelivered(owner, NotificationType.TOURNAMENT_COMPLETED)
            assertTrue(notificationRepository.findPage(friend, cursor = null, limit = 10).isEmpty())
        } finally {
            cleanUp(rootId, owner, friend)
        }
    }

    @Test
    fun `주최자가 ROOT 를 완료하면 참여자에게 결과 알림이 저장된다`() {
        val owner = UUID.randomUUID()
        val participant = UUID.randomUUID()
        var rootId: Long? = null
        try {
            rootId =
                transactionTemplate.execute {
                    saveUser(owner)
                    saveUser(participant)
                    val root = saveRootWithOwner(owner)
                    tournamentUserRepository.save(TournamentUser(root, participant))
                    eventPublisher.publishEvent(TournamentResultReady(rootTournamentId = root, actorId = owner))
                    root
                }
            assertDelivered(participant, NotificationType.TOURNAMENT_RESULT_READY)
            assertTrue(notificationRepository.findPage(owner, cursor = null, limit = 10).isEmpty()) // actor(주최자) 제외
        } finally {
            cleanUp(rootId, owner, participant)
        }
    }

    // 비동기 워커가 저장을 끝낼 때까지 기다렸다가 타입까지 확인한다. 리스너가 없으면 영영 0건이라 타임아웃으로 깨진다.
    private fun assertDelivered(
        userId: UUID,
        expected: NotificationType,
    ) {
        await().atMost(Duration.ofSeconds(10)).until {
            notificationRepository.findPage(userId, cursor = null, limit = 10).isNotEmpty()
        }
        val inbox = notificationRepository.findPage(userId, cursor = null, limit = 10)
        assertEquals(1, inbox.size)
        assertEquals(expected, inbox.first().type)
    }

    // 닉네임은 UNIQUE(10자) 라 고정 문자열을 쓰면 테스트끼리 충돌한다 — userId 에서 파생해 격리한다.
    private fun saveUser(id: UUID) {
        val nickname = "u${id.toString().take(5)}"
        userRepository.save(
            User(
                id = id,
                nickname = nickname,
                profileImage = "https://img/$nickname.png",
                identityType = IdentityType.MEMBER,
            ),
        )
    }

    // ROOT 토너먼트 + 주최자 TU. 세 알림 모두 수신자를 ROOT 기준으로 역조회하므로 이 최소 fixture 면 충분하다.
    private fun saveRootWithOwner(ownerUserId: UUID): Long {
        val root =
            tournamentRepository.saveTournament(
                Tournament(
                    ownerTournamentUserId = 0L,
                    name = "구독경로검증",
                    inviteCode = UUID.randomUUID().toString().take(6).uppercase(),
                    inviteExpiresAt = LocalDateTime.now().plusDays(1),
                ),
            )
        val ownerTu = tournamentUserRepository.save(TournamentUser(root.getId(), ownerUserId))
        root.assignOwner(ownerTu.getId())
        tournamentRepository.saveTournament(root)
        return root.getId()
    }

    // @Transactional 롤백을 못 쓰므로(위 클래스 주석) 자기가 만든 행만 명시 정리한다.
    // 단언이 깨져도 남은 행이 다음 테스트의 UNIQUE 를 깨지 않게 finally 에서 부른다 (fixture 생성 전 실패면 rootId=null).
    private fun cleanUp(
        tournamentId: Long?,
        vararg userIds: UUID,
    ) {
        userIds.forEach { jdbcTemplate.update("DELETE FROM notifications WHERE user_id = ?", uuidToBytes(it)) }
        tournamentId?.let {
            jdbcTemplate.update("DELETE FROM tournament_users WHERE tournament_id = ?", it)
            jdbcTemplate.update("DELETE FROM tournaments WHERE id = ?", it)
        }
        userIds.forEach { jdbcTemplate.update("DELETE FROM users WHERE id = ?", uuidToBytes(it)) }
    }
}
