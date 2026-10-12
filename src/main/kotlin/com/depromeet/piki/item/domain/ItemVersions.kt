package com.depromeet.piki.item.domain

import java.util.UUID

// 한 상품의 버전들 중 카드에 보일 버전을 고른다(#1051)
// 보이는 값은 기계 READY(남이 요청한 것이어도 됨)와 내 값(내가 요청하거나 직접 넣은 버전) 둘뿐이다
class ItemVersions private constructor(
    private val versions: List<ItemSnapshot>,
) {
    val itemId: Long get() = versions.first().itemId

    // 내 값과 기계 READY 중 더 새로운 것. 내 입력은 그보다 새 기계 READY 에만 진다
    fun displayFor(viewer: UUID): ItemSnapshot {
        val mine = versions.lastOrNull { it.isOwnedBy(viewer) }
        val machineReady = versions.lastOrNull { !it.userInput && it.isReady() }
        listOfNotNull(mine, machineReady).maxByOrNull { it.getId() }?.let { return it }
        return versions.lastOrNull { !it.userInput } ?: versions.last()
    }

    // 직전까지 미완이던 카드가 이 버전으로 채워지는가(#1028). 이 버전을 요청한 카드는 호출부가 뺀다
    // 뒤에 쌓인 버전을 빼고 판정한다. 성공이 연달아 와도 앞 버전의 알림이 뒤 버전에 가려지지 않게 한다
    fun recovers(
        viewer: UUID,
        snapshotId: Long,
    ): Boolean {
        require(versions.any { it.getId() == snapshotId }) { "이 상품에 버전 $snapshotId 이 없다" }
        val before = versions.filter { it.getId() < snapshotId }
        if (before.isEmpty()) return false
        if (!ItemVersions(before).displayFor(viewer).isIncomplete()) return false
        val after = versions.filter { it.getId() <= snapshotId }
        return ItemVersions(after).displayFor(viewer).getId() == snapshotId
    }

    companion object {
        // "최신" 판정은 전부 id 순서다
        fun of(versions: Collection<ItemSnapshot>): ItemVersions {
            require(versions.isNotEmpty()) { "버전이 하나도 없는 상품은 카드가 될 수 없다" }
            require(versions.map { it.itemId }.distinct().size == 1) { "한 상품의 버전만 묶을 수 있다" }
            return ItemVersions(versions.sortedBy { it.getId() })
        }
    }
}
