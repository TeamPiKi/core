package com.depromeet.piki.item.repository

import com.depromeet.piki.item.domain.ItemSnapshot
import java.time.LocalDateTime

interface ItemSnapshotRepository {
    fun save(snapshot: ItemSnapshot): ItemSnapshot

    fun findAllByItemIds(itemIds: Collection<Long>): List<ItemSnapshot>

    // 시작한 토너먼트의 출전 값. 시작과 버전 생성이 같은 아이템 행 잠금을 거쳐 created_at 순서가 커밋 순서와 같다
    fun findAllByItemIdsAsOf(
        itemIds: Collection<Long>,
        at: LocalDateTime,
    ): List<ItemSnapshot>

    // 출처 미상 행은 서버 추출인지 사용자 입력인지 알 수 없어 가격 이력에서 뺀다.
    fun findPriceHistoryByItemId(
        itemId: Long,
        limit: Int,
    ): List<ItemSnapshot>

    fun findById(id: Long): ItemSnapshot?

    fun findByIds(ids: List<Long>): List<ItemSnapshot>
}
