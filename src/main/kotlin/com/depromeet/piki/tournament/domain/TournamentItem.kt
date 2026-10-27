package com.depromeet.piki.tournament.domain

import com.depromeet.piki.common.domain.LongBaseEntity
import com.depromeet.piki.item.domain.CardQuery
import com.depromeet.piki.item.domain.ItemSlot
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.LocalDateTime
import java.util.UUID

// 토너먼트에 올라간 아이템. userId 는 올린 사람(게스트·회원). 파싱이 성공하기 전엔 아이템이 비어 있다
@Entity
@Table(name = "tournament_items")
class TournamentItem(

    @Column(nullable = false)
    val tournamentId: Long,

    @Column(nullable = false, columnDefinition = "BINARY(16)")
    val userId: UUID,

    itemId: Long?,

) : LongBaseEntity() {
    @Column
    var itemId: Long? = itemId
        protected set

    init {
        require(tournamentId > 0) { "tournamentId 는 양수여야 한다: $tournamentId" }
        itemId?.let { require(it > 0) { "itemId 는 양수여야 한다: $it" } }
    }

    val slot: ItemSlot get() = ItemSlot.tournamentItem(getId())

    // 토너먼트가 시작되면 토너먼트 아이템의 버전은 고정된다
    fun cardQuery(tournament: Tournament): CardQuery {
        require(tournament.getId() == tournamentId) { "토너먼트 아이템 ${getIdOrNull()} 은 토너먼트 ${tournament.getId()} 의 출전이 아니다" }
        return CardQuery(slot = slot, itemId = itemId, owner = userId, asOf = tournament.startedAt)
    }

    fun fill(itemId: Long) {
        this.itemId?.let {
            check(it == itemId) { "토너먼트 아이템 ${getIdOrNull()} 은 이미 아이템 $it 로 확정됐다" }
            return
        }
        require(itemId > 0) { "itemId 는 양수여야 한다: $itemId" }
        this.itemId = itemId
    }

    fun softDelete() {
        deletedAt = LocalDateTime.now()
    }
}
