package com.depromeet.piki.wishlist.controller.dto

import com.depromeet.piki.contracts.extraction.v1.ExtractionMethod
import com.depromeet.piki.item.domain.ItemStatus
import com.depromeet.piki.wishlist.domain.Wish
import com.depromeet.piki.wishlist.service.dto.WishResult
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

@Schema(description = "위시 항목 — 위시 기록(wish)과 상품(item)의 묶음")
data class WishItemResponse(
    @field:Schema(description = "위시 기록")
    val wish: WishView,
    @field:Schema(description = "상품")
    val item: ItemView,
) {
    companion object {
        fun from(
            result: WishResult,
        ): WishItemResponse =
            WishItemResponse(
                wish = WishView.from(result.wish),
                item = ItemView.from(result),
            )
    }

    @Schema(description = "위시 기록")
    data class WishView(
        @field:Schema(description = "위시 항목 ID", example = "1024")
        val id: Long,
        @field:Schema(description = "위시리스트에 담은 시각", example = "2026-05-21T10:00:00")
        val createdAt: LocalDateTime,
    ) {
        companion object {
            fun from(wish: Wish): WishView =
                WishView(
                    id = wish.getId(),
                    createdAt = wish.createdAt,
                )
        }
    }

    @Schema(description = "상품")
    data class ItemView(
        @field:Schema(description = "상품 ID. 파싱이 성공해 상품이 확정되기 전에는 null", example = "512", nullable = true)
        val id: Long?,
        @field:Schema(
            description = "상태 — PARSING(상품 확정 전 파싱 중)/FAILED(상품을 확정하지 못하고 파싱 실패)/" +
                "READY(이름·가격·이미지가 모두 있음)/INCOMPLETE(일부만 있음, 사용자 입력으로 채우면 READY). " +
                "PARSING·FAILED 동안은 id·name·price·imageUrl 이 비어 있다.",
            example = "READY",
        )
        val status: ItemStatus,
        @field:Schema(
            description = "이미 값이 있는 상품을 새로고침하는 중인지. 값과 status 는 새로고침 전 것을 그대로 보인다.",
            example = "false",
        )
        val refreshing: Boolean,
        @field:Schema(
            description = "지금 보이는 값을 서버가 뽑은 경로. STRUCTURED(구조화 파서)·LLM. 사용자 입력과 경로 기록 전 버전은 null. PARSING·FAILED 시 null.",
            example = "STRUCTURED",
            nullable = true,
        )
        val method: ExtractionMethod?,
        @field:Schema(
            description = "지금 보이는 값이 본인이 직접 입력한 값인지(타인의 입력은 내 카드의 표시값이 되지 않는다). PARSING·FAILED 시 null.",
            example = "false",
            nullable = true,
        )
        val userInput: Boolean?,
        @field:Schema(description = "상품명 (PARSING·FAILED 시 null)", example = "에어 조던 1 미드", nullable = true)
        val name: String?,
        @field:Schema(description = "스냅샷 시점의 현재 판매가 (PARSING·FAILED 시 null)", example = "119000", nullable = true)
        val price: Int?,
        @field:Schema(description = "통화 코드 (ISO 4217, PARSING·FAILED 시 null)", example = "KRW", nullable = true)
        val currency: String?,
        @field:Schema(
            description = "상품 대표 이미지 URL (PARSING·FAILED 시 null)",
            example = "https://cdn.example.com/p/512.jpg",
            nullable = true,
        )
        val imageUrl: String?,
        @field:Schema(
            description = "원본 상품 페이지 URL (이미지 등록 항목은 URL 이 없어 null)",
            example = "https://www.example-shop.com/products/12345",
            nullable = true,
        )
        val sourceUrl: String?,
        @field:Schema(
            description = "출처 커머스몰 표시명 — 백오피스 등록값이 있으면 그 표기(예: 29CM), 없으면 URL host 에서 " +
                "유도한 임시값(예: 29cm). 표시 텍스트로만 취급한다(안정 식별자 아님). 이미지 등록 항목은 URL 이 없어 null.",
            example = "29CM",
            nullable = true,
        )
        val sourcePlatform: String?,
    ) {
        companion object {
            fun from(result: WishResult): ItemView =
                ItemView(
                    id = result.item?.getId(),
                    status = result.card.status,
                    refreshing = result.card.refreshing,
                    method = result.card.display?.method,
                    userInput = result.card.display?.userInput,
                    name = result.card.display?.name,
                    price = result.card.display?.price,
                    currency = result.card.display?.currency,
                    imageUrl = result.card.display?.imageUrl,
                    sourceUrl = (result.item?.sourceLink() ?: result.card.pendingLink)?.toString(),
                    sourcePlatform = result.sourcePlatform,
                )
        }
    }
}
