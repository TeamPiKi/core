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
    // 우선순위(#825 결정 3a, #853 개정):
    //   1. 진행 중(PENDING/PROCESSING) 버전 → 합류. 모두 같은 파싱 결과를 기다린다.
    //   2. 마지막 기계 READY → 신선도 무관 재사용(reused=true). 재사용 확정은 서버가 하지 않는다 — 낡은
    //      값(REFRESH_NEEDED_HOURS 초과)은 refreshNeeded 로 표시해 클라가 "새로 가져올까요?"를 묻고,
    //      사용자가 원할 때 수동 새로고침으로 재추출한다. 등록이 자동으로 파싱을 만들면 위시 행 수에 비례해
    //      부하가 커져 위험하다(#853 — 자동 갱신은 사용자당 상한 설계와 함께 별도 검토).
    //      수기(MANUAL)는 카드·추적이 믿지 않는 값이라 재사용 판정에서 제외.
    //   3. 값이 아예 없음(첫 등록·FAILED 뿐) → 새 PENDING. 등록이 파싱을 만드는 유일한 경우다.
    //
    // 병합 경합 재시도: resolveExistingItem(비락)과 여기의 행 락 사이에 이 item 이 병합(merge)의 loser 로
    // soft delete 될 수 있다. 그 순간 별칭은 이미 승자 소속이므로, 원본 링크로 한 번 재해석해 승자에 붙는다 —
    // 등록 요청이 밀리초 창의 경합으로 500 으로 죽지 않게 한다. 재해석 후에도 없으면 코드 버그(500).
    fun resolveAttachment(
        itemId: Long,
        link: ProductLink,
    ): SharedAttachment {
        attachOrNull(itemId)?.let { return it }
        val winner = resolveExistingItem(link) ?: error("공유 대상 item $itemId 이 없다")
        return attachOrNull(winner.getId()) ?: error("공유 대상 item ${winner.getId()} 이 없다")
    }

    private fun attachOrNull(itemId: Long): SharedAttachment? {
        itemRepository.findByIdForUpdate(itemId) ?: return null
        itemSnapshotRepository.findLatestInProgressByItemId(itemId)?.let {
            // 진행 중 합류 — 곧 새 값이 오므로 "캐시 값 사용" 질문 대상이 아니다.
            return SharedAttachment(snapshot = it, reused = false, refreshNeeded = false)
        }
        itemSnapshotRepository.findLatestMachineReadyByItemId(itemId)?.let {
            return SharedAttachment(snapshot = it, reused = true, refreshNeeded = staleForRefresh(it))
        }
        return SharedAttachment(snapshot = itemSnapshotRepository.save(ItemSnapshot.pending(itemId)), reused = false, refreshNeeded = false)
    }

    private fun staleForRefresh(snapshot: ItemSnapshot): Boolean {
        val extractedAt = snapshot.extractedAt ?: return true
        return extractedAt.isBefore(LocalDateTime.now().minusHours(REFRESH_NEEDED_HOURS))
    }

    companion object {
        // 재사용 값의 갱신 권고 임계 — 이보다 낡은 캐시 값은 refreshNeeded 로 표시해 클라가 "새로 가져올까요?"를
        // 묻게 한다(#853). 판정은 서버가 진다: 클라에 임계를 복제하면 이 값을 조정할 때 서버·클라가 어긋난다.
        const val REFRESH_NEEDED_HOURS = 24L
    }
}

// attach 결과 — 붙은 버전과 "어떻게 붙었는지"의 메타(#853). reused = 파싱 없이 완성된 기존 값에 붙음(캐시),
// refreshNeeded = 그 캐시 값이 갱신 권고 임계보다 낡음(서버 판정). 등록 응답이 이 메타를 그대로 내려
// 클라가 "기존 값 사용/새로 가져오기" 선택 UI 를 그린다.
data class SharedAttachment(
    val snapshot: ItemSnapshot,
    val reused: Boolean,
    val refreshNeeded: Boolean,
)
