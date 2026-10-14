package com.depromeet.piki.wishlist.domain

import com.depromeet.piki.common.domain.LongBaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.LocalDateTime

// 쌓이기만 한다. 현재 메모는 가장 늦게 쓴 행이고, content 가 없는 행은 지운 기록이다
@Entity
@Table(name = "wish_memos")
class WishMemo(

    @Column(nullable = false, updatable = false)
    val wishId: Long,

    @Column(length = MAX_LENGTH, updatable = false)
    val content: String?,

    @Column(nullable = false, updatable = false)
    val writtenAt: LocalDateTime,

) : LongBaseEntity() {
    init {
        content?.let {
            require(it.isNotBlank()) { "빈 메모는 content 없이 지운 기록으로 남긴다" }
            require(it.length <= MAX_LENGTH) { "메모는 ${MAX_LENGTH}자 이하여야 한다: ${it.length}자" }
        }
    }

    fun sameContentAs(current: WishMemo?): Boolean = content == current?.content

    companion object {
        const val MAX_LENGTH = 100

        fun written(
            wishId: Long,
            rawMemo: String,
        ): WishMemo = WishMemo(wishId = wishId, content = rawMemo.trim().ifEmpty { null }, writtenAt = LocalDateTime.now())
    }
}
