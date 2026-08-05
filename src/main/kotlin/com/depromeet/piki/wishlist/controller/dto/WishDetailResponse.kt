package com.depromeet.piki.wishlist.controller.dto

import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.domain.ItemSnapshotSource
import com.depromeet.piki.wishlist.service.dto.WishDetail
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime
import java.util.UUID

@Schema(description = "위시 상세 — 지금 보이는 값(item)과 그 상품의 가격 이력(priceHistory)")
data class WishDetailResponse(
    @field:Schema(description = "위시 기록")
    val wish: WishItemResponse.WishView,
    @field:Schema(
        description = "지금 화면에 보일 상품 값. 목록 응답의 item 과 같은 규칙으로 파생된다(같은 카드가 목록과 상세에서 같게 보인다).",
    )
    val item: WishItemResponse.ItemView,
    @field:Schema(
        description = "가격 이력 — 이 상품의 가격 기록을 최신순으로 최대 50건. 서버가 추출한 값(SERVER·SERVER_LLM)과 " +
            "사용자가 직접 입력한 값(MANUAL)을 함께 담으며, 수기는 **같은 상품을 담은 다른 사용자가 넣은 것도 포함**한다 " +
            "(누가 넣었는지는 editedByMe 로만 구분되고 편집자 식별자는 내리지 않는다). 출처 기록 도입 전 버전만 빠진다. " +
            "source 는 신뢰도 등급이 아니라 맥락 표시다 — 서버 추출값도 완전하지 않고(SERVER_LLM 은 재추출 시 값이 달라질 수 있다), " +
            "수기는 사용자가 페이지를 직접 보고 적은 값이다. 다만 서로 다른 값을 재고 있을 수 있어(페이지 표시가 vs 실구매가) " +
            "구분해 보여주기를 권한다. " +
            "item 과는 별개의 축이다 — item 은 맥락 스코프(내 수기 존중, 타인 수기 무시)를 거친 표시값이고 이력은 그 필터를 " +
            "타지 않으므로, 첫 항목이 지금 보이는 값과 다를 수 있다. " +
            "가격 기록이 하나도 없으면 빈 배열이다(그때는 item.status 로 대기와 실패를 가른다).",
    )
    val priceHistory: List<PriceHistoryEntry>,
) {
    companion object {
        // sourcePlatform 은 SourcePlatformResolver(빈)의 판정이라 호출부(컨트롤러)가 풀어 넘긴다.
        // requesterId 는 editedByMe(수기 버전을 내가 넣었는지) 파생용 — 편집자 식별자(UUID)는 응답에 노출하지 않는다.
        fun from(
            detail: WishDetail,
            sourcePlatform: String?,
            requesterId: UUID,
        ): WishDetailResponse =
            WishDetailResponse(
                wish = WishItemResponse.WishView.from(detail.wish),
                item = WishItemResponse.ItemView.from(detail.item, detail.snapshot, sourcePlatform),
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
            description = "이 값의 출처 — SERVER(구조화 파서)·SERVER_LLM(LLM 추출)은 서버가 페이지에서 뽑은 값이고, " +
                "MANUAL 은 사용자가 직접 입력한 값이다. " +
                "신뢰도 등급이 아니라 맥락 표시다 — 서버 추출값도 완전하지 않고(SERVER_LLM 은 재추출 시 값이 달라질 수 있다), " +
                "수기는 사용자가 페이지를 직접 보고 적은 값이다.",
            example = "SERVER",
        )
        val source: ItemSnapshotSource,
        @field:Schema(
            description = "수기(MANUAL) 값을 요청자 본인이 넣었는지 — false 면 같은 상품을 담은 다른 사용자가 넣은 값이다. " +
                "타인의 값은 어떤 조건(회원가·쿠폰 등)에서 본 것인지 알 수 없으므로 구분해 다루는 편이 좋다. " +
                "서버 추출(SERVER·SERVER_LLM) 값은 null.",
            example = "true",
            nullable = true,
        )
        val editedByMe: Boolean?,
    ) {
        companion object {
            // 조회 조건이 READY 로 한정돼 price·extractedAt 은 ItemSnapshot 의 READY 불변식(requireReadyInvariant)이,
            // source 는 쿼리의 "출처 기록된 행만" 필터가 각각 보장한다 — 셋 다 항상 채워져 있다. 비어 있으면 그 불변식이나
            // 필터가 깨진 코드 버그이므로 requireNotNull(500)으로 드러낸다(정상 흐름의 클라이언트는 닿지 않는다).
            fun from(
                snapshot: ItemSnapshot,
                requesterId: UUID,
            ): PriceHistoryEntry {
                val snapshotId = snapshot.getId()
                return PriceHistoryEntry(
                    price = requireNotNull(snapshot.price) { "READY snapshot $snapshotId 의 price 가 없다" },
                    extractedAt = requireNotNull(snapshot.extractedAt) { "READY snapshot $snapshotId 의 extractedAt 이 없다" },
                    source = requireNotNull(snapshot.source) { "가격 이력 snapshot $snapshotId 의 source 가 없다" },
                    editedByMe = editedByMe(snapshot, requesterId),
                )
            }

            // MANUAL 값만 "누가 넣었나" 가 의미를 갖는다. 편집자 UUID 원값은 개인 식별자라 응답에 싣지 않고
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
