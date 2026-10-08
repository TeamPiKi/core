package com.depromeet.piki.wishlist.domain

import com.depromeet.piki.common.domain.LongBaseEntity
import com.depromeet.piki.item.domain.CardQuery
import com.depromeet.piki.item.domain.ItemSlot
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "wishes")
class Wish(

    @Column(nullable = false, columnDefinition = "BINARY(16)")
    val userId: UUID,

    itemId: Long?,

) : LongBaseEntity() {
    @Column
    var itemId: Long? = itemId
        protected set

    @Column(length = MEMO_MAX_LENGTH)
    var memo: String? = null
        protected set

    init {
        itemId?.let { require(it > 0) { "itemId 는 양수여야 한다: $it" } }
    }

    val slot: ItemSlot get() = ItemSlot.wish(getId())

    fun cardQuery(): CardQuery = CardQuery(slot = slot, itemId = itemId, owner = userId)

    fun verifyOwnedBy(userId: UUID) {
        if (this.userId != userId) throw WishException.forbiddenWishItems()
    }

    fun fill(itemId: Long) {
        this.itemId?.let {
            check(it == itemId) { "위시 ${getIdOrNull()} 는 이미 아이템 $it 로 확정됐다" }
            return
        }
        require(itemId > 0) { "itemId 는 양수여야 한다: $itemId" }
        this.itemId = itemId
    }

    fun updateMemo(rawMemo: String) {
        val normalized = rawMemo.trim().ifEmpty { null }
        normalized?.let {
            require(it.length <= MEMO_MAX_LENGTH) { "메모는 ${MEMO_MAX_LENGTH}자 이하여야 한다: ${it.length}자" }
        }
        memo = normalized
    }

    fun delete() {
        deletedAt = LocalDateTime.now()
    }

    companion object {
        const val MEMO_MAX_LENGTH = 100
    }
}
