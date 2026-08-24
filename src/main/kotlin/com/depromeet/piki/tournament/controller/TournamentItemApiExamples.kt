package com.depromeet.piki.tournament.controller

import com.depromeet.piki.common.exception.CommonErrorCode
import com.depromeet.piki.common.openapi.OpenApiObjectMapper
import com.depromeet.piki.common.openapi.binds
import com.depromeet.piki.common.openapi.examples
import com.depromeet.piki.common.ratelimit.ItemQuotaException
import com.depromeet.piki.common.response.ApiResponseBody
import com.depromeet.piki.common.storage.ImageStorageException
import com.depromeet.piki.image.controller.dto.PresignedImageUploadResponse
import com.depromeet.piki.image.domain.ImageUploadException
import com.depromeet.piki.image.domain.ProductImageException
import com.depromeet.piki.item.domain.ItemException
import com.depromeet.piki.item.domain.ItemStatus
import com.depromeet.piki.product.domain.ProductLinkException
import com.depromeet.piki.tournament.controller.dto.AddTournamentItemFromLinkResponse
import com.depromeet.piki.tournament.controller.dto.AddTournamentItemsFromImagesResponse
import com.depromeet.piki.tournament.controller.dto.AddTournamentItemsFromWishResponse
import com.depromeet.piki.tournament.controller.dto.AddTournamentItemsRequest
import com.depromeet.piki.tournament.controller.dto.TournamentItemDetailResponse
import com.depromeet.piki.tournament.service.TournamentErrorCode
import com.depromeet.piki.tournament.service.TournamentException
import org.springdoc.core.customizers.OperationCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus

