package com.depromeet.piki.wishlist.controller.dto

import com.depromeet.piki.contracts.extraction.v1.ExtractionMethod
import com.depromeet.piki.item.domain.DiscountRate
import com.depromeet.piki.item.domain.ItemStatus
import com.depromeet.piki.wishlist.domain.WishFolderResult
import com.depromeet.piki.wishlist.service.dto.WishResult
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

@Schema(description = "위시 항목 — 위시 기록(wish)과 상품(item)의 묶음")
data class WishItemResponse(
    @field:Schema(description = "위시 기록")
    val wish: WishView,
    @field:Schema(description = "상품")
    val item: ItemView,
    @field:Schema(
        description =
            "이 상품이 우승한 내 토너먼트들 — 최신 우승 순이고, 비어 있으면 우승 이력이 없다(위시 카드의 우승 칩). " +
                "기준은 그룹 합산 순위가 아니라 내가 플레이한 결과라, 같은 소셜 토너먼트라도 참여자마다 다르다. " +
                "한 상품이 여러 판에서 이길 수 있어 배열이며, 그 토너먼트를 삭제하면 해당 id 가 빠진다.",
        example = "[1402, 1397]",
    )
    val winnerTournamentIds: List<Long> = emptyList(),
    @field:Schema(
        description =
            "폴더를 지정해 담은 링크가 이미 내 위시였을 때의 결과 — ADDED(그 위시를 이 폴더에 넣음, \"이 폴더에 담았어요\")/" +
                "ALREADY_IN_FOLDER(이미 이 폴더에 있어 바뀐 것 없음, 그 카드로 스크롤·하이라이트). " +
                "새로 담긴 위시와 폴더를 지정하지 않은 응답은 null 이다. 값이 있으면 wish.id 는 기존 위시의 id 다.",
        example = "ADDED",
        nullable = true,
    )
    val folderResult: WishFolderResult? = null,
) {
    companion object {
        fun from(
            result: WishResult,
        ): WishItemResponse =
            WishItemResponse(
                wish = WishView.from(result),
                item = ItemView.from(result),
                winnerTournamentIds = result.winnerTournamentIds,
                folderResult = result.folderResult,
            )
    }

    @Schema(description = "위시 기록")
    data class WishView(
        @field:Schema(description = "위시 항목 ID", example = "1024")
        val id: Long,
        @field:Schema(description = "위시리스트에 담은 시각", example = "2026-05-21T10:00:00")
        val createdAt: LocalDateTime,
        @field:Schema(
            description = "이 위시가 들어 있는 내 폴더 id. 폴더 id 오름차순이고, 폴더에 없으면 빈 배열",
            example = "[12, 15]",
        )
        val folderIds: List<Long> = emptyList(),
    ) {
        companion object {
            fun from(result: WishResult): WishView =
                WishView(
                    id = result.wish.getId(),
                    createdAt = result.wish.createdAt,
                    folderIds = result.folderIds,
                )
        }
    }

    @Schema(description = "상품")
    data class ItemView(
        @field:Schema(description = "상품 ID. 파싱 성공이나 사용자 입력으로 상품이 정해지기 전에는 null", example = "512", nullable = true)
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
        @field:Schema(
            description = "할인 전 정가. 몰이 정가를 노출하지 않거나 사용자가 입력하지 않으면 null(모름). 할인 없는 상품은 price 와 같다",
            example = "139000",
            nullable = true,
        )
        val originalPrice: Int?,
        @field:Schema(
            description = "정가 대비 할인율(%, 내림). originalPrice 가 null 이면 null, 할인 없으면 0",
            example = "14",
            nullable = true,
        )
        val discountRate: Int?,
        @field:Schema(description = "통화 코드 (ISO 4217, PARSING·FAILED 시 null)", example = "KRW", nullable = true)
        val currency: String?,
        @field:Schema(
            description = "상품 대표 이미지 URL (PARSING·FAILED 시 null)",
            example = "https://cdn.example.com/p/512.jpg",
            nullable = true,
        )
        val imageUrl: String?,
        @field:Schema(
            description = "상품 페이지 URL. 상품이 정해지기 전에는 등록한 URL 그대로이고, 정해진 뒤에는 정규화된 상품 주소라 " +
                "값이 바뀔 수 있다. 이미지 등록 항목은 URL 이 없어 null",
            example = "https://www.example-shop.com/products/12345",
            nullable = true,
        )
        val sourceUrl: String?,
        @field:Schema(
            description = "출처 커머스몰 표시명 — 백오피스 등록값이 있으면 그 표기(예: 29CM), 없으면 URL host 에서 " +
                "유도한 임시값(예: 29cm). 표시 텍스트로만 취급한다(안정 식별자 아님). sourceUrl 이 바뀌면 함께 바뀔 수 있다. " +
                "이미지 등록 항목은 URL 이 없어 null.",
            example = "29CM",
            nullable = true,
        )
        val sourcePlatform: String?,
    ) {
        companion object {
            fun from(result: WishResult): ItemView =
                ItemView(
                    id = result.wish.itemId,
                    status = result.card.status,
                    refreshing = result.card.refreshing,
                    method = result.card.display?.method,
                    userInput = result.card.display?.userInput,
                    name = result.card.display?.name,
                    price = result.card.display?.price,
                    originalPrice = result.card.display?.originalPrice,
                    discountRate = DiscountRate.of(result.card.display?.price, result.card.display?.originalPrice),
                    currency = result.card.display?.currency,
                    imageUrl = result.card.display?.imageUrl,
                    sourceUrl = result.card.sourceLink?.toString(),
                    sourcePlatform = result.sourcePlatform,
                )
        }
    }
}
