package com.depromeet.piki.wishlist.controller.dto

import com.depromeet.piki.contracts.extraction.v1.ExtractionMethod
import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.wishlist.service.dto.WishDetailResult
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime
import java.util.UUID

@Schema(description = "위시 상세 — 지금 보이는 값(item)과 그 상품의 가격 이력(priceHistory)")
data class WishDetailResponse(
    @field:Schema(description = "위시 기록")
    val wish: WishItemResponse.WishView,
    @field:Schema(
        description = "개인 메모 — 이 위시를 담은 본인만 본다(같은 상품을 담은 다른 사용자와 공유되지 않는다). 없으면 null.",
        example = "생일 선물 후보",
        nullable = true,
    )
    val memo: String?,
    @field:Schema(
        description = "지금 화면에 보일 상품 값. 목록 응답의 item 과 같은 규칙으로 파생된다(같은 카드가 목록과 상세에서 같게 보인다).",
    )
    val item: WishItemResponse.ItemView,
    @field:Schema(
        description = "가격 이력 — 이 상품의 가격 기록을 최신순으로 최대 50건. 서버가 추출한 값과 " +
            "사용자가 직접 입력한 값을 함께 담으며, 사용자 입력은 **같은 상품을 담은 다른 사용자가 넣은 것도 포함**한다 " +
            "(누가 넣었는지는 editedByMe 로만 구분되고 편집자 식별자는 내리지 않는다). 출처 기록 도입 전 버전만 빠진다. " +
            "method·userInput 은 신뢰도 등급이 아니라 맥락 표시다. 서버 추출값도 완전하지 않고(LLM 은 재추출 시 값이 달라질 수 있다), " +
            "사용자 입력은 사용자가 페이지를 직접 보고 적은 값이다. 다만 서로 다른 값을 재고 있을 수 있어(페이지 표시가 vs 실구매가) " +
            "구분해 보여주기를 권한다. " +
            "item 과는 별개의 축이다 — item 은 맥락 스코프(내 입력 존중, 타인 입력 무시)를 거친 표시값이고 이력은 그 필터를 " +
            "타지 않으므로, 첫 항목이 지금 보이는 값과 다를 수 있다. " +
            "가격 기록이 하나도 없으면 빈 배열이다(그때는 item.status 로 대기와 실패를 가른다).",
    )
    val priceHistory: List<PriceHistoryEntry>,
) {
    companion object {
        // requesterId 는 editedByMe(사용자 입력 버전을 내가 넣었는지) 파생용 — 편집자 식별자(UUID)는 응답에 노출하지 않는다.
        fun from(
            detail: WishDetailResult,
            requesterId: UUID,
        ): WishDetailResponse =
            WishDetailResponse(
                wish = WishItemResponse.WishView.from(detail.wishResult.wish),
                memo = detail.wishResult.wish.memo,
                item = WishItemResponse.ItemView.from(detail.wishResult),
                priceHistory = detail.history.map { PriceHistoryEntry.from(it, requesterId) },
            )
    }

    @Schema(description = "가격 이력의 한 점")
    data class PriceHistoryEntry(
        @field:Schema(description = "그 시점의 판매가", example = "119000")
        val price: Int,
        @field:Schema(
            description = "기록된 시각 — 관측값은 추출이 완료된 시각, 본인 입력값은 입력한 시각이다.",
            example = "2026-06-18T10:00:00",
        )
        val extractedAt: LocalDateTime,
        @field:Schema(
            description = "서버가 페이지에서 값을 뽑은 경로. STRUCTURED(구조화 파서)·LLM. 사용자 입력은 null.",
            example = "STRUCTURED",
            nullable = true,
        )
        val method: ExtractionMethod?,
        @field:Schema(description = "사용자가 직접 입력한 값인지", example = "false")
        val userInput: Boolean,
        @field:Schema(
            description = "사용자 입력 값을 요청자 본인이 넣었는지 — false 면 같은 상품을 담은 다른 사용자가 넣은 값이다. " +
                "타인의 값은 어떤 조건(회원가·쿠폰 등)에서 본 것인지 알 수 없으므로 구분해 다루는 편이 좋다. " +
                "사용자 입력이 아니면 null.",
            example = "true",
            nullable = true,
        )
        val editedByMe: Boolean?,
    ) {
        companion object {
            // 가격 이력 쿼리가 이름·가격·이미지가 다 있는 행만 고르므로 price 가 비면 코드 버그다
            fun from(
                snapshot: ItemSnapshot,
                requesterId: UUID,
            ): PriceHistoryEntry {
                val snapshotId = snapshot.getId()
                return PriceHistoryEntry(
                    price = requireNotNull(snapshot.price) { "가격 이력 snapshot $snapshotId 의 price 가 없다" },
                    extractedAt = snapshot.createdAt,
                    method = snapshot.method,
                    userInput = snapshot.userInput,
                    editedByMe = editedByMe(snapshot, requesterId),
                )
            }

            // 편집자 UUID 원값은 개인 식별자라 응답에 싣지 않고 본인 여부로만 내린다
            private fun editedByMe(
                snapshot: ItemSnapshot,
                requesterId: UUID,
            ): Boolean? {
                if (!snapshot.userInput) return null
                return snapshot.isOwnedBy(requesterId)
            }
        }
    }
}
