package com.depromeet.piki.wishlist.controller.dto

import com.depromeet.piki.item.domain.Item
import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.wishlist.domain.Wish
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

@Schema(description = "위시 상품의 가격 히스토리 — 활성 버전 식별과 추출 완료(READY) 버전 이력의 묶음")
data class WishPriceHistoryResponse(
    @field:Schema(description = "상품 ID (정체성)", example = "512")
    val itemId: Long,
    @field:Schema(
        description = "원본 상품 페이지 URL (이미지로 등록한 항목은 URL 이 없어 null)",
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
    @field:Schema(
        description = "현재 활성(위시가 가리키는) 버전의 snapshot ID. entries 중 isActive=true 인 항목과 일치한다. " +
            "활성 버전이 아직 추출 중(PENDING·PROCESSING)이거나 실패(FAILED)면 가격이 없어 entries 에서 빠질 수 있다.",
        example = "1088",
    )
    val activeSnapshotId: Long,
    @field:Schema(
        description = "가격 히스토리 — 추출 완료(READY) 버전을 최신순(id desc)으로 나열한다. " +
            "갱신·새로고침마다 새 버전이 쌓여 가격·이름·이미지 이력이 보존된다. " +
            "가격이 없는 PENDING·PROCESSING·FAILED 버전은 제외된다.",
    )
    val entries: List<PriceHistoryEntry>,
) {
    companion object {
        // sourcePlatform 은 SourcePlatformResolver(빈)의 판정이라 호출부(컨트롤러)가 풀어 넘긴다.
        fun from(
            wish: Wish,
            item: Item,
            history: List<ItemSnapshot>,
            sourcePlatform: String?,
        ): WishPriceHistoryResponse =
            WishPriceHistoryResponse(
                itemId = item.getId(),
                sourceUrl = item.link?.toString(),
                sourcePlatform = sourcePlatform,
                activeSnapshotId = wish.snapshotId,
                entries = history.map { PriceHistoryEntry.from(it, activeSnapshotId = wish.snapshotId) },
            )
    }

    @Schema(description = "한 추출 버전(snapshot)의 가격 시점")
    data class PriceHistoryEntry(
        @field:Schema(description = "추출 버전(snapshot) ID — 버전 식별·정렬 키", example = "1088")
        val snapshotId: Long,
        @field:Schema(description = "이 버전 시점의 판매가", example = "119000")
        val currentPrice: Int,
        @field:Schema(description = "통화 코드 (ISO 4217)", example = "KRW", nullable = true)
        val currency: String?,
        @field:Schema(description = "이 버전 시점의 상품명", example = "에어 조던 1 미드")
        val name: String,
        @field:Schema(description = "이 버전 시점의 대표 이미지 URL", example = "https://cdn.example.com/p/512.jpg")
        val imageUrl: String,
        @field:Schema(description = "추출이 완료된 시각", example = "2026-06-18T10:00:00")
        val extractedAt: LocalDateTime,
        @field:Schema(description = "현재 활성(위시가 가리키는) 버전인지 여부", example = "true")
        val isActive: Boolean,
    ) {
        companion object {
            // READY 버전만 조회하므로 네 필드(currentPrice·name·imageUrl·extractedAt)는 ItemSnapshot 의 READY 불변식
            // (requireReadyInvariant)이 모두 보장한다 — 항상 채워져 있다. 비어 있으면 그 불변식이 깨진 코드 버그이므로
            // requireNotNull(500)으로 드러낸다(정상 흐름의 클라이언트는 닿지 않는다).
            fun from(
                snapshot: ItemSnapshot,
                activeSnapshotId: Long,
            ): PriceHistoryEntry {
                val snapshotId = snapshot.getId()
                return PriceHistoryEntry(
                    snapshotId = snapshotId,
                    currentPrice = requireNotNull(snapshot.currentPrice) { "READY snapshot $snapshotId 의 currentPrice 가 없다" },
                    currency = snapshot.currency,
                    name = requireNotNull(snapshot.name) { "READY snapshot $snapshotId 의 name 이 없다" },
                    imageUrl = requireNotNull(snapshot.imageUrl) { "READY snapshot $snapshotId 의 imageUrl 이 없다" },
                    extractedAt = requireNotNull(snapshot.extractedAt) { "READY snapshot $snapshotId 의 extractedAt 이 없다" },
                    isActive = snapshotId == activeSnapshotId,
                )
            }
        }
    }
}
