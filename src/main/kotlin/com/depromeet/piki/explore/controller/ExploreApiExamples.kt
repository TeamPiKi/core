package com.depromeet.piki.explore.controller

import com.depromeet.piki.common.openapi.OpenApiObjectMapper
import com.depromeet.piki.common.openapi.binds
import com.depromeet.piki.common.openapi.examples
import com.depromeet.piki.common.response.ApiResponseBody
import com.depromeet.piki.common.response.PageResponse
import com.depromeet.piki.explore.controller.dto.ExploreItemView
import com.depromeet.piki.explore.controller.dto.ExploreRecentResponse
import com.depromeet.piki.explore.controller.dto.ExploreWishResponse
import com.depromeet.piki.wishlist.domain.WishException
import org.springdoc.core.customizers.OperationCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import java.time.LocalDateTime

@Configuration
class ExploreApiExamples(
    private val openApiObjectMapper: OpenApiObjectMapper,
) {
    @Bean
    fun exploreOpenApiExamples(): OperationCustomizer =
        OperationCustomizer { operation, handlerMethod ->
            if (handlerMethod.binds(ExploreController::getRecent)) {
                operation.examples(openApiObjectMapper.delegate) {
                    add(
                        status = HttpStatus.OK,
                        name = "조회 성공 (다음 쪽 있음)",
                        payload = ApiResponseBody.ok(recent, PageResponse(nextCursor = "1023", hasNext = true)),
                    )
                    add(
                        status = HttpStatus.OK,
                        name = "목록이 아직 만들어지지 않음",
                        payload = ApiResponseBody.ok(ExploreRecentResponse(updatedAt = null, wishes = emptyList())),
                    )
                    add(WishException.invalidCursor(), name = "cursor 가 숫자가 아님")
                }
            }
            operation
        }

    companion object {
        private val linkWish =
            ExploreWishResponse(
                wishId = 1024,
                addedAt = LocalDateTime.of(2026, 11, 24, 0, 58, 30),
                saved = false,
                item =
                    ExploreItemView(
                        id = 512,
                        name = "에어 조던 1 미드",
                        price = 119_000,
                        originalPrice = 139_000,
                        discountRate = 14,
                        currency = "KRW",
                        imageUrl = "https://cdn.example.com/p/512.jpg",
                        sourceUrl = "https://www.example-shop.com/products/12345",
                        sourcePlatform = "29CM",
                    ),
            )

        private val imageWish =
            ExploreWishResponse(
                wishId = 1023,
                addedAt = LocalDateTime.of(2026, 11, 24, 0, 41, 2),
                saved = true,
                item =
                    ExploreItemView(
                        id = 498,
                        name = "캔버스 토트백",
                        price = 45_000,
                        originalPrice = null,
                        discountRate = null,
                        currency = "KRW",
                        imageUrl = "https://cdn.example.com/u/498.jpg",
                        sourceUrl = null,
                        sourcePlatform = null,
                    ),
            )

        private val recent =
            ExploreRecentResponse(
                updatedAt = LocalDateTime.of(2026, 11, 24, 1, 0),
                wishes = listOf(linkWish, imageWish),
            )
    }
}
