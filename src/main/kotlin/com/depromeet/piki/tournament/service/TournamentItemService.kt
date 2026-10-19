package com.depromeet.piki.tournament.service

import com.depromeet.piki.common.ratelimit.ItemQuotaGuard
import com.depromeet.piki.common.storage.ImageStorage
import com.depromeet.piki.image.domain.ProductImage
import com.depromeet.piki.image.domain.ProductImageKey
import com.depromeet.piki.image.domain.UploadFormat
import com.depromeet.piki.image.service.ImagePresignService
import com.depromeet.piki.image.service.dto.PresignedRawUpload
import com.depromeet.piki.item.domain.ItemErrorCode
import com.depromeet.piki.item.service.ItemRegistrar
import com.depromeet.piki.item.service.UniqueConflictRetry
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.tournament.repository.TournamentRepository
import com.depromeet.piki.tournament.repository.TournamentUserRepository
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

@Service
class TournamentItemService(
    private val tournamentItemPersistenceService: TournamentItemPersistenceService,
    private val imageStorage: ImageStorage,
    private val imagePresignService: ImagePresignService,
    private val tournamentRepository: TournamentRepository,
    private val tournamentUserRepository: TournamentUserRepository,
    private val itemQuotaGuard: ItemQuotaGuard,
    private val itemRegistrar: ItemRegistrar,
) {
    // 등록 한도는 회원인 토너먼트 오너 몫에서 깎는다(#339). 요청자 기준이면 무한 발급되는 게스트로 갈아타며 한도를 리셋할 수 있다
    private fun ownerIdOf(tournamentId: Long): UUID {
        val tournament =
            tournamentRepository.findTournamentById(tournamentId)
                ?: throw TournamentException.notFoundTournament()
        val owner =
            tournamentUserRepository.findByIds(listOf(tournament.ownerTournamentUserId)).firstOrNull()
                // 토너먼트가 있으면 오너 TournamentUser 도 반드시 있다(create 가 함께 만든다) — 없으면 데이터 파손이다.
                ?: error("토너먼트 오너 행이 없다 (tournamentId=$tournamentId, ownerTournamentUserId=${tournament.ownerTournamentUserId})")
        return owner.userId
    }

    fun addItemFromLink(
        userId: UUID,
        tournamentId: Long,
        url: String,
    ): Long {
        // persist 안에서 정원까지 포함해 최종 판정을 다시 하므로 여기 검증은 사전 확인이다.
        tournamentItemPersistenceService.verifyCanAddItems(userId, tournamentId)
        val link = ProductLink.parse(url)
        tournamentItemPersistenceService.rejectIfAlreadyAdded(tournamentId, link)
        itemRegistrar.accept(link, ownerIdOf(tournamentId))
        return tournamentItemPersistenceService.persistLinkItem(userId, tournamentId, link)
    }

    // 발급 단계에선 tournament_item 을 만들지 않아 정원 최종 판정은 confirm 으로 미룬다.
    fun presignImageUploads(
        userId: UUID,
        tournamentId: Long,
        formats: List<UploadFormat>,
    ): List<PresignedRawUpload> {
        if (formats.size !in MIN_IMAGE_COUNT..MAX_IMAGE_COUNT) throw TournamentException.invalidImageCount()
        tournamentItemPersistenceService.verifyCanAddItems(userId, tournamentId)
        itemQuotaGuard.consume(ownerIdOf(tournamentId), formats.size, ItemErrorCode.QUOTA_EXCEEDED)
        return imagePresignService.presignProductUploads(formats)
    }

    fun confirmImageRegistration(
        userId: UUID,
        tournamentId: Long,
        imageKeys: List<ProductImageKey>,
    ): List<Long> {
        if (imageKeys.size !in MIN_IMAGE_COUNT..MAX_IMAGE_COUNT) throw TournamentException.invalidImageCount()
        tournamentItemPersistenceService.verifyCanAddItems(userId, tournamentId)
        imagePresignService.verifyUploaded(imageKeys)
        return tournamentItemPersistenceService.registerImages(imageKeys, userId, tournamentId)
    }

    // S3 업로드가 트랜잭션 안에 들어가지 않게 영속화만 applyUserInput 에 맡긴다
    fun updateItem(
        userId: UUID,
        tournamentId: Long,
        tournamentItemId: Long,
        name: String?,
        price: Int?,
        currency: String?,
        image: MultipartFile?,
    ) {
        val productImage = image?.let { ProductImage.of(it.bytes, it.contentType) }
        productImage?.let {
            tournamentItemPersistenceService
                .validateUserInput(userId, tournamentId, tournamentItemId, name, price, currency)
        }
        val imageUrl =
            productImage?.let {
                imageStorage.upload(it.bytes, "tournament-items/${UUID.randomUUID()}.${it.extension}", it.mimeType)
            }
        UniqueConflictRetry.once("tournamentItem=$tournamentItemId") {
            tournamentItemPersistenceService.applyUserInput(userId, tournamentId, tournamentItemId, name, price, imageUrl, currency)
        }
    }

    companion object {
        private const val MIN_IMAGE_COUNT = 1
        private const val MAX_IMAGE_COUNT = 5
    }
}
