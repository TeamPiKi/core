package com.depromeet.piki.item.service

import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.domain.ItemSnapshotSource
import com.depromeet.piki.item.repository.ItemSnapshotRepository
import org.springframework.stereotype.Component

// 카드 표시값 파생(#857)의 단일 지점. 각 맥락(위시·토너먼트 아이템)이 가진 포인터(snapshotId)는
// "그 맥락에 놓인 버전"의 표식일 뿐, 화면에 보일 값은 여기서 파생한다.
//
// 정책: **최신 기계 READY 는 절대 지지 않는다.**
//   - 기본: 그 상품(item)의 마지막 기계(SERVER/SERVER_LLM) READY 를 보여준다 — 같은 상품을 공유하는 모두가
//     누가 갱신했든 최신 값을 본다.
//   - 수기 존중(맥락 스코프): 포인터가 수기(MANUAL)를 가리키고 그보다 새로운 기계 READY 가 아직 없으면
//     그 수기값을 보여준다. 수기가 놓인 맥락(수기 수정한 위시, 수기값 상태로 추가된 토너먼트)만 이 분기에
//     닿는다 — 다른 맥락의 포인터는 수기를 가리키지 않으므로 자연히 기계값을 본다.
//   - 진행 중 유지: 포인터가 진행 중(PENDING/PROCESSING)이면 그대로 보여준다 — 값이 없어 이김/짐의 대상이
//     아니고, 그 맥락이 스스로 시작한 등록·갱신 흐름의 UX 신호다.
//   - 기계 READY 가 없는 상품(첫 파싱·실패뿐·출처 기록 도입 전 데이터): 포인터를 그대로 보여준다(fallback).
//
// 파생을 타지 않는 곳: 시작된 토너먼트(플레이·히스토리) — start 순간 포인터가 표시 버전으로 박제(repin)되어
// "겨룬 값 = 히스토리 값" 이 고정된다(TournamentService.start).
@Component
class ItemDisplayService(
    private val itemSnapshotRepository: ItemSnapshotRepository,
) {
    // 포인터 버전 목록 → 표시 버전 매핑(key = 포인터 snapshot id). 목록 화면용 배치 — item 별 마지막 기계
    // READY 를 한 번에 끌어와 포인터 수와 무관하게 추가 쿼리 1회다.
    fun resolveDisplay(pointers: Collection<ItemSnapshot>): Map<Long, ItemSnapshot> {
        if (pointers.isEmpty()) return emptyMap()
        val latestMachineByItemId =
            itemSnapshotRepository
                .findLatestMachineReadyByItemIds(pointers.map { it.itemId }.distinct())
                .associateBy { it.itemId }
        return pointers.associate { pointer ->
            pointer.getId() to displayOf(pointer, latestMachineByItemId[pointer.itemId])
        }
    }

    fun resolveDisplay(pointer: ItemSnapshot): ItemSnapshot =
        displayOf(pointer, itemSnapshotRepository.findLatestMachineReadyByItemId(pointer.itemId))

    private fun displayOf(
        pointer: ItemSnapshot,
        latestMachine: ItemSnapshot?,
    ): ItemSnapshot {
        latestMachine ?: return pointer
        if (pointer.isInProgress()) return pointer
        if (pointer.source == ItemSnapshotSource.MANUAL && pointer.getId() > latestMachine.getId()) return pointer
        return latestMachine
    }
}