@Configuration
class TournamentItemApiExamples(
    private val openApiObjectMapper: OpenApiObjectMapper,
) {
    @Bean
    fun tournamentItemOpenApiExamples(): OperationCustomizer =
        OperationCustomizer { operation, handlerMethod ->
            when {
                handlerMethod.binds(TournamentItemController::addItemsFromWish) ->
                    operation.examples(openApiObjectMapper.delegate) {
                        add(
                            status = HttpStatus.OK,
                            name = "위시 아이템 추가 성공",
                            payload =
                                ApiResponseBody.ok(
                                    AddTournamentItemsFromWishResponse(tournamentItemIds = listOf(10L, 11L)),
                                ),
                        )
                        add(
                            status = HttpStatus.BAD_REQUEST,
                            name = "itemIds 개수 위반 (1~32개)",
                            payload =
                                ApiResponseBody.fail<Unit>(
                                    CommonErrorCode.INVALID_INPUT,
                                    // Bean Validation 위반은 GlobalExceptionHandler.detailOf 가 위반 필드의 메시지를 그대로 detail 로 내린다.
                                    detail = AddTournamentItemsRequest.ITEM_IDS_SIZE_MESSAGE,
                                ),
                        )
                        add(TournamentException.tooManyTournamentItems(), name = "아이템 최대 32개 초과")
                        unauthorized()
                        add(TournamentException.forbiddenTournament(), name = "토너먼트 권한 없음")
                        add(TournamentException.clonedTournamentCannotAddItems(), name = "플레이링크 복제 토너먼트에는 아이템 추가 불가")
                        add(TournamentException.itemNotInWishlist(), name = "위시리스트에 없는 아이템 포함")
                        add(TournamentException.notFoundTournament(), name = "토너먼트를 찾을 수 없음")
                        add(TournamentException.notFoundItems(), name = "존재하지 않는 아이템 포함")
                        add(TournamentException.notPendingTournament(), name = "PENDING 상태 아님")
                        add(TournamentException.duplicateTournamentItem(), name = "이미 등록된/중복 아이템")
                        add(TournamentException.itemNotReady(), name = "PENDING/PROCESSING/FAILED 등 미완료 상품 포함")
                    }

                handlerMethod.binds(TournamentItemController::addItemFromLink) ->
                    operation.examples(openApiObjectMapper.delegate) {
                        add(
                            status = HttpStatus.OK,
                            name = "링크 아이템 추가 성공",
                            payload = ApiResponseBody.ok(AddTournamentItemFromLinkResponse(tournamentItemId = 1L)),
                        )
                        add(ProductLinkException.invalidFormat(urlFormatCause), name = "유효하지 않은 URL 형식")
                        add(ProductLinkException.unsupportedScheme(), name = "https 외 스킴")
                        add(ProductLinkException.unsupportedPlatform(), name = "지원하지 않는 쇼핑몰 (차단 목록은 백오피스 도메인 접근 정책 기준)")
                        add(TournamentException.tooManyTournamentItems(), name = "아이템 최대 32개 초과")
                        unauthorized()
                        add(TournamentException.forbiddenTournament(), name = "토너먼트 권한 없음")
                        add(TournamentException.clonedTournamentCannotAddItems(), name = "플레이링크 복제 토너먼트에는 아이템 추가 불가")
                        add(TournamentException.notFoundTournament(), name = "토너먼트를 찾을 수 없음")
                        add(TournamentException.notPendingTournament(), name = "PENDING 상태 아님")
                        add(itemQuotaExceeded, name = "아이템 등록 한도 초과 (오너 몫에서 차감)")
                        add(capacityExceeded, name = "서비스 전체 가용량 소진")
                    }

                handlerMethod.binds(TournamentItemController::addItemsFromImages) ->
                    operation.examples(openApiObjectMapper.delegate) {
                        add(
                            status = HttpStatus.OK,
                            name = "이미지 아이템 추가 성공",
                            payload =
                                ApiResponseBody.ok(
                                    AddTournamentItemsFromImagesResponse(
                                        tournamentItemIds = listOf(1L, 2L, 3L),
                                    ),
                                ),
                        )
                        add(TournamentException.invalidImageCount(), name = "이미지 개수 위반 (1~5개)")
                        // ProductImage.of 의 형식 검증 3종 — S3 업로드 전에 동기로 거른다.
                        add(ProductImageException.emptyImage(), name = "빈 이미지 파일")
                        add(ProductImageException.unknownType(), name = "이미지 형식을 확인할 수 없음")
                        add(ProductImageException.unsupportedType(), name = "지원하지 않는 이미지 형식")
                        add(TournamentException.tooManyTournamentItems(), name = "아이템 최대 32개 초과")
                        unauthorized()
                        add(TournamentException.forbiddenTournament(), name = "토너먼트 권한 없음")
                        add(TournamentException.clonedTournamentCannotAddItems(), name = "플레이링크 복제 토너먼트에는 아이템 추가 불가")
                        add(TournamentException.notFoundTournament(), name = "토너먼트를 찾을 수 없음")
                        add(TournamentException.notPendingTournament(), name = "PENDING 상태 아님")
                        add(ImageStorageException.uploadFailed(), name = "이미지 저장 실패 (S3 업로드 장애)")
                        add(itemQuotaExceeded, name = "아이템 등록 한도 초과 (오너 몫에서 이미지 장수만큼 차감)")
                        add(capacityExceeded, name = "서비스 전체 가용량 소진")
                    }

                handlerMethod.binds(TournamentItemController::presignImageUploads) ->
                    operation.examples(openApiObjectMapper.delegate) {
                        add(
                            status = HttpStatus.OK,
                            name = "presigned 발급 성공 (다건)",
                            payload = ApiResponseBody.ok(presignedUploadsSample),
                        )
                        add(TournamentException.invalidImageCount(), name = "이미지 개수 위반 (1~5개)")
                        add(ProductImageException.unsupportedType(), name = "지원하지 않는 이미지 형식")
                        unauthorized()
                        add(TournamentException.forbiddenTournament(), name = "토너먼트 권한 없음")
                        add(TournamentException.clonedTournamentCannotAddItems(), name = "플레이링크 복제 토너먼트에는 아이템 추가 불가")
                        add(TournamentException.notFoundTournament(), name = "토너먼트를 찾을 수 없음")
                        add(TournamentException.notPendingTournament(), name = "PENDING 상태 아님")
                        add(ImageStorageException.presignFailed(), name = "presigned URL 발급 실패 (스토리지 장애)")
                        add(itemQuotaExceeded, name = "아이템 등록 한도 초과 (발급 시점에 오너 몫에서 차감)")
                        add(capacityExceeded, name = "서비스 전체 가용량 소진 (발급 시점에 확인)")
                    }

                handlerMethod.binds(TournamentItemController::confirmImageRegistration) ->
                    operation.examples(openApiObjectMapper.delegate) {
                        add(
                            status = HttpStatus.OK,
                            name = "이미지 아이템 추가 성공",
                            payload =
                                ApiResponseBody.ok(
                                    AddTournamentItemsFromImagesResponse(
                                        tournamentItemIds = listOf(1L, 2L, 3L),
                                    ),
                                ),
                        )
                        add(TournamentException.invalidImageCount(), name = "이미지 개수 위반 (1~5개)")
                        add(ImageUploadException.invalidKey(), name = "발급 형식이 아닌 key")
                        add(ImageUploadException.notUploaded(), name = "아직 업로드되지 않은 이미지")
                        add(TournamentException.tooManyTournamentItems(), name = "아이템 최대 32개 초과")
                        unauthorized()
                        add(TournamentException.forbiddenTournament(), name = "토너먼트 권한 없음")
                        add(TournamentException.clonedTournamentCannotAddItems(), name = "플레이링크 복제 토너먼트에는 아이템 추가 불가")
                        add(TournamentException.notFoundTournament(), name = "토너먼트를 찾을 수 없음")
                        add(TournamentException.notPendingTournament(), name = "PENDING 상태 아님")
                        add(ImageStorageException.existsCheckFailed(), name = "이미지 존재 확인 실패 (스토리지 장애)")
                    }

                handlerMethod.binds(TournamentItemController::updateItem) ->
                    operation.examples(openApiObjectMapper.delegate) {
                        add(
                            status = HttpStatus.OK,
                            name = "수기 수정 성공 (MANUAL 새 버전으로 교체)",
                            payload = ApiResponseBody.ok<Unit>(),
                        )
                        add(ItemException.nameRequiredForReady(), name = "병합 후에도 상품명 없음 (빈 항목에 일부 필드만 수정)")
                        add(ItemException.priceRequiredForReady(), name = "병합 후에도 가격 없음")
                        add(ItemException.imageRequiredForReady(), name = "병합 후에도 이미지 없음")
                        // ProductImage.of 의 형식 검증 3종 — S3 업로드 전에 동기로 거른다.
                        add(ProductImageException.emptyImage(), name = "빈 이미지 파일")
                        add(ProductImageException.unknownType(), name = "이미지 형식을 확인할 수 없음")
                        add(ProductImageException.unsupportedType(), name = "지원하지 않는 이미지 형식")
                        unauthorized()
                        add(TournamentException.forbiddenTournament(), name = "토너먼트 권한 없음")
                        add(TournamentException.clonedTournamentCannotModifyItems(), name = "플레이 링크 클론은 아이템 수정 불가")
                        add(TournamentException.notFoundTournament(), name = "토너먼트를 찾을 수 없음")
                        add(TournamentException.notFoundTournamentItem(), name = "토너먼트 아이템을 찾을 수 없음")
                        add(TournamentException.notPendingTournament(), name = "PENDING 상태 아님")
                        add(ImageStorageException.uploadFailed(), name = "이미지 저장 실패")
                    }

                handlerMethod.binds(TournamentItemController::deleteItem) ->
                    operation.examples(openApiObjectMapper.delegate) {
                        add(
                            status = HttpStatus.OK,
                            name = "아이템 삭제 성공",
                            payload = ApiResponseBody.ok<Unit>(),
                        )
                        unauthorized()
                        add(TournamentException.forbiddenTournament(), name = "토너먼트 권한 없음")
                        add(TournamentException.clonedTournamentCannotModifyItems(), name = "플레이 링크 클론은 아이템 삭제 불가")
                        add(TournamentException.notFoundTournament(), name = "토너먼트를 찾을 수 없음")
                        add(TournamentException.notFoundTournamentItem(), name = "토너먼트 아이템을 찾을 수 없음")
                        add(TournamentException.notPendingTournament(), name = "PENDING 상태 아님")
                    }

                handlerMethod.binds(TournamentItemController::getTournamentItem) ->
                    operation.examples(openApiObjectMapper.delegate) {
                        add(
                            status = HttpStatus.OK,
                            name = "READY - 파싱 완료 (링크 등록, 내 위시에 담긴 상품이라 memo 포함)",
                            payload =
                                ApiResponseBody.ok(
                                    TournamentItemDetailResponse(
                                        tournamentItemId = 10,
                                        itemId = 100,
                                        sourceUrl = "https://www.nike.com/kr/t/air-max/example",
                                        name = "나이키 에어맥스",
                                        imageUrl = "https://cdn.example.com/items/1.jpg",
                                        price = 129_000,
                                        currency = "KRW",
                                        status = ItemStatus.READY,
                                        memo = "생일 선물 후보",
                                    ),
                                ),
                        )
                        add(
                            status = HttpStatus.OK,
                            name = "READY - 파싱 완료 (이미지 등록, sourceUrl=null)",
                            payload =
                                ApiResponseBody.ok(
                                    TournamentItemDetailResponse(
                                        tournamentItemId = 11,
                                        itemId = 101,
                                        sourceUrl = null,
                                        name = "아디다스 울트라부스트",
                                        imageUrl = "https://cdn.example.com/items/2.jpg",
                                        price = 189_000,
                                        currency = "KRW",
                                        status = ItemStatus.READY,
                                    ),
                                ),
                        )
                        add(
                            status = HttpStatus.OK,
                            name = "PENDING - 파싱 대기 (URL 등록 직후)",
                            payload =
                                ApiResponseBody.ok(
                                    TournamentItemDetailResponse(
                                        tournamentItemId = 10,
                                        itemId = 100,
                                        sourceUrl = "https://www.nike.com/kr/t/air-max/example",
                                        name = null,
                                        imageUrl = null,
                                        price = null,
                                        currency = null,
                                        status = ItemStatus.PENDING,
                                    ),
                                ),
                        )
                        add(
                            status = HttpStatus.OK,
                            name = "PROCESSING - 파싱 진행 중",
                            payload =
                                ApiResponseBody.ok(
                                    TournamentItemDetailResponse(
                                        tournamentItemId = 10,
                                        itemId = 100,
                                        sourceUrl = "https://www.nike.com/kr/t/air-max/example",
                                        name = null,
                                        imageUrl = null,
                                        price = null,
                                        currency = null,
                                        status = ItemStatus.PROCESSING,
                                    ),
                                ),
                        )
                        add(
                            status = HttpStatus.OK,
                            name = "FAILED - 파싱 실패",
                            payload =
                                ApiResponseBody.ok(
                                    TournamentItemDetailResponse(
                                        tournamentItemId = 10,
                                        itemId = 100,
                                        sourceUrl = "https://www.nike.com/kr/t/air-max/example",
                                        name = null,
                                        imageUrl = null,
                                        price = null,
                                        currency = null,
                                        status = ItemStatus.FAILED,
                                    ),
                                ),
                        )
                        unauthorized()
                        add(TournamentException.forbiddenTournament(), name = "토너먼트 권한 없음")
                        add(TournamentException.notFoundTournament(), name = "토너먼트를 찾을 수 없음")
                        add(
                            TournamentException.notFoundTournamentItem(),
                            name = "토너먼트 아이템을 찾을 수 없음 · 아이템이 해당 토너먼트에 속하지 않음",
                        )
                    }
            }
            operation
        }

    // ProductLinkException.invalidFormat 은 cause 를 요구하지만, example 헬퍼는 message·category·status 만
    // 사용한다(GlobalExceptionHandler.handleBaseException 과 동일). 따라서 이 cause 는 payload 에 영향을 주지 않는 더미다.
    private val urlFormatCause = IllegalArgumentException("example")

    // 아이템 등록 한도 초과(#339). retryAfterSeconds 는 Retry-After 헤더로만 나가고 body 에는 실리지 않으므로
    // example payload 에 영향을 주지 않는다 — 문서상 대표값으로 15분을 넣는다.
    private val itemQuotaExceeded = ItemQuotaException.exceeded(TournamentErrorCode.ITEM_QUOTA_EXCEEDED, 900)

    // 전역 가용량 소진(#927). 오너의 몫과 무관하게 서비스 전체가 찬 상태라 503 이고, code 도 도메인이 아닌 공통이다.
    private val capacityExceeded = ItemQuotaException.capacityExceeded(900)

    // 이미지 등록 v2 presigned 발급 응답 샘플 — 위시와 동일 구조. uploadUrl 의 서명 쿼리스트링은 예시라 실제 값이 아니다.
    private val presignedUploadsSample =
        PresignedImageUploadResponse(
            uploads =
                listOf(
                    PresignedImageUploadResponse.PresignedImageUpload(
                        imageKey = "items/raw/550e8400-e29b-41d4-a716-446655440000.png",
                        uploadUrl =
                            "https://piki-images.s3.ap-northeast-2.amazonaws.com/items/raw/" +
                                "550e8400-e29b-41d4-a716-446655440000.png?X-Amz-Signature=EXAMPLE",
                        contentType = "image/png",
                    ),
                ),
        )
}
