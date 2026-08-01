package com.depromeet.piki.notification.service

import com.depromeet.piki.notification.domain.NotificationCategory
import com.depromeet.piki.notification.domain.NotificationCursor
import com.depromeet.piki.notification.domain.NotificationHistorySize
import com.depromeet.piki.notification.repository.NotificationRepository
import com.depromeet.piki.notification.service.dto.NotificationDeleteCommand
import com.depromeet.piki.notification.service.dto.NotificationHistoryPage
import com.depromeet.piki.notification.service.dto.NotificationReadCommand
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

// 알림 히스토리 조회 + 읽음 처리(#246). 발송(채널 fan-out)과 무관한 읽기/상태 변경 경계다.
@Service
class NotificationService(
    private val notificationRepository: NotificationRepository,
    private val defaultPushImage: DefaultPushImage,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 본인 알림을 최신순으로 한 페이지 + 안읽음 수 동봉. hasNext 판단을 위해 한 건 더 조회하고 초과분은 잘라낸다.
    // category 가 있으면 그 카테고리의 type 집합으로 필터(활동/시스템 탭). unreadCount 는 category 무관 전체(앱 badge).
    @Transactional(readOnly = true)
    fun getHistory(
        userId: UUID,
        rawCursor: String?,
        rawSize: Int?,
        category: NotificationCategory?,
    ): NotificationHistoryPage {
        val cursor = NotificationCursor.parse(rawCursor)
        val size = NotificationHistorySize.of(rawSize).value
        val types = category?.let { NotificationCategory.typesOf(it) }
        val fetched = notificationRepository.findPage(userId, cursor, size + 1, types)
        val hasNext = fetched.size > size
        val page = fetched.take(size)
        // 안읽음 수는 category 필터와 무관한 전체(앱 badge) 하나다.
        val unreadCount = notificationRepository.countUnread(userId)
        val nextCursor =
            page
                .lastOrNull()
                ?.getId()
                ?.toString()
                .takeIf { hasNext }
        return NotificationHistoryPage(
            notifications = page,
            unreadCount = unreadCount,
            nextCursor = nextCursor,
            hasNext = hasNext,
            defaultPushImageUrl = defaultPushImage.url,
        )
    }

    // 읽음 처리 — 명령(All/Ids)별 벌크 UPDATE. 본인 소유만 반영(소유 검증은 쿼리의 user_id 조건이 겸한다). 멱등.
    // 처리 후 전체 안읽음 수를 같은 트랜잭션에서 세어 반환한다 — 클라가 앱 badge 를 서버 권위 값으로
    // 미러링하게 해 +1/-1 산수 drift 를 없앤다.
    @Transactional
    fun read(
        userId: UUID,
        command: NotificationReadCommand,
    ): Long {
        val method =
            when (command) {
                NotificationReadCommand.All -> {
                    notificationRepository.markAllRead(userId)
                    "all"
                }
                is NotificationReadCommand.Ids -> {
                    notificationRepository.markRead(userId, command.ids)
                    "ids(${command.ids.size})"
                }
            }
        val unread = notificationRepository.countUnread(userId)
        log.info("알림 읽음 처리 userId={} 방식={} 처리후안읽음={}", userId, method, unread)
        return unread
    }

    // 삭제 처리 — 명령(All/Ids)별 벌크 하드삭제(읽음 무관). 본인 소유만 반영(소유 검증은 쿼리의 user_id 조건이 겸한다). 멱등.
    // 삭제로 안읽음 알림이 사라지면 badge 도 줄어야 하므로, 읽음(read)과 대칭으로 처리 후 전체 안읽음 수를 같은
    // 트랜잭션에서 세어 반환한다 — 클라가 앱 badge 를 서버 권위 값으로 미러링하게 해 산수 drift 를 없앤다.
    @Transactional
    fun delete(
        userId: UUID,
        command: NotificationDeleteCommand,
    ): Long {
        val deleted =
            when (command) {
                NotificationDeleteCommand.All -> notificationRepository.hardDeleteAllByUserId(userId)
                is NotificationDeleteCommand.Ids -> notificationRepository.deleteByUserIdAndIds(userId, command.ids)
            }
        val unread = notificationRepository.countUnread(userId)
        log.info("알림 삭제 userId={} 삭제건수={} 처리후안읽음={}", userId, deleted, unread)
        return unread
    }
}
