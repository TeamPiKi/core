package com.depromeet.piki.folder.controller

import com.depromeet.piki.common.openapi.OpenApiObjectMapper
import com.depromeet.piki.common.openapi.binds
import com.depromeet.piki.common.openapi.examples
import com.depromeet.piki.common.response.ApiResponseBody
import com.depromeet.piki.folder.controller.dto.FolderMoveResponse
import com.depromeet.piki.folder.controller.dto.FolderResponse
import com.depromeet.piki.folder.controller.dto.FolderWishCountResponse
import com.depromeet.piki.folder.domain.FolderException
import com.depromeet.piki.user.domain.UserException
import com.depromeet.piki.wishlist.domain.WishException
import org.springdoc.core.customizers.OperationCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import java.time.LocalDateTime

@Configuration
class FolderApiExamples(
    private val openApiObjectMapper: OpenApiObjectMapper,
) {
    @Bean
    fun folderOpenApiExamples(): OperationCustomizer =
        OperationCustomizer { operation, handlerMethod ->
            if (handlerMethod.binds(FolderController::create)) {
                operation.examples(openApiObjectMapper.delegate) {
                    add(status = HttpStatus.CREATED, name = "생성 성공 (빈 폴더)", payload = ApiResponseBody.created(newFolder))
                    add(FolderException.invalidName(), name = "이름이 비었거나 20자 초과")
                    unauthorized()
                    add(WishException.guestCannotUseWishlist(), name = "게스트 거부 (회원 전용)")
                    add(UserException.deletedUser(), name = "탈퇴한 유저")
                }
            }
            if (handlerMethod.binds(FolderController::getFolders)) {
                operation.examples(openApiObjectMapper.delegate) {
                    add(status = HttpStatus.OK, name = "조회 성공 (생성순)", payload = ApiResponseBody.ok(listOf(coatFolder, giftFolder, newFolder)))
                    add(status = HttpStatus.OK, name = "폴더 없음", payload = ApiResponseBody.ok(emptyList<FolderResponse>()))
                    unauthorized()
                    add(WishException.guestCannotUseWishlist(), name = "게스트 거부 (회원 전용)")
                    add(UserException.deletedUser(), name = "탈퇴한 유저")
                }
            }
            if (handlerMethod.binds(FolderController::rename)) {
                operation.examples(openApiObjectMapper.delegate) {
                    add(status = HttpStatus.OK, name = "변경 성공", payload = ApiResponseBody.ok(coatFolder.copy(name = "코트")))
                    add(FolderException.invalidName(), name = "이름이 비었거나 20자 초과")
                    unauthorized()
                    add(WishException.guestCannotUseWishlist(), name = "게스트 거부 (회원 전용)")
                    add(FolderException.notFound(), name = "없는 폴더 · 남의 폴더")
                    add(UserException.deletedUser(), name = "탈퇴한 유저")
                }
            }
            if (handlerMethod.binds(FolderController::delete)) {
                operation.examples(openApiObjectMapper.delegate) {
                    add(status = HttpStatus.OK, name = "삭제 성공", payload = ApiResponseBody.ok<Unit>())
                    unauthorized()
                    add(WishException.guestCannotUseWishlist(), name = "게스트 거부 (회원 전용)")
                    add(FolderException.notFound(), name = "없는 폴더 · 남의 폴더")
                    add(UserException.deletedUser(), name = "탈퇴한 유저")
                }
            }
            if (handlerMethod.binds(FolderController::putWishes)) {
                operation.examples(openApiObjectMapper.delegate) {
                    add(status = HttpStatus.OK, name = "넣기 성공", payload = ApiResponseBody.ok(FolderWishCountResponse(folderId = COAT_FOLDER_ID, wishCount = 10)))
                    add(FolderException.invalidWishCount(), name = "wishIds 가 비었거나 100개 초과")
                    unauthorized()
                    add(WishException.guestCannotUseWishlist(), name = "게스트 거부 (회원 전용)")
                    add(WishException.forbiddenWishItems(), name = "남의 위시나 지운 위시 포함")
                    add(FolderException.notFound(), name = "없는 폴더 · 남의 폴더")
                    add(UserException.deletedUser(), name = "탈퇴한 유저")
                }
            }
            if (handlerMethod.binds(FolderController::takeOutWishes)) {
                operation.examples(openApiObjectMapper.delegate) {
                    add(status = HttpStatus.OK, name = "빼기 성공", payload = ApiResponseBody.ok(FolderWishCountResponse(folderId = COAT_FOLDER_ID, wishCount = 8)))
                    add(FolderException.invalidWishCount(), name = "wishIds 가 비었거나 100개 초과")
                    unauthorized()
                    add(WishException.guestCannotUseWishlist(), name = "게스트 거부 (회원 전용)")
                    add(FolderException.notFound(), name = "없는 폴더 · 남의 폴더")
                    add(UserException.deletedUser(), name = "탈퇴한 유저")
                }
            }
            if (handlerMethod.binds(FolderController::moveWishes)) {
                operation.examples(openApiObjectMapper.delegate) {
                    add(
                        status = HttpStatus.OK,
                        name = "이동 성공",
                        payload =
                            ApiResponseBody.ok(
                                FolderMoveResponse(folderId = COAT_FOLDER_ID, wishCount = 6, targetFolderId = GIFT_FOLDER_ID, targetWishCount = 3),
                            ),
                    )
                    add(FolderException.invalidWishCount(), name = "wishIds 가 비었거나 100개 초과")
                    add(FolderException.sameFolderMove(), name = "이 폴더와 대상 폴더가 같음")
                    unauthorized()
                    add(WishException.guestCannotUseWishlist(), name = "게스트 거부 (회원 전용)")
                    add(FolderException.notFound(), name = "이 폴더나 대상 폴더가 없음 · 남의 폴더")
                    add(UserException.deletedUser(), name = "탈퇴한 유저")
                }
            }
            operation
        }

    private val coatFolder =
        FolderResponse(
            id = COAT_FOLDER_ID,
            name = "겨울 아우터",
            wishCount = 7,
            coverImageUrls =
                listOf(
                    "https://cdn.example.com/p/701.jpg",
                    "https://cdn.example.com/p/698.jpg",
                    "https://cdn.example.com/p/690.jpg",
                ),
            createdAt = LocalDateTime.of(2026, 10, 20, 13, 2, 11),
        )

    // 위시가 하나뿐이라 표지도 한 장
    private val giftFolder =
        FolderResponse(
            id = GIFT_FOLDER_ID,
            name = "선물",
            wishCount = 1,
            coverImageUrls = listOf("https://cdn.example.com/p/655.jpg"),
            createdAt = LocalDateTime.of(2026, 10, 21, 9, 10, 0),
        )

    private val newFolder =
        FolderResponse(
            id = 16,
            name = "가방 위시템",
            wishCount = 0,
            coverImageUrls = emptyList(),
            createdAt = LocalDateTime.of(2026, 11, 2, 10, 52, 31),
        )

    companion object {
        private const val COAT_FOLDER_ID = 12L
        private const val GIFT_FOLDER_ID = 15L
    }
}
