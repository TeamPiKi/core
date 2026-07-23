package com.depromeet.piki.metrics.dashboard

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

// 운영 통계 대시보드 조립. 조회 구간(KST from~to)을 created_at 저장 기준(UTC)으로 변환해 리포지토리에 넘기고,
// 집계 결과를 MetricsSnapshot 으로 묶는다. 프리셋(오늘·어제·최근N일)으로 구간을 빠르게 잡고, 현재 구간 vs 직전
// 동일 길이 구간을 비교(PeriodComparison)해 릴리즈·이벤트 전후 변화를 본다. 외부 호출 없이 DB 읽기뿐이라
// 짧은 readOnly 트랜잭션으로 충분하다.
@Service
class MetricsService(
    private val repository: MetricsRepository,
) {
    // 조회 구간 + 활성 프리셋(칩 하이라이트용).
    data class Range(
        val from: LocalDateTime,
        val to: LocalDateTime,
        val preset: String?,
    )

    fun resolveRange(
        preset: String?,
        from: LocalDateTime?,
        to: LocalDateTime?,
    ): Range {
        from?.let { f -> to?.let { t -> return Range(f, t, null) } } // 직접 지정: from·to 둘 다 있으면
        val now = LocalDateTime.now(KST)
        val today = now.toLocalDate()
        return when (preset) {
            "yesterday" -> Range(today.minusDays(1).atStartOfDay(), today.atStartOfDay(), "yesterday")
            "7d" -> Range(today.minusDays(6).atStartOfDay(), now, "7d")
            "30d" -> Range(today.minusDays(29).atStartOfDay(), now, "30d")
            else -> Range(today.atStartOfDay(), now, "today") // 기본 = 오늘
        }
    }

    // excludeInternal=true 면 개발진(developers 명단) 활동을 빼고 집계한다(/admin/metrics 기본). 토글로 false 면 포함.
    @Transactional(readOnly = true)
    fun snapshot(
        from: LocalDateTime,
        to: LocalDateTime,
        excludeInternal: Boolean,
    ): MetricsSnapshot {
        val fromUtc = from.atZone(KST).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime()
        val toUtc = to.atZone(KST).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime()

        val identityCounts = repository.countWithinByIdentityType(fromUtc, toUtc, excludeInternal)
        val (wishUrl, wishImage) = repository.countWishesBySource(fromUtc, toUtc, excludeInternal)
        val (activeWishUrl, activeWishImage) = repository.countActiveWishesBySource(excludeInternal)
        val parsing = repository.countParsingBySource(fromUtc, toUtc)
        val (notifTotal, notifRead) = repository.notificationReadApprox(fromUtc, toUtc, excludeInternal)
        // announcements.sent_at 은 다른 테이블(UTC 저장)과 달리 KST wall-clock 으로 저장된다(Announcement.markSent → now(KST)).
        // 그래서 UTC 로 변환한 fromUtc/toUtc 로 조회하면 9시간 어긋나 늘 0/0/0 이 된다 → 원본 KST 구간(from·to)으로 조회한다.
        val (deliverySuccess, deliveryFailure, deliverySkipped) = repository.announcementDelivery(from, to)
        val hourly = repository.hourlySignups(fromUtc, toUtc, excludeInternal)

        return MetricsSnapshot(
            from = from,
            to = to,
            signup =
                MetricsSnapshot.Signup(
                    before = repository.countActiveUsersBefore(fromUtc, excludeInternal),
                    within = repository.countActiveUsersWithin(fromUtc, toUtc, excludeInternal),
                    withinMembers = identityCounts["MEMBER"] ?: 0L,
                    withinGuests = identityCounts["GUEST"] ?: 0L,
                    byProvider = repository.countSignupsByProvider(fromUtc, toUtc, excludeInternal),
                    guestToMemberConversions = repository.countGuestToMemberConversions(fromUtc, toUtc, excludeInternal),
                ),
            wish =
                MetricsSnapshot.Wish(
                    total = repository.countWishes(fromUtc, toUtc, excludeInternal),
                    fromUrl = wishUrl,
                    fromImage = wishImage,
                    activeTotal = activeWishUrl + activeWishImage,
                    activeFromUrl = activeWishUrl,
                    activeFromImage = activeWishImage,
                    wishParsedReady = parsing.wishReady,
                    wishParsedFailed = parsing.wishFailed,
                    tournamentParsedReady = parsing.tournamentReady,
                    tournamentParsedFailed = parsing.tournamentFailed,
                ),
            tournament =
                MetricsSnapshot.Tournament(
                    created = repository.countTournamentsCreated(fromUtc, toUtc, excludeInternal),
                    participants = repository.countTournamentParticipants(fromUtc, toUtc, excludeInternal),
                    itemsAdded = repository.countTournamentItems(fromUtc, toUtc, excludeInternal),
                    completed = repository.countTournamentCompleted(fromUtc, toUtc, excludeInternal),
                    plays = repository.countTournamentPlays(fromUtc, toUtc, excludeInternal),
                ),
            pushReachableUsers = repository.countPushReachableUsers(excludeInternal),
            retention =
                MetricsSnapshot.Retention(
                    cohortSignups = repository.countSignupsInWindow(fromUtc, toUtc, excludeInternal),
                    d1Returned = repository.countD1Returned(fromUtc, toUtc, excludeInternal),
                    dau = repository.dailyActiveUsers(from.toLocalDate(), to.minusSeconds(1).toLocalDate(), excludeInternal),
                ),
            push =
                MetricsSnapshot.Push(
                    byType = repository.notificationsByType(fromUtc, toUtc, excludeInternal),
                    deliverySuccess = deliverySuccess,
                    deliveryFailure = deliveryFailure,
                    deliverySkipped = deliverySkipped,
                    notificationsTotal = notifTotal,
                    readApprox = notifRead,
                ),
            hourlySignups = hourBuckets(from, to, hourly),
        )
    }

    // 현재 구간 vs 직전 동일 길이 구간 비교. 창을 릴리즈 후로 잡으면 직전 동기간(릴리즈 전)이 자동 비교군이 된다.
    @Transactional(readOnly = true)
    fun compareWithPrevious(
        from: LocalDateTime,
        to: LocalDateTime,
        excludeInternal: Boolean,
    ): PeriodComparison {
        val length = Duration.between(from, to)
        val prevFrom = from.minus(length)
        val prev = snapshot(prevFrom, from, excludeInternal)
        val cur = snapshot(from, to, excludeInternal)
        val rows =
            listOf(
                PeriodComparison.Row("신규 가입", prev.signup.within, cur.signup.within),
                PeriodComparison.Row("위시 담기", prev.wish.total, cur.wish.total),
                PeriodComparison.Row("토너먼트 생성", prev.tournament.created, cur.tournament.created),
                PeriodComparison.Row("토너먼트 이용자", prev.tournament.participants, cur.tournament.participants),
                PeriodComparison.Row("올라간 아이템", prev.tournament.itemsAdded, cur.tournament.itemsAdded),
                PeriodComparison.Row("토너먼트 플레이", prev.tournament.plays, cur.tournament.plays),
                PeriodComparison.Row("푸시 발송", prev.push.notificationsTotal, cur.push.notificationsTotal),
            )
        return PeriodComparison(prevFrom, from, from, to, rows)
    }

    // 구간 내 KST 시간대 막대. 같은 날 구간이면 [from.hour, to 끝시) 만, 여러 날이면 0~23 전체.
    private fun hourBuckets(
        from: LocalDateTime,
        to: LocalDateTime,
        counts: Map<Int, Long>,
    ): List<MetricsSnapshot.HourCount> {
        val hours =
            if (from.toLocalDate() == to.toLocalDate()) {
                val onHour = to.minute == 0 && to.second == 0 && to.nano == 0
                val end = (if (onHour) to.hour else to.hour + 1).coerceAtMost(24)
                (from.hour until end).toList()
            } else {
                (0..23).toList()
            }
        return hours.map { MetricsSnapshot.HourCount(it, counts[it] ?: 0L) }
    }

    companion object {
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
