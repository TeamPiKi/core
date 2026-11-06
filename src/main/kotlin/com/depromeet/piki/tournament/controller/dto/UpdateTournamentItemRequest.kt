package com.depromeet.piki.tournament.controller.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.springframework.web.multipart.MultipartFile

@Schema(description = "토너먼트 아이템 수정 요청 — 들어온 필드만 갱신한다")
data class UpdateTournamentItemRequest(
    @field:Schema(description = "수정할 상품명", example = "나이키 에어맥스", nullable = true)
    @field:Size(min = 1, max = 512, message = "상품명을 1~512자로 입력해 주세요.")
    val name: String? = null,
    @field:Schema(description = "수정할 현재 판매가", example = "129000", nullable = true)
    @field:Min(value = 0, message = "가격은 0원 이상으로 입력해 주세요.")
    val price: Int? = null,
    @field:Schema(description = "수정할 할인 전 정가 — 판매가 이상이어야 한다", example = "159000", nullable = true)
    @field:Min(value = 0, message = "정가는 0원 이상으로 입력해 주세요.")
    val originalPrice: Int? = null,
    @field:Schema(description = "수정할 통화 코드 (ISO 4217)", example = "KRW", nullable = true)
    @field:Size(max = 8, message = "통화 코드는 8자까지 입력할 수 있어요.")
    val currency: String? = null,
    @field:Schema(description = "수정할 상품 이미지", nullable = true)
    val image: MultipartFile? = null,
)
