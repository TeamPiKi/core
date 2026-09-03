package com.depromeet.piki.wishlist.controller

import com.depromeet.piki.item.domain.ItemErrorCode
import com.depromeet.piki.common.exception.AlreadyRegisteredException
import com.depromeet.piki.common.exception.CommonErrorCode
import com.depromeet.piki.common.openapi.OpenApiObjectMapper
import com.depromeet.piki.common.openapi.binds
import com.depromeet.piki.common.openapi.examples
import com.depromeet.piki.common.ratelimit.ItemQuotaException
import com.depromeet.piki.common.response.ApiResponseBody
import com.depromeet.piki.common.response.PageResponse
import com.depromeet.piki.common.storage.ImageStorageException
import com.depromeet.piki.image.controller.dto.PresignedImageUpload
import com.depromeet.piki.image.controller.dto.PresignedImageUploadResponse
import com.depromeet.piki.image.domain.ImageUploadException
import com.depromeet.piki.image.domain.ProductImageException
import com.depromeet.piki.item.domain.ItemException
import com.depromeet.piki.item.domain.ItemSnapshotSource
import com.depromeet.piki.item.domain.ItemStatus
import com.depromeet.piki.product.domain.ProductLinkException
import com.depromeet.piki.user.domain.UserException
import com.depromeet.piki.wishlist.controller.dto.WishDetailResponse
import com.depromeet.piki.wishlist.controller.dto.WishItemResponse
import com.depromeet.piki.wishlist.controller.dto.WishlistUpdateRequest
import com.depromeet.piki.wishlist.domain.WishErrorCode
import com.depromeet.piki.wishlist.domain.WishException
import org.springdoc.core.customizers.OperationCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import java.time.LocalDateTime

// 이 파일의 example 들이 함께 쓰는 위시 id. 목록·상세 example 의 위시와 중복 등록 409 가 가리키는 위시가
// 같은 값이어야 "이미 담긴 그 위시"라는 것이 문서에서 읽힌다.
private const val EXAMPLE_WISH_ID = 1024L

