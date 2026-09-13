package com.depromeet.piki.item.domain

import com.depromeet.piki.common.domain.LongBaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "parse_requests")
class ParseRequest(

    @Column(nullable = false)
    val itemId: Long,

    @Column(nullable = false, columnDefinition = "BINARY(16)")
    val requestedBy: UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val triggerType: ParseTrigger,

    val resultSnapshotId: Long?,
) : LongBaseEntity() {
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: ParseRequestStatus = ParseRequestStatus.PENDING
        protected set

    @Column(nullable = false)
    var attemptCount: Int = 0
        protected set

    var claimedAt: LocalDateTime? = null
        protected set

    var heartbeatAt: LocalDateTime? = null
        protected set

    var finishedAt: LocalDateTime? = null
        protected set

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    var failureReason: ParseFailureReason? = null
        protected set

    // PENDING → PROCESSING. attemptCount 는 건드리지 않는다. 시도 소모는 워커가 실행에 진입할 때 일어나므로,
    // 제출이 거부돼 실행이 0회인 요청은 예산을 잃지 않는다.
    // heartbeatAt 을 집기 시각으로 시작한다. 비워 두면 제출이 거부된 요청이 stale 스캔에 영영 안 걸려 마감만 기다린다.
    fun claim() {
        check(status == ParseRequestStatus.PENDING) { "PENDING 이 아닌 요청(status=$status)은 집을 수 없다" }
        status = ParseRequestStatus.PROCESSING
        val now = LocalDateTime.now()
        claimedAt = now
        heartbeatAt = now
    }

    // PROCESSING → PENDING. 일시 오류로 결론 없이 끝난 실행을 워커가 즉시 반납해 다음 tick 이 바로 집게 한다.
    // attemptCount 는 유지한다. 실행은 실제로 일어났으므로 예산 소모가 맞고, 상한 도달 판정은 서비스가 진다.
    fun release() {
        check(status == ParseRequestStatus.PROCESSING) { "PROCESSING 이 아닌 요청(status=$status)은 반납할 수 없다" }
        status = ParseRequestStatus.PENDING
    }

    fun succeed() {
        check(status == ParseRequestStatus.PROCESSING) { "PROCESSING 이 아닌 요청(status=$status)은 성공으로 끝낼 수 없다" }
        status = ParseRequestStatus.SUCCEEDED
        finishedAt = LocalDateTime.now()
    }

    // PENDING 도 받는다. 마감은 아직 집히지 않은 요청도 종결해 영구 정체를 막는다.
    fun fail(reason: ParseFailureReason) {
        check(status == ParseRequestStatus.PENDING || status == ParseRequestStatus.PROCESSING) {
            "이미 종결된 요청(status=$status)은 실패로 끝낼 수 없다"
        }
        status = ParseRequestStatus.FAILED
        failureReason = reason
        finishedAt = LocalDateTime.now()
    }
}
