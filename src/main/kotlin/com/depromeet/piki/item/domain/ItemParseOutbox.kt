package com.depromeet.piki.item.domain

import com.depromeet.piki.common.domain.LongBaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table

@Entity
@Table(name = "item_parse_outbox")
class ItemParseOutbox(

    @Column(nullable = false)
    val itemSnapshotId: Long,

) : LongBaseEntity() {
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: ItemParseOutboxStatus = ItemParseOutboxStatus.PENDING
        protected set

    fun isProcessing(): Boolean = status == ItemParseOutboxStatus.PROCESSING

    fun claim() = transit(from = ItemParseOutboxStatus.PENDING, to = ItemParseOutboxStatus.PROCESSING)

    fun requeue() = transit(from = ItemParseOutboxStatus.PROCESSING, to = ItemParseOutboxStatus.PENDING)

    fun succeed() = transit(from = ItemParseOutboxStatus.PROCESSING, to = ItemParseOutboxStatus.SUCCEEDED)

    fun fail() = transit(from = ItemParseOutboxStatus.PROCESSING, to = ItemParseOutboxStatus.FAILED)

    private fun transit(
        from: ItemParseOutboxStatus,
        to: ItemParseOutboxStatus,
    ) {
        check(status == from) { "item parse outbox ${getIdOrNull()} 은 $status 라 $to 로 갈 수 없다" }
        status = to
    }
}
