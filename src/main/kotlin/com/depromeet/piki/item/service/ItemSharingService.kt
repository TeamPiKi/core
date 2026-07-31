package com.depromeet.piki.item.service

import com.depromeet.piki.item.domain.Item
import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.repository.ItemLinkRepository
import com.depromeet.piki.item.repository.ItemRepository
import com.depromeet.piki.item.repository.ItemSnapshotRepository
import com.depromeet.piki.product.domain.CanonicalLink
import com.depromeet.piki.product.domain.ProductLink
import org.springframework.stereotype.Component
import java.time.LocalDateTime

// 공유 등록(#825 활성화)의 정책 계층 — 별칭으로 기존 상품을 알아보고(resolveExistingItem), 어느 버전에 붙을지
// (resolveAttachment)를 정한다. 등록 영속화 빈(위시·토너먼트)이 자기 트랜잭션 안에서 호출한다.
@Component
class ItemSharingService(
    private val itemRepository: ItemRepository,
    private val itemLinkRepository: ItemLinkRepository,
    private val itemSnapshotRepository: ItemSnapshotRepository,
) {
    // 정규화된 입력이 이미 아는 링크 모양이면 그 item(공유 대상)을 돌려준다. 처음 보는 모양·저장 상한 초과는 null —
    // 그 경우 호출부가 기존 신규 경로(새 item + 별칭 기록 + PENDING)로 간다. 별칭은 병합 시 승자에게 이관되므로
    // 여기서 찾은 item 은 살아 있는 정체성이다.
    fun resolveExistingItem(link: ProductLink): Item? {
        val canonical = CanonicalLink.of(link)
        if (canonical.exceedsStorageLimit) return null
        val alias = itemLinkRepository.findByUrlHash(canonical.hash) ?: return null
        return itemRepository.findById(alias.itemId)
    }

    // 공유 item 에서 붙을 버전을 정한다. item 행 락으로 동시 등록의 attach 판정을 직렬화한다(#826) — 락 없이는
    // 두 등록이 각자 "진행 중 없음"을 읽고 PENDING 을 두 개 만들어 같은 상품을 중복 파싱한다.
    //
    // 우선순위(#825 결정 3a):
    //   1. 진행 중(PENDING/PROCESSING) 버전 → 합류. 모두 같은 파싱 결과를 기다린다.
    //   2. 마지막 기계 READY 가 신선(24h 이내) → 재사용. 같은 날 재추출은 이력에 거의 아무것도 더하지 않고
    //      파싱 비용·차단 리스크만 낸다. 수기(MANUAL)는 카드·추적이 믿지 않는 값이라 재사용 판정에서 제외.
    //   3. 그 외(낡음·FAILED 뿐) → 기존 item 에 새 PENDING(재추출).
    //
    // 병합 경합 재시도: resolveExistingItem(비락)과 여기의 행 락 사이에 이 item 이 병합(merge)의 loser 로
    // soft delete 될 수 있다. 그 순간 별칭은 이미 승자 소속이므로, 원본 링크로 한 번 재해석해 승자에 붙는다 —
    // 등록 요청이 밀리초 창의 경합으로 500 으로 죽지 않게 한다. 재해석 후에도 없으면 코드 버그(500).
    fun resolveAttachment(
        itemId: Long,
        link: ProductLink,
    ): ItemSnapshot {
        attachOrNull(itemId)?.let { return it }
        val winner = resolveExistingItem(link) ?: error("공유 대상 item $itemId 이 없다")
        return attachOrNull(winner.getId()) ?: error("공유 대상 item ${winner.getId()} 이 없다")
    }

    private fun attachOrNull(itemId: Long): ItemSnapshot? {
        itemRepository.findByIdForUpdate(itemId) ?: return null
        itemSnapshotRepository.findLatestInProgressByItemId(itemId)?.let { return it }
        itemSnapshotRepository.findLatestMachineReadyByItemId(itemId)
            ?.takeIf { fresh(it) }
            ?.let { return it }
        return itemSnapshotRepository.save(ItemSnapshot.pending(itemId))
    }

    private fun fresh(snapshot: ItemSnapshot): Boolean {
        val extractedAt = snapshot.extractedAt ?: return false
        return extractedAt.isAfter(LocalDateTime.now().minusHours(REUSE_FRESHNESS_HOURS))
    }

    companion object {
        // 재사용 신선도 임계 — 후속 주기 갱신 스케줄러의 주기와 자연스럽게 맞물리는 시작값(코드 상수, #825 결정 3a).
        const val REUSE_FRESHNESS_HOURS = 24L
    }
}