@Configuration
class WishlistApiExamples(
    private val openApiObjectMapper: OpenApiObjectMapper,
) {
    @Bean
    fun wishlistOpenApiExamples(): OperationCustomizer =
        OperationCustomizer { operation, handlerMethod ->
            if (handlerMethod.binds(WishlistController::registerFromUrl)) {
                operation.examples(openApiObjectMapper.delegate) {
                    add(
                        status = HttpStatus.CREATED,
                        name = "등록 접수 (파싱 대기 — PENDING)",
                        payload = ApiResponseBody.created(pendingRegistrationEntry),
                    )
                    add(
                        status = HttpStatus.CREATED,
                        name = "기존 값 재사용 (캐시 — reused=true, 낡은 값이라 새로 가져오기 권고)",
                        payload = ApiResponseBody.created(reusedRegistrationEntry),
                    )
                    add(ProductLinkException.invalidFormat(urlFormatCause), name = "유효하지 않은 URL 형식")
                    add(ProductLinkException.unsupportedScheme(), name = "https 외 스킴")
                    add(ProductLinkException.unsupportedPlatform(), name = "지원하지 않는 쇼핑몰 (차단 목록은 백오피스 도메인 접근 정책 기준)")
                    add(
                        AlreadyRegisteredException.wish(WishErrorCode.ALREADY_EXISTS, EXAMPLE_WISH_ID),
                        name = "이미 위시리스트에 등록된 상품 (공유 정체성 기준)",
                    )
                    unauthorized()
                    add(WishException.guestCannotUseWishlist(), name = "게스트의 위시리스트 이용 거부 (회원 전용)")
                    add(UserException.deletedUser(), name = "탈퇴한 유저")
                    add(itemQuotaExceeded, name = "아이템 등록 한도 초과")
                    add(capacityExceeded, name = "서비스 전체 가용량 소진")
                }
            }
            if (handlerMethod.binds(WishlistController::getWishlist)) {
                operation.examples(openApiObjectMapper.delegate) {
                    add(
                        status = HttpStatus.OK,
                        name = "조회 성공 (대기·담는 중 + 완성 혼재, 마지막 페이지)",
                        payload =
                            ApiResponseBody.ok(
                                data = listOf(pendingSampleEntry, processingSampleEntry, sampleEntry),
                                pageResponse = PageResponse(nextCursor = null, hasNext = false),
                            ),
                    )
                    add(
                        status = HttpStatus.OK,
                        name = "조회 성공 (다음 페이지 있음)",
                        payload =
                            ApiResponseBody.ok(
                                data = listOf(sampleEntry),
                                pageResponse = PageResponse(nextCursor = "1024", hasNext = true),
                            ),
                    )
                    add(
                        status = HttpStatus.OK,
                        name = "빈 위시리스트",
                        payload =
                            ApiResponseBody.ok(
                                data = emptyList<WishItemResponse>(),
                                pageResponse = PageResponse(nextCursor = null, hasNext = false),
                            ),
                    )
                    add(WishException.invalidCursor(), name = "유효하지 않은 cursor")
                    unauthorized()
                    add(WishException.guestCannotUseWishlist(), name = "게스트의 위시리스트 이용 거부 (회원 전용)")
                    add(UserException.deletedUser(), name = "탈퇴한 유저")
                }
            }
            if (handlerMethod.binds(WishlistController::getWish)) {
                operation.examples(openApiObjectMapper.delegate) {
                    add(
                        status = HttpStatus.OK,
                        name = "상세 조회 성공 (표시값 + 가격 이력)",
                        payload = ApiResponseBody.ok(wishDetailSample),
                    )
                    add(
                        status = HttpStatus.OK,
                        name = "본인이 직접 고친 항목 (입력값이 이력 맨 앞)",
                        payload = ApiResponseBody.ok(manualEditedDetailSample),
                    )
                    add(
                        status = HttpStatus.OK,
                        name = "아직 추출 성공 이력 없음 (빈 가격 이력)",
                        payload = ApiResponseBody.ok(emptyHistoryDetailSample),
                    )
                    add(WishException.guestCannotUseWishlist(), name = "게스트의 위시리스트 이용 거부 (회원 전용)")
                    add(UserException.deletedUser(), name = "탈퇴한 유저")
                    add(WishException.forbiddenWishItems(), name = "본인 위시 아님")
                    add(WishException.notFound(), name = "존재하지 않는 위시 항목")
                    unauthorized()
                }
            }
            if (handlerMethod.binds(WishlistController::recoverWishItem)) {
                operation.examples(openApiObjectMapper.delegate) {
                    add(
                        status = HttpStatus.OK,
                        name = "FAILED 보정 성공 (READY 로 복구)",
                        payload = ApiResponseBody.ok(sampleEntry),
                    )
                    add(
                        status = HttpStatus.OK,
                        name = "memo 만 수정 (버전을 쌓지 않음 — 표시값 그대로, 메모는 상세 조회로 확인)",
                        payload = ApiResponseBody.ok(sampleEntry),
                    )
                    add(
                        status = HttpStatus.BAD_REQUEST,
                        name = "가격 음수",
                        payload =
                            ApiResponseBody.fail<Unit>(
                                CommonErrorCode.INVALID_INPUT,
                                // @ModelAttribute Bean Validation 위반은 GlobalExceptionHandler.detailOf 가 위반 필드의 메시지를 그대로 detail 로 내린다.
                                detail = WishlistUpdateRequest.PRICE_MIN_MESSAGE,
                            ),
                    )
                    add(
                        status = HttpStatus.BAD_REQUEST,
                        name = "메모 길이 초과 (100자)",
                        payload =
                            ApiResponseBody.fail<Unit>(
                                CommonErrorCode.INVALID_INPUT,
                                detail = WishlistUpdateRequest.MEMO_MAX_MESSAGE,
                            ),
                    )
                    add(ItemException.nameRequiredForReady(), name = "병합 후에도 상품명 없음 (빈 항목에 일부 필드만 수정)")
                    add(ItemException.priceRequiredForReady(), name = "병합 후에도 가격 없음")
                    add(ItemException.imageRequiredForReady(), name = "병합 후에도 이미지 없음")
                    // ProductImage.of 의 형식 검증 3종 — S3 업로드 전에 동기로 거른다.
                    add(ProductImageException.emptyImage(), name = "빈 이미지 파일")
                    add(ProductImageException.unknownType(), name = "이미지 형식을 확인할 수 없음")
                    add(ProductImageException.unsupportedType(), name = "지원하지 않는 이미지 형식")
                    add(WishException.guestCannotUseWishlist(), name = "게스트의 위시리스트 이용 거부 (회원 전용)")
                    add(UserException.deletedUser(), name = "탈퇴한 유저")
                    add(WishException.forbiddenWishItems(), name = "본인 위시 아님")
                    add(WishException.notFound(), name = "존재하지 않는 위시 항목")
                    add(ImageStorageException.uploadFailed(), name = "이미지 저장 실패")
                    unauthorized()
                }
            }
            if (handlerMethod.binds(WishlistController::refreshWishItem)) {
                operation.examples(openApiObjectMapper.delegate) {
                    add(
                        status = HttpStatus.OK,
                        name = "새로고침 접수 (새 추출 버전 — PENDING)",
                        payload = ApiResponseBody.ok(pendingSampleEntry),
                    )
                    add(
                        status = HttpStatus.OK,
                        name = "이미 새로고침 진행 중 (멱등 — 현재 진행 상태)",
                        payload = ApiResponseBody.ok(processingSampleEntry),
                    )
                    add(WishException.notRefreshable(), name = "링크 없는 항목(이미지 등록) — 새로고침 불가")
                    add(WishException.failedNotRefreshable(), name = "추출 실패(FAILED) 항목 — 보정으로 복구")
                    add(WishException.guestCannotUseWishlist(), name = "게스트의 위시리스트 이용 거부 (회원 전용)")
                    add(UserException.deletedUser(), name = "탈퇴한 유저")
                    add(WishException.forbiddenWishItems(), name = "본인 위시 아님")
                    add(WishException.notFound(), name = "존재하지 않는 위시 항목")
                    unauthorized()
                    add(itemQuotaExceeded, name = "아이템 등록 한도 초과")
                    add(capacityExceeded, name = "서비스 전체 가용량 소진")
                }
            }
            if (handlerMethod.binds(WishlistController::deleteWish)) {
                operation.examples(openApiObjectMapper.delegate) {
                    add(
                        status = HttpStatus.OK,
                        name = "삭제 성공",
                        payload = ApiResponseBody.ok<Unit>(),
                    )
                    add(WishException.guestCannotUseWishlist(), name = "게스트의 위시리스트 이용 거부 (회원 전용)")
                    add(UserException.deletedUser(), name = "탈퇴한 유저")
                    add(WishException.forbiddenWishItems(), name = "본인 위시 아님")
                    unauthorized()
                }
            }
            if (handlerMethod.binds(WishlistController::deleteWishes)) {
                operation.examples(openApiObjectMapper.delegate) {
                    add(
                        status = HttpStatus.OK,
                        name = "다중 삭제 성공",
                        payload = ApiResponseBody.ok<Unit>(),
                    )
                    add(WishException.invalidIdCount(), name = "ids 누락/빈 목록/100개 초과")
                    add(WishException.guestCannotUseWishlist(), name = "게스트의 위시리스트 이용 거부 (회원 전용)")
                    add(UserException.deletedUser(), name = "탈퇴한 유저")
                    add(WishException.forbiddenWishItems(), name = "본인 위시 아닌 항목 포함")
                    unauthorized()
                }
            }
            if (handlerMethod.binds(WishlistController::presignImageUploads)) {
                operation.examples(openApiObjectMapper.delegate) {
                    add(
                        status = HttpStatus.OK,
                        name = "presigned 발급 성공 (다건)",
                        payload = ApiResponseBody.ok(presignedUploadsSample),
                    )
                    add(WishException.invalidImageCount(), name = "이미지 개수 위반 (1~5개 아님)")
                    add(ProductImageException.unsupportedType(), name = "지원하지 않는 이미지 형식")
                    add(ImageStorageException.presignFailed(), name = "presigned URL 발급 실패 (스토리지 장애)")
                    unauthorized()
                    add(WishException.guestCannotUseWishlist(), name = "게스트의 위시리스트 이용 거부 (회원 전용)")
                    add(UserException.deletedUser(), name = "탈퇴한 유저")
                    add(itemQuotaExceeded, name = "아이템 등록 한도 초과 (발급 시점에 장수만큼 소모)")
                    add(capacityExceeded, name = "서비스 전체 가용량 소진 (발급 시점에 확인)")
                }
            }
            if (handlerMethod.binds(WishlistController::confirmImageRegistration)) {
                operation.examples(openApiObjectMapper.delegate) {
                    add(
                        status = HttpStatus.CREATED,
                        name = "이미지 등록 접수 (PENDING, 다건)",
                        payload = ApiResponseBody.created(imagePendingEntries),
                    )
                    add(WishException.invalidImageCount(), name = "이미지 개수 위반 (1~5개 아님)")
                    add(ImageUploadException.invalidKey(), name = "발급 형식이 아닌 key")
                    add(ImageUploadException.notUploaded(), name = "아직 업로드되지 않은 이미지")
                    add(ImageStorageException.existsCheckFailed(), name = "이미지 존재 확인 실패 (스토리지 장애)")
                    unauthorized()
                    add(WishException.guestCannotUseWishlist(), name = "게스트의 위시리스트 이용 거부 (회원 전용)")
                    add(UserException.deletedUser(), name = "탈퇴한 유저")
                }
            }
            operation
        }

    // ProductLinkException.invalidFormat 은 cause 를 요구하지만, example 헬퍼는 message·category·status 만
    // 사용한다(GlobalExceptionHandler.handleBaseException 과 동일). 따라서 이 cause 는 payload 에 영향을 주지 않는 더미다.
    private val urlFormatCause = IllegalArgumentException("example")

    // 아이템 등록 한도 초과(#339). retryAfterSeconds 는 Retry-After 헤더로만 나가고 body 에는 실리지 않으므로
    // example payload 에 영향을 주지 않는다 — 문서상 대표값으로 15분을 넣는다.
    private val itemQuotaExceeded = ItemQuotaException.exceeded(ItemErrorCode.QUOTA_EXCEEDED, 900)

    // 전역 가용량 소진(#927). 요청자의 몫과 무관하게 서비스 전체가 찬 상태라 503 이고, code 도 도메인이 아닌 공통이다.
    private val capacityExceeded = ItemQuotaException.capacityExceeded(900)

    // 상세 조회 — 지금 보이는 값(item)과 그 상품의 가격 기록. 서버 추출값 사이에 타인이 넣은 수기(89,000원)가 섞여 있어,
    // source·editedByMe 로 구분해 그리는 예시다.
    private val wishDetailSample =
        WishDetailResponse(
            wish =
                WishItemResponse.WishView(
                    id = EXAMPLE_WISH_ID,
                    createdAt = LocalDateTime.of(2026, 5, 21, 10, 0, 0),
                ),
            memo = "생일 선물 후보",
            item =
                WishItemResponse.ItemView(
                    id = 512,
                    status = ItemStatus.READY,
                    source = ItemSnapshotSource.SERVER,
                    name = "에어 조던 1 미드",
                    price = 109_000,
                    currency = "KRW",
                    imageUrl = "https://cdn.example.com/p/512.jpg",
                    sourceUrl = "https://www.example-shop.com/products/12345",
                    // 백오피스 미등록 도메인이라 host 에서 유도한 임시 표시명(fallback)이 나가는 예시.
                    sourcePlatform = "example-shop",
                ),
            priceHistory =
                listOf(
                    WishDetailResponse.PriceHistoryEntry(
                        price = 109_000,
                        extractedAt = LocalDateTime.of(2026, 6, 18, 10, 0, 0),
                        source = ItemSnapshotSource.SERVER,
                        editedByMe = null,
                    ),
                    // 같은 상품을 담은 다른 사용자가 넣은 값 — 어떤 조건에서 본 가격인지 알 수 없어 구분해 다룬다.
                    WishDetailResponse.PriceHistoryEntry(
                        price = 89_000,
                        extractedAt = LocalDateTime.of(2026, 6, 10, 9, 0, 0),
                        source = ItemSnapshotSource.MANUAL,
                        editedByMe = false,
                    ),
                    WishDetailResponse.PriceHistoryEntry(
                        price = 115_000,
                        extractedAt = LocalDateTime.of(2026, 6, 2, 10, 0, 0),
                        source = ItemSnapshotSource.SERVER_LLM,
                        editedByMe = null,
                    ),
                    WishDetailResponse.PriceHistoryEntry(
                        price = 119_000,
                        extractedAt = LocalDateTime.of(2026, 5, 21, 10, 0, 0),
                        source = ItemSnapshotSource.SERVER,
                        editedByMe = null,
                    ),
                ),
        )

    // 본인이 직접 고친 항목 — 표시값(99,000원)은 본인 입력값이고 이력 맨 앞에도 온다.
    // 반면 타인 수기(89,000원)는 이력에 남아 있어도 표시값이 되지 않는다(맥락 스코프) — 그래서 이력의 순서만으로
    // 표시값을 유추할 수 없고, 지금 보이는 값은 item 에서 읽어야 한다.
    private val manualEditedDetailSample =
        wishDetailSample.copy(
            item =
                wishDetailSample.item.copy(
                    source = ItemSnapshotSource.MANUAL,
                    price = 99_000,
                ),
            priceHistory =
                listOf(
                    WishDetailResponse.PriceHistoryEntry(
                        price = 99_000,
                        extractedAt = LocalDateTime.of(2026, 6, 20, 14, 30, 0),
                        source = ItemSnapshotSource.MANUAL,
                        editedByMe = true,
                    ),
                ) + wishDetailSample.priceHistory,
        )

    // 아직 추출에 한 번도 성공하지 못한 상품 — 추출 진행 중이라 표시값도 비어 있고 이력도 없다.
    // 클라는 status 로 "기다린다(PENDING·PROCESSING)" 와 "그만 기다린다(FAILED)" 를 가른다.
    private val emptyHistoryDetailSample =
        WishDetailResponse(
            wish =
                WishItemResponse.WishView(
                    id = 1027,
                    createdAt = LocalDateTime.of(2026, 5, 21, 10, 11, 0),
                ),
            memo = null,
            item =
                WishItemResponse.ItemView(
                    id = 515,
                    status = ItemStatus.PROCESSING,
                    source = null,
                    name = null,
                    price = null,
                    currency = null,
                    imageUrl = null,
                    sourceUrl = "https://www.example-shop.com/products/67891",
                    sourcePlatform = "example-shop",
                ),
            priceHistory = emptyList(),
        )

    // 파싱이 끝난 완성 항목 (READY).
    private val sampleEntry =
        WishItemResponse(
            wish =
                WishItemResponse.WishView(
                    id = EXAMPLE_WISH_ID,
                    createdAt = LocalDateTime.of(2026, 5, 21, 10, 0, 0),
                ),
            item =
                WishItemResponse.ItemView(
                    id = 512,
                    status = ItemStatus.READY,
                    source = ItemSnapshotSource.SERVER,
                    name = "에어 조던 1 미드",
                    price = 119_000,
                    currency = "KRW",
                    imageUrl = "https://cdn.example.com/p/512.jpg",
                    sourceUrl = "https://www.example-shop.com/products/12345",
                    // 백오피스 미등록 도메인이라 host 에서 유도한 임시 표시명(fallback)이 나가는 예시.
                    sourcePlatform = "example-shop",
                ),
        )

    // URL 등록 직후 항목 (PENDING) — link 만 있고 name·가격·이미지는 비어 있다. 디스패처가 집어 PROCESSING 으로 전이한다.
    private val pendingSampleEntry =
        WishItemResponse(
            wish =
                WishItemResponse.WishView(
                    id = 1027,
                    createdAt = LocalDateTime.of(2026, 5, 21, 10, 11, 0),
                ),
            item =
                WishItemResponse.ItemView(
                    id = 515,
                    status = ItemStatus.PENDING,
                    source = null,
                    name = null,
                    price = null,
                    currency = null,
                    imageUrl = null,
                    sourceUrl = "https://www.example-shop.com/products/67891",
                    sourcePlatform = "example-shop",
                ),
        )

    // URL 등록 응답 전용(#853) — 등록 경로는 attach 메타(reused·refreshNeeded)가 non-null 로 내려간다.
    // 새 파싱을 만든 등록: 캐시에 붙지 않았으므로 둘 다 false.
    private val pendingRegistrationEntry = pendingSampleEntry.copy(reused = false, refreshNeeded = false)

    // 파싱 없이 다른 등록의 완성 값(캐시)에 붙은 등록 — 값이 갱신 권고 임계(24h)보다 낡아 refreshNeeded=true.
    // 클라는 "새로운 정보로 가져올까요?" 를 물어 사용자가 원하면 새로고침 API 를 호출한다.
    private val reusedRegistrationEntry = sampleEntry.copy(reused = true, refreshNeeded = true)

    // 파싱 진행 중 항목 (PROCESSING) — 디스패처가 집어 추출 중인 상태. 목록·단건 조회에서 등장한다.
    private val processingSampleEntry =
        WishItemResponse(
            wish =
                WishItemResponse.WishView(
                    id = 1026,
                    createdAt = LocalDateTime.of(2026, 5, 21, 10, 10, 0),
                ),
            item =
                WishItemResponse.ItemView(
                    id = 514,
                    status = ItemStatus.PROCESSING,
                    source = null,
                    name = null,
                    price = null,
                    currency = null,
                    imageUrl = null,
                    sourceUrl = "https://www.example-shop.com/products/67890",
                    sourcePlatform = "example-shop",
                ),
        )

    // 이미지 등록 v2 presigned 발급 응답 샘플 — 클라는 각 uploadUrl 로 contentType 을 Content-Type 헤더에 실어 S3 에 직접 PUT 한 뒤
    // imageKey 들을 confirm 으로 되돌려준다. uploadUrl 의 서명 쿼리스트링은 예시라 실제 값이 아니다.
    private val presignedUploadsSample =
        PresignedImageUploadResponse(
            uploads =
                listOf(
                    PresignedImageUpload(
                        imageKey = "items/raw/550e8400-e29b-41d4-a716-446655440000.png",
                        uploadUrl =
                            "https://piki-images.s3.ap-northeast-2.amazonaws.com/items/raw/" +
                                "550e8400-e29b-41d4-a716-446655440000.png?X-Amz-Signature=EXAMPLE",
                        contentType = "image/png",
                    ),
                    PresignedImageUpload(
                        imageKey = "items/raw/7c9e6679-7425-40de-944b-e07fc1f90ae7.jpg",
                        uploadUrl =
                            "https://piki-images.s3.ap-northeast-2.amazonaws.com/items/raw/" +
                                "7c9e6679-7425-40de-944b-e07fc1f90ae7.jpg?X-Amz-Signature=EXAMPLE",
                        contentType = "image/jpeg",
                    ),
                ),
        )

    // 이미지 등록 직후 항목들 — link 도 없이 imageKey 로 작업 큐 적재된 PENDING(sourceUrl=null). 디스패처가 집어 PROCESSING→READY/FAILED 로 전이한다.
    private val imagePendingEntries =
        listOf(
            WishItemResponse(
                wish =
                    WishItemResponse.WishView(
                        id = 1025,
                        createdAt = LocalDateTime.of(2026, 5, 21, 10, 5, 0),
                    ),
                item =
                    WishItemResponse.ItemView(
                        id = 513,
                        status = ItemStatus.PENDING,
                        source = null,
                        name = null,
                        price = null,
                        currency = null,
                        imageUrl = null,
                        sourceUrl = null,
                        sourcePlatform = null,
                    ),
            ),
            WishItemResponse(
                wish =
                    WishItemResponse.WishView(
                        id = 1027,
                        createdAt = LocalDateTime.of(2026, 5, 21, 10, 5, 0),
                    ),
                item =
                    WishItemResponse.ItemView(
                        id = 515,
                        status = ItemStatus.PENDING,
                        source = null,
                        name = null,
                        price = null,
                        currency = null,
                        imageUrl = null,
                        sourceUrl = null,
                        sourcePlatform = null,
                    ),
            ),
        )
}
