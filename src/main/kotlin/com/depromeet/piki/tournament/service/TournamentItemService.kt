package com.depromeet.piki.tournament.service

import com.depromeet.piki.common.ratelimit.ItemQuotaGuard
import com.depromeet.piki.common.storage.ImageStorage
import com.depromeet.piki.image.domain.PendingUpload
import com.depromeet.piki.image.domain.ProductImage
import com.depromeet.piki.image.domain.UploadFormat
import com.depromeet.piki.image.service.ImagePresignService
import com.depromeet.piki.image.service.dto.PresignedRawUpload
import com.depromeet.piki.item.domain.ItemErrorCode
import com.depromeet.piki.item.service.ItemRegistrar
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
    // 아이템 등록 비용은 요청자가 아니라 **토너먼트 오너**의 몫에서 깎는다(#339). 참여자에는 게스트가 섞이는데
    // 게스트 계정은 무한 발급되므로 요청자 기준으로 세면 계정을 갈아타며 한도를 리셋할 수 있다. 오너는 반드시
    // 회원이라(토너먼트 생성이 회원 전용) 그 우회가 성립하지 않는다.
    //
    // tournament 를 다시 조회하는 것은 verifyCanAddItems 와 중복이지만, 오너를 함께 돌려주도록 그 시그니처를
    // 바꾸면 호출부 전부가 쓰지도 않는 값을 받게 된다. 조회 1회 비용을 택했다.
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
        // 파싱·상태 전이는 item PK 를, 클라이언트 응답은 tournament_item PK 를 쓴다 (PersistedTournamentItem).
        val persisted = tournamentItemPersistenceService.persistLinkItem(userId, tournamentId, link)
        return persisted.tournamentItemId
    }

    // 발급 단계에선 tournament_item 을 만들지 않아 정원 최종 판정은 confirm 으로 미룬다.
    fun presignImageUploads(
        userId: UUID,
        tournamentId: Long,
        contentTypes: List<String>,
    ): List<PresignedRawUpload> {
        if (contentTypes.size !in MIN_IMAGE_COUNT..MAX_IMAGE_COUNT) throw TournamentException.invalidImageCount()
        tournamentItemPersistenceService.verifyCanAddItems(userId, tournamentId)
        val formats = contentTypes.map { UploadFormat.of(it) }
        itemQuotaGuard.consume(ownerIdOf(tournamentId), formats.size, ItemErrorCode.QUOTA_EXCEEDED)
        return imagePresignService.presignRawUploads(formats) { key, expiresAt ->
            PendingUpload.tournament(key, userId, tournamentId, expiresAt)
        }
    }

    fun confirmImageRegistration(
        userId: UUID,
        tournamentId: Long,
        imageKeys: List<String>,
    ): List<Long> {
        if (imageKeys.size !in MIN_IMAGE_COUNT..MAX_IMAGE_COUNT) throw TournamentException.invalidImageCount()
        tournamentItemPersistenceService.verifyCanAddItems(userId, tournamentId)
        imagePresignService.verifyUploaded(imageKeys)
        return tournamentItemPersistenceService
            .registerClaimedImages(imageKeys, userId, tournamentId)
            .map { it.tournamentItemId }
    }

    // recoverWishItem 과 동일한 패턴(#825 결정 4) — 수기 수정은 상태 무관 허용이며 MANUAL 새 버전 + pin 이동으로
    // 영속화한다(manualEdit). 이미지 형식 검증 후 S3 업로드는 트랜잭션 밖에서, 권한 검증·적재는 manualEdit 에 위임한다.
    // S3 업로드 전 병합 결과 필수 필드를 사전 확인해 orphan 업로드를 방지한다(최종 판정은 manualEdit).
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
        // 업로드 전 사전 검증(orphan 방지)은 이미지가 있을 때만 — 그게 dry-run 의 유일한 존재 이유라, 업로드가
        // 없는 수정은 manualEdit(락 안)의 최종 판정 하나로 충분하다(예외·응답 동일, 쿼리만 줄어든다).
        productImage?.let {
            tournamentItemPersistenceService
                .validateManualEdit(userId, tournamentId, tournamentItemId, name, price, currency)
        }
        val imageUrl =
            productImage?.let {
                imageStorage.upload(it.bytes, "tournament-items/${UUID.randomUUID()}.${it.extension}", it.mimeType)
            }
        tournamentItemPersistenceService.manualEdit(userId, tournamentId, tournamentItemId, name, price, imageUrl, currency)
    }

    companion object {
        private const val MIN_IMAGE_COUNT = 1
        private const val MAX_IMAGE_COUNT = 5
    }
}
