package com.depromeet.piki.tournament.service

import com.depromeet.piki.common.ratelimit.ItemQuotaGuard
import com.depromeet.piki.common.storage.ImageStorage
import com.depromeet.piki.image.domain.PendingUpload
import com.depromeet.piki.image.domain.ProductImage
import com.depromeet.piki.image.domain.UploadFormat
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.image.service.ImagePresignService
import com.depromeet.piki.image.service.dto.PresignedRawUpload
import com.depromeet.piki.item.domain.ItemErrorCode
import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.repository.ItemSnapshotRepository
import com.depromeet.piki.item.service.ItemRegistrar
import com.depromeet.piki.tournament.repository.TournamentItemRepository
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
    private val tournamentItemRepository: TournamentItemRepository,
    private val tournamentUserRepository: TournamentUserRepository,
    private val itemSnapshotRepository: ItemSnapshotRepository,
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

    // 이미지 등록 발급 — 클라가 S3 에 직접 올릴 presigned URL 을 발급한다(위시 presignImageUploads 와 동일 패턴).
    // 원본 바이트가 서버 메모리·대역을 경유하지 않는다.
    // 개수·권한(참여자·PENDING·비복제)을 사전 검증하고, content-type 검증·raw key 생성·presign 은 ImagePresignService 에 위임한다.
    // 발급은 pending_uploads 매핑만 남기고 tournament_item 을 만들지 않으므로 정원 최종 판정(persist 의 FOR UPDATE)은 confirm 으로 미룬다 — 여기선 사전 권한만 본다.
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

    // 이미지 등록 v2 확정(빠른 경로) — presigned 로 업로드를 마친 key 들을 받아 PENDING 아이템으로 적재한다.
    // 권한 사전검증 → key 형식·존재(HEAD) 검증 → pending_uploads claim(FOR UPDATE 삭제) + persist(정원 FOR UPDATE 최종 판정).
    // 폴링 백스톱과 같은 진입점이라 confirm 이 안 와도 폴링이 회수하고, 둘이 같은 key 를 다퉈도 claim 이 한쪽만 이긴다(멱등).
    // persist 실패 시 트랜잭션이 claim 을 롤백해 pending 이 남으므로 회수는 폴링에 맡긴다(raw 는 클라가 올린 것 + lifecycle 백업).
    fun confirmImageRegistration(
        userId: UUID,
        tournamentId: Long,
        imageKeys: List<String>,
    ): List<Long> {
        if (imageKeys.size !in MIN_IMAGE_COUNT..MAX_IMAGE_COUNT) throw TournamentException.invalidImageCount()
        tournamentItemPersistenceService.verifyCanAddItems(userId, tournamentId)
        // 한도는 여기서 차감하지 않는다 — 이 key 들은 presignImageUploads 에서 이미 차감된 몫이다(이중 차감 방지).
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
        val tournament =
            tournamentRepository.findTournamentById(tournamentId)
                ?: throw TournamentException.notFoundTournament()
        // 클론은 원본 아이템을 이어받을 뿐 소유 행이 없다 — effective-id 로 뚫으면 원본을 수정하게 되므로 막는다(#977).
        // 아이템 추가 금지(032)와 같은 결. 조회는 허용(getTournamentItem), 수정만 금지.
        tournament.sourceTournamentId?.let { throw TournamentException.clonedTournamentCannotModifyItems() }
        if (!tournament.isPending()) throw TournamentException.notPendingTournament()
        val tournamentItem =
            tournamentItemRepository.findById(tournamentItemId)
                ?: throw TournamentException.notFoundTournamentItem()
        if (tournamentItem.tournamentId != tournamentId) throw TournamentException.notFoundTournamentItem()
        if (tournamentItem.userId != userId) throw TournamentException.forbiddenTournament()
        // 업로드 전 사전 검증(orphan 방지) — 병합 결과가 400 이면 S3 전에 거른다. 이미지가 오면 업로드가 imageUrl 을
        // 채울 것이므로 자리표시 URL 로 그 자리만 메워 검증한다(저장 안 됨 — dry-run). 최종 판정은 manualEdit 이 다시 한다.
        val snapshotId = tournamentItem.snapshotId
        val snapshot =
            itemSnapshotRepository.findById(snapshotId)
                ?: error("snapshot 없음 — tournamentItemId=$tournamentItemId, snapshotId=$snapshotId")
        ItemSnapshot.manual(
            base = snapshot,
            name = name,
            price = price,
            imageUrl = productImage?.let { PRE_UPLOAD_VALIDATION_IMAGE_URL },
            currency = currency,
            editedBy = userId,
        )
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

// 수기 수정 사전 검증(dry-run)에서 업로드 예정 이미지 자리를 메우는 자리표시 값 — 저장되지 않는다.
private const val PRE_UPLOAD_VALIDATION_IMAGE_URL = "https://validation.invalid/pre-upload.png"
