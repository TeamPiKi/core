package com.depromeet.piki.explore.controller

import com.depromeet.piki.common.openapi.OpenApiObjectMapper
import com.depromeet.piki.common.openapi.binds
import com.depromeet.piki.common.openapi.examples
import com.depromeet.piki.common.response.ApiResponseBody
import com.depromeet.piki.common.response.PageResponse
import com.depromeet.piki.contracts.extraction.v1.ExtractionMethod
import com.depromeet.piki.explore.controller.dto.ExploreFolderView
import com.depromeet.piki.explore.controller.dto.ExploreItemView
import com.depromeet.piki.explore.controller.dto.ExploreOwnerView
import com.depromeet.piki.explore.controller.dto.ExploreRecentResponse
import com.depromeet.piki.explore.controller.dto.ExploreWishDetailResponse
import com.depromeet.piki.explore.controller.dto.ExploreWishResponse
import com.depromeet.piki.item.domain.ItemStatus
import com.depromeet.piki.profile.domain.ProfileRelation
import com.depromeet.piki.wishlist.controller.dto.WishItemResponse
import com.depromeet.piki.wishlist.domain.WishException
import org.springdoc.core.customizers.OperationCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import java.time.LocalDateTime
import java.util.UUID

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
            if (handlerMethod.binds(ExploreController::getWish)) {
                operation.examples(openApiObjectMapper.delegate) {
                    add(status = HttpStatus.OK, name = "친구가 공개 폴더에 담은 위시", payload = ApiResponseBody.ok(friendsWish))
                    add(
                        status = HttpStatus.OK,
                        name = "공개 폴더에 들어 있지 않은 위시",
                        payload = ApiResponseBody.ok(friendsWish.copy(owner = owner.copy(relation = ProfileRelation.NOT_FRIEND, folder = null))),
                    )
                    add(
                        status = HttpStatus.OK,
                        name = "담은 사람을 볼 수 없음 (프로필 비공개 · 차단 관계)",
                        payload = ApiResponseBody.ok(friendsWish.copy(owner = null)),
                    )
                    add(WishException.notFound(), name = "없는 위시 · 지운 위시 · 상품이 아직 정해지지 않은 위시")
                }
            }
            operation
        }

    companion object {
        private val owner =
            ExploreOwnerView(
                userId = UUID.fromString("9c4f4e97-9c9c-4f4d-8a3d-4e5f60718293"),
                nickname = "난폭한 북극곰",
                profileImage = "https://cdn.piki.day/profiles/polar-bear.png",
                relation = ProfileRelation.FRIEND,
                folder = ExploreFolderView(folderId = 12, name = "11월 위시리스트"),
            )

        private val friendsWish =
            ExploreWishDetailResponse(
                wishId = 1024,
                addedAt = LocalDateTime.of(2026, 11, 24, 0, 58, 30),
                saved = false,
                savedUserCount = 12,
                item =
                    WishItemResponse.ItemView(
                        id = 512,
                        status = ItemStatus.READY,
                        refreshing = false,
                        method = ExtractionMethod.STRUCTURED,
                        userInput = false,
                        name = "에어 조던 1 미드",
                        price = 119_000,
                        originalPrice = 139_000,
                        discountRate = 14,
                        currency = "KRW",
                        imageUrl = "https://cdn.example.com/p/512.jpg",
                        sourceUrl = "https://www.example-shop.com/products/12345",
                        sourcePlatform = "29CM",
                    ),
                owner = owner,
            )

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
