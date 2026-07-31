package com.depromeet.piki.wishlist.controller.dto

import com.depromeet.piki.item.domain.Item
import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.domain.ItemSnapshotSource
import com.depromeet.piki.wishlist.domain.Wish
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime
import java.util.UUID

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
            "가격이 없는 PENDING·PROCESSING·FAILED 버전은 제외된다. " +
            "수기(MANUAL) 버전도 포함되므로 가격 추적 그래프 등 기계값만 믿는 뷰는 source 로 걸러 그린다(클라 기본값).",
    )
    val entries: List<PriceHistoryEntry>,
) {
    companion object {
        // sourcePlatform 은 SourcePlatformResolver(빈)의 판정이라 호출부(컨트롤러)가 풀어 넘긴다.
        // requesterId 는 editedByMe(수기 버전을 내가 고쳤는지) 파생용 — 편집자 식별자(UUID)는 응답에 노출하지 않는다.
        fun from(
            wish: Wish,
            item: Item,
            history: List<ItemSnapshot>,
            sourcePlatform: String?,
            requesterId: UUID,
        ): WishPriceHistoryResponse =
            WishPriceHistoryResponse(
                itemId = item.getId(),
                sourceUrl = item.link?.toString(),
                sourcePlatform = sourcePlatform,
                activeSnapshotId = wish.snapshotId,
                entries = history.map { PriceHistoryEntry.from(it, activeSnapshotId = wish.snapshotId, requesterId = requesterId) },
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
        @field:Schema(
            description = "이 버전을 만든 출처 — SERVER(구조화 파서)·SERVER_LLM(LLM 추출)·MANUAL(사용자 수기 입력). " +
                "수기값은 신뢰하지 않는 값이므로 가격 추적 뷰의 기본값은 MANUAL 제외를 권장한다. " +
                "출처 기록 도입 전에 쌓인 버전은 null(모름).",
            example = "SERVER",
            nullable = true,
        )
        val source: ItemSnapshotSource?,
        @field:Schema(
            description = "수기(MANUAL) 버전을 요청자 본인이 입력했는지 — false 면 같은 상품을 공유하는 타인이 고친 값이다. " +
                "기계(SERVER·SERVER_LLM)·출처 미상 버전은 null.",
            example = "true",
            nullable = true,
        )
        val editedByMe: Boolean?,
    ) {
        companion object {
            // READY 버전만 조회하므로 네 필드(currentPrice·name·imageUrl·extractedAt)는 ItemSnapshot 의 READY 불변식
            // (requireReadyInvariant)이 모두 보장한다 — 항상 채워져 있다. 비어 있으면 그 불변식이 깨진 코드 버그이므로
            // requireNotNull(500)으로 드러낸다(정상 흐름의 클라이언트는 닿지 않는다).
            fun from(
                snapshot: ItemSnapshot,
                activeSnapshotId: Long,
                requesterId: UUID,
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
                    source = snapshot.source,
                    editedByMe = editedByMe(snapshot, requesterId),
                )
            }

            // MANUAL 버전만 "누가 고쳤나"가 의미를 갖는다. 편집자 UUID 원값은 개인 식별자라 응답에 싣지 않고
            // 본인 여부(Boolean)로만 파생한다.
            private fun editedByMe(
                snapshot: ItemSnapshot,
                requesterId: UUID,
            ): Boolean? {
                if (snapshot.source != ItemSnapshotSource.MANUAL) return null
                val editor = snapshot.editedBy ?: return null
                return editor == requesterId
            }
        }
    }
}
