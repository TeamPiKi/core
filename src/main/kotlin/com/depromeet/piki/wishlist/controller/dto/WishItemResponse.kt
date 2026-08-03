package com.depromeet.piki.wishlist.controller.dto

import com.depromeet.piki.item.domain.Item
import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.domain.ItemStatus
import com.depromeet.piki.wishlist.domain.Wish
import com.depromeet.piki.wishlist.service.dto.WishWithItem
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

@Schema(description = "위시 항목 — 위시 기록(wish)과 상품 스냅샷(item)의 묶음")
data class WishItemResponse(
    @field:Schema(description = "위시 기록")
    val wish: WishView,
    @field:Schema(description = "상품 스냅샷")
    val item: ItemView,
    @field:Schema(
        description = "URL 등록 응답 전용 — 파싱 없이 다른 등록이 만든 완성 값(캐시)에 붙었는지. " +
            "true 면 item 의 값은 이 등록이 새로 가져온 것이 아니다. 등록 외 응답(목록·수정 등)에서는 null.",
        example = "true",
        nullable = true,
    )
    val reused: Boolean? = null,
    @field:Schema(
        description = "URL 등록 응답 전용 — 붙은 캐시 값이 갱신 권고 임계(서버 기준 24시간)보다 낡았는지(서버 판정). " +
            "true 면 \"새로운 정보로 가져올까요?\" 를 물어 사용자가 원할 때 새로고침 API 를 호출한다. " +
            "reused=true 일 때만 의미가 있으며, 등록 외 응답에서는 null.",
        example = "false",
        nullable = true,
    )
    val refreshNeeded: Boolean? = null,
) {
    companion object {
        fun from(
            wish: Wish,
            item: Item,
            snapshot: ItemSnapshot,
            sourcePlatform: String?,
        ): WishItemResponse =
            WishItemResponse(
                wish = WishView.from(wish),
                item = ItemView.from(item, snapshot, sourcePlatform),
            )

        // URL 등록 응답 전용 — 공유 attach 메타(#853)까지 싣는다. 등록만 이 오버로드를 쓰고,
        // 목록·수정 등 다른 경로는 위 기본 from(플래그 null)을 유지한다.
        fun fromRegistration(
            result: WishWithItem,
            sourcePlatform: String?,
        ): WishItemResponse =
            WishItemResponse(
                wish = WishView.from(result.wish),
                item = ItemView.from(result.item, result.snapshot, sourcePlatform),
                reused = result.reused,
                refreshNeeded = result.refreshNeeded,
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

    @Schema(description = "상품 스냅샷")
    data class ItemView(
        @field:Schema(description = "상품 ID", example = "512")
        val id: Long,
        @field:Schema(
            description = "파싱 상태 — PENDING(URL 등록 접수, 파싱 대기)/PROCESSING(파싱 중)/READY(완료)/FAILED(파싱 실패). " +
                "URL 등록은 PENDING 으로 시작해 디스패처가 집어 PROCESSING→READY/FAILED 로 전이하고, 이미지 등록은 PROCESSING 으로 시작한다. " +
                "PENDING·PROCESSING 동안은 name·price·imageUrl 이 비어 있다.",
            example = "READY",
        )
        val status: ItemStatus,
        @field:Schema(description = "상품명 (PENDING·PROCESSING·실패 시 null)", example = "에어 조던 1 미드", nullable = true)
        val name: String?,
        @field:Schema(description = "스냅샷 시점의 현재 판매가 (PENDING·PROCESSING·실패 시 null)", example = "119000", nullable = true)
        val price: Int?,
        @field:Schema(description = "통화 코드 (ISO 4217)", example = "KRW", nullable = true)
        val currency: String?,
        @field:Schema(
            description = "상품 대표 이미지 URL (PENDING·PROCESSING·실패 시 null)",
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
            // 표시값(status·name·price·currency·imageUrl)은 활성 snapshot 에서,
            // 정체성(id·sourceUrl=상품 링크)은 item 에서 읽는다. snapshot 은 5단계 갱신에서 새 버전으로 스왑된다.
            // sourcePlatform 은 SourcePlatformResolver(빈)의 판정이라 호출부(컨트롤러)가 풀어 넘긴다.
            fun from(
                item: Item,
                snapshot: ItemSnapshot,
                sourcePlatform: String?,
            ): ItemView =
                ItemView(
                    id = item.getId(),
                    status = snapshot.status,
                    name = snapshot.name,
                    price = snapshot.price,
                    currency = snapshot.currency,
                    imageUrl = snapshot.imageUrl,
                    sourceUrl = item.link?.toString(),
                    sourcePlatform = sourcePlatform,
                )
        }
    }
}
