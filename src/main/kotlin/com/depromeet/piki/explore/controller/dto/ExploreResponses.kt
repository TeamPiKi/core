package com.depromeet.piki.explore.controller.dto

import com.depromeet.piki.explore.domain.ExploreCard
import com.depromeet.piki.explore.domain.ExploreItem
import com.depromeet.piki.explore.service.dto.ExploreRecentResult
import com.depromeet.piki.explore.service.dto.ExploreWishResult
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

@Schema(description = "탐색의 방금 담긴 위시")
data class ExploreRecentResponse(
    @field:Schema(
        description = "이 목록을 마지막으로 다시 만든 시각. 아직 만들어진 적이 없으면 null",
        example = "2026-11-24T10:00:00+09:00",
        nullable = true,
    )
    val updatedAt: LocalDateTime?,
    @field:Schema(description = "최근에 담긴 순. 같은 상품은 가장 최근에 담긴 한 장만 있다")
    val wishes: List<ExploreWishResponse>,
) {
    companion object {
        fun from(result: ExploreRecentResult): ExploreRecentResponse =
            ExploreRecentResponse(
                updatedAt = result.updatedAt,
                wishes = result.entries.map(ExploreWishResponse::from),
            )
    }
}

@Schema(description = "탐색 카드 한 장")
data class ExploreWishResponse(
    @field:Schema(description = "위시 id. 위시 상세와 내 위시로 담기에 쓴다", example = "1024")
    val wishId: Long,
    @field:Schema(description = "담긴 시각", example = "2026-11-24T09:58:30+09:00")
    val addedAt: LocalDateTime,
    @field:Schema(description = "보는 사람이 이미 내 위시에 가진 상품인지. 비로그인이면 false", example = "false")
    val saved: Boolean,
    val item: ExploreItemView,
) {
    companion object {
        fun from(result: ExploreWishResult): ExploreWishResponse =
            ExploreWishResponse(
                wishId = result.card.wishId,
                addedAt = result.card.addedAt,
                saved = result.saved,
                item = ExploreItemView.from(result.card),
            )
    }
}

@Schema(description = "상품. 필드 이름과 뜻은 위시 목록의 item 과 같다")
data class ExploreItemView(
    @field:Schema(description = "상품 ID", example = "512")
    val id: Long,
    @field:Schema(description = "상품명", example = "에어 조던 1 미드")
    val name: String,
    @field:Schema(description = "현재 판매가", example = "119000")
    val price: Int,
    @field:Schema(description = "할인 전 정가. 모르면 null", example = "139000", nullable = true)
    val originalPrice: Int?,
    @field:Schema(description = "정가 대비 할인율(%, 내림). originalPrice 가 null 이면 null", example = "14", nullable = true)
    val discountRate: Int?,
    @field:Schema(description = "통화 코드 (ISO 4217)", example = "KRW", nullable = true)
    val currency: String?,
    @field:Schema(description = "상품 대표 이미지 URL", example = "https://cdn.example.com/p/512.jpg")
    val imageUrl: String,
    @field:Schema(
        description = "상품 페이지 URL. 이미지로 담은 상품은 null",
        example = "https://www.example-shop.com/products/12345",
        nullable = true,
    )
    val sourceUrl: String?,
    @field:Schema(description = "출처 커머스몰 표시명. 이미지로 담은 상품은 null", example = "29CM", nullable = true)
    val sourcePlatform: String?,
) {
    companion object {
        fun from(card: ExploreCard): ExploreItemView =
            ExploreItemView(
                id = card.itemId,
                name = card.name,
                price = card.price,
                originalPrice = card.originalPrice,
                discountRate = card.discountRate,
                currency = card.currency,
                imageUrl = card.imageUrl,
                sourceUrl = card.sourceUrl,
                sourcePlatform = card.sourcePlatform,
            )

        fun from(item: ExploreItem): ExploreItemView =
            ExploreItemView(
                id = item.itemId,
                name = item.name,
                price = item.price,
                originalPrice = item.originalPrice,
                discountRate = item.discountRate,
                currency = item.currency,
                imageUrl = item.imageUrl,
                sourceUrl = item.sourceUrl,
                sourcePlatform = item.sourcePlatform,
            )
    }
}
