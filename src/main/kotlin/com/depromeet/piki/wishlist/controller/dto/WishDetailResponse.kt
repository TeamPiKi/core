package com.depromeet.piki.wishlist.controller.dto

import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.wishlist.service.dto.WishDetail
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

@Schema(description = "위시 상세 — 지금 보이는 값(item)과 그 상품의 가격 이력(priceHistory)")
data class WishDetailResponse(
    @field:Schema(description = "위시 기록")
    val wish: WishItemResponse.WishView,
    @field:Schema(
        description = "지금 화면에 보일 상품 값. 목록 응답의 item 과 같은 규칙으로 파생된다(같은 카드가 목록과 상세에서 같게 보인다).",
    )
    val item: WishItemResponse.ItemView,
    @field:Schema(
        description = "가격 이력 — 이 상품을 서버가 추출해 관측한 가격을 최신순으로 최대 50건. " +
            "수기 입력(MANUAL)과 출처 미상 버전은 빠진다. 사용자가 직접 넣은 값은 관측치가 아니고, " +
            "스냅샷을 지우는 경로가 없어 한 번 잘못 입력하면 추이에 영구히 남기 때문이다. " +
            "item 과는 별개의 축이라 조인해 맞춰볼 대상이 아니다 — 지금 보이는 값이 수기이거나 추출 진행 중이면 " +
            "여기에 그 값이 없는 것이 정상이고, 한 번도 추출에 성공하지 못한 상품은 빈 배열이다.",
    )
    val priceHistory: List<PriceHistoryEntry>,
) {
    companion object {
        // sourcePlatform 은 SourcePlatformResolver(빈)의 판정이라 호출부(컨트롤러)가 풀어 넘긴다.
        fun from(
            detail: WishDetail,
            sourcePlatform: String?,
        ): WishDetailResponse =
            WishDetailResponse(
                wish = WishItemResponse.WishView.from(detail.wish),
                item = WishItemResponse.ItemView.from(detail.item, detail.snapshot, sourcePlatform),
                priceHistory = detail.history.map { PriceHistoryEntry.from(it) },
            )
    }

    @Schema(description = "가격 이력의 한 점 — 서버가 그 시점에 추출한 가격")
    data class PriceHistoryEntry(
        @field:Schema(description = "그 시점의 판매가", example = "119000")
        val price: Int,
        @field:Schema(description = "추출이 완료된 시각", example = "2026-06-18T10:00:00")
        val extractedAt: LocalDateTime,
    ) {
        companion object {
            // 기계(SERVER·SERVER_LLM) READY 버전만 조회하므로 두 필드는 ItemSnapshot 의 READY 불변식
            // (requireReadyInvariant)이 보장한다 — 항상 채워져 있다. 비어 있으면 그 불변식이 깨진 코드 버그이므로
            // requireNotNull(500)으로 드러낸다(정상 흐름의 클라이언트는 닿지 않는다).
            fun from(snapshot: ItemSnapshot): PriceHistoryEntry {
                val snapshotId = snapshot.getId()
                return PriceHistoryEntry(
                    price = requireNotNull(snapshot.price) { "READY snapshot $snapshotId 의 price 가 없다" },
                    extractedAt = requireNotNull(snapshot.extractedAt) { "READY snapshot $snapshotId 의 extractedAt 이 없다" },
                )
            }
        }
    }
}
