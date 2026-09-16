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
}
