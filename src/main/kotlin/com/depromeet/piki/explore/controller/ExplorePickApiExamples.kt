package com.depromeet.piki.explore.controller

import com.depromeet.piki.common.openapi.OpenApiObjectMapper
import com.depromeet.piki.common.openapi.binds
import com.depromeet.piki.common.openapi.examples
import com.depromeet.piki.common.response.ApiResponseBody
import com.depromeet.piki.common.response.PageResponse
import com.depromeet.piki.explore.controller.dto.ExploreCandidateResponse
import com.depromeet.piki.explore.controller.dto.ExploreFolderView
import com.depromeet.piki.explore.controller.dto.ExploreItemView
import com.depromeet.piki.explore.controller.dto.ExploreOwnerView
import com.depromeet.piki.explore.controller.dto.ExplorePickCandidatesResponse
import com.depromeet.piki.explore.controller.dto.ExplorePickDetailResponse
import com.depromeet.piki.explore.controller.dto.ExplorePickResponse
import com.depromeet.piki.explore.controller.dto.ExplorePicksResponse
import com.depromeet.piki.explore.controller.dto.ExploreTournamentDetailView
import com.depromeet.piki.explore.controller.dto.ExploreTournamentView
import com.depromeet.piki.explore.controller.dto.ExploreWishCopyResponse
import com.depromeet.piki.explore.domain.ExploreException
import com.depromeet.piki.profile.domain.ProfileRelation
import com.depromeet.piki.wishlist.domain.WishException
import org.springdoc.core.customizers.OperationCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import java.time.LocalDateTime
import java.util.UUID

@Configuration
class ExplorePickApiExamples(
    private val openApiObjectMapper: OpenApiObjectMapper,
) {
    @Bean
    fun explorePickOpenApiExamples(): OperationCustomizer =
        OperationCustomizer { operation, handlerMethod ->
            if (handlerMethod.binds(ExplorePickController::getPicks)) {
                operation.examples(openApiObjectMapper.delegate) {
                    add(
                        status = HttpStatus.OK,
                        name = "조회 성공 (다음 쪽 있음)",
                        payload =
                            ApiResponseBody.ok(
                                ExplorePicksResponse(updatedAt = LocalDateTime.of(2026, 11, 27, 1, 0), picks = listOf(pickCard)),
                                PageResponse(nextCursor = "2048", hasNext = true),
                            ),
                    )
                    add(
                        status = HttpStatus.OK,
                        name = "목록이 아직 만들어지지 않음",
                        payload = ApiResponseBody.ok(ExplorePicksResponse(updatedAt = null, picks = emptyList())),
                    )
                    add(WishException.invalidCursor(), name = "cursor 가 숫자가 아님")
                }
            }
            if (handlerMethod.binds(ExplorePickController::getPick)) {
                operation.examples(openApiObjectMapper.delegate) {
                    add(status = HttpStatus.OK, name = "친구의 1위", payload = ApiResponseBody.ok(pickDetail))
                    add(
                        status = HttpStatus.OK,
                        name = "고른 사람이 이 상품을 공개 폴더에 넣지 않음",
                        payload = ApiResponseBody.ok(pickDetail.copy(picker = picker.copy(relation = ProfileRelation.NOT_FRIEND, folder = null))),
                    )
                    add(ExploreException.pickNotFound(), name = "볼 수 없는 1위")
                }
            }
            if (handlerMethod.binds(ExplorePickController::getCandidates)) {
                operation.examples(openApiObjectMapper.delegate) {
                    add(status = HttpStatus.OK, name = "조회 성공", payload = ApiResponseBody.ok(candidates))
                    add(ExploreException.pickNotFound(), name = "볼 수 없는 1위")
                }
            }
            if (handlerMethod.binds(ExplorePickController::copyCandidate)) {
                operation.examples(openApiObjectMapper.delegate) {
                    add(
                        status = HttpStatus.OK,
                        name = "새로 담음",
                        payload = ApiResponseBody.ok(ExploreWishCopyResponse(wishId = 2048, alreadySaved = false)),
                    )
                    add(
                        status = HttpStatus.OK,
                        name = "이미 내 위시에 있던 상품",
                        payload = ApiResponseBody.ok(ExploreWishCopyResponse(wishId = 1900, alreadySaved = true)),
                    )
                    unauthorized()
                    add(WishException.guestCannotUseWishlist(), name = "게스트")
                    add(ExploreException.pickNotFound(), name = "볼 수 없는 1위 · 보이는 후보가 아님")
                }
            }
            operation
        }

    companion object {
        private val tournament = ExploreTournamentView(tournamentId = 301, name = "겨울 패딩 뭐 사지")

        private val winnerItem =
            ExploreItemView(
                id = 512,
                name = "숏 패딩 자켓",
                price = 159_000,
                originalPrice = 199_000,
                discountRate = 20,
                currency = "KRW",
                imageUrl = "https://cdn.example.com/p/512.jpg",
                sourceUrl = "https://www.example-shop.com/products/12345",
                sourcePlatform = "29CM",
            )

        private val runnerUpItem =
            winnerItem.copy(
                id = 513,
                name = "롱 패딩",
                price = 249_000,
                originalPrice = null,
                discountRate = null,
                imageUrl = "https://cdn.example.com/p/513.jpg",
                sourceUrl = "https://www.example-shop.com/products/12346",
            )

        private val pickCard =
            ExplorePickResponse(
                pickId = 2048,
                candidateId = 4096,
                pickedAt = LocalDateTime.of(2026, 11, 27, 0, 58, 30),
                saved = false,
                tournament = tournament,
                item = winnerItem,
            )

        private val picker =
            ExploreOwnerView(
                userId = UUID.fromString("9c4f4e97-9c9c-4f4d-8a3d-4e5f60718293"),
                nickname = "난폭한 북극곰",
                profileImage = "https://cdn.piki.day/profiles/polar-bear.png",
                relation = ProfileRelation.FRIEND,
                folder = ExploreFolderView(folderId = 12, name = "11월 위시리스트"),
            )

        private val pickDetail =
            ExplorePickDetailResponse(
                pickId = 2048,
                candidateId = 4096,
                pickedAt = LocalDateTime.of(2026, 11, 27, 0, 58, 30),
                saved = false,
                savedUserCount = 3,
                item = winnerItem,
                picker = picker,
                tournament =
                    ExploreTournamentDetailView(
                        tournamentId = 301,
                        name = "겨울 패딩 뭐 사지",
                        candidateCount = 2,
                        candidateImageUrls = listOf(winnerItem.imageUrl, runnerUpItem.imageUrl),
                    ),
            )

        private val candidates =
            ExplorePickCandidatesResponse(
                pickId = 2048,
                tournament = tournament,
                candidates =
                    listOf(
                        ExploreCandidateResponse(candidateId = 4096, isPick = true, saved = false, item = winnerItem),
                        ExploreCandidateResponse(candidateId = 4097, isPick = false, saved = true, item = runnerUpItem),
                    ),
            )
    }
}
