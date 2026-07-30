package com.depromeet.piki.tournament.service

import com.depromeet.piki.common.storage.ImageStorage
import com.depromeet.piki.image.domain.PendingUpload
import com.depromeet.piki.image.domain.ProductImage
import com.depromeet.piki.image.service.ImagePresignService
import com.depromeet.piki.image.service.dto.PresignedRawUpload
import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.repository.ItemSnapshotRepository
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.routing.ExtractionRoutingPolicy
import com.depromeet.piki.tournament.repository.TournamentItemRepository
import com.depromeet.piki.tournament.repository.TournamentRepository
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

@Service
class TournamentItemService(
    private val tournamentItemPersistenceService: TournamentItemPersistenceService,
    private val extractionRoutingPolicy: ExtractionRoutingPolicy,
    private val imageStorage: ImageStorage,
    private val imagePresignService: ImagePresignService,
    private val tournamentRepository: TournamentRepository,
    private val tournamentItemRepository: TournamentItemRepository,
    private val itemSnapshotRepository: ItemSnapshotRepository,
) {
    fun addItemFromLink(
        userId: UUID,
        tournamentId: Long,
        url: String,
    ): Long {
        val link = ProductLink.parse(url)
        // fetch 불가 플랫폼(봇 차단)은 담아봐야 파싱이 무의미하게 실패한다 — 등록 시점에 막아 빠르게 안내한다(400).
        // 미지원 목록은 DB 정책(백오피스에서 배포 없이 변경)이 진다 — ExtractionRoutingPolicy 참고.
        extractionRoutingPolicy.verifyRegistrable(link)
        // URL 경로는 PENDING snapshot 을 커밋만 하고(작업 큐 적재) 즉시 반환한다. 파싱은 디스패처(@Scheduled)가
        // PENDING 을 집어 워커에 넘긴다 — @Async 유실과 무관하게 최소 1회는 claim 된다(at-least-once).
        // 파싱·상태 전이는 item PK 를, 클라이언트 응답은 tournament_item PK 를 쓴다 (PersistedTournamentItem).
        val persisted = tournamentItemPersistenceService.persistLinkItem(userId, tournamentId, link)
        return persisted.tournamentItemId
    }

    fun addItemsFromImages(
        userId: UUID,
        tournamentId: Long,
        images: List<MultipartFile>,
    ): List<Long> {
        if (images.size !in MIN_IMAGE_COUNT..MAX_IMAGE_COUNT) throw TournamentException.invalidImageCount()
        // 권한·상태·복제를 업로드 전에 미리 검증 — 거부될 요청이 S3 에 orphan raw 를 남기지 않게 한다(정원 동시성 최종 검증은 persist 의 FOR UPDATE).
        tournamentItemPersistenceService.verifyCanAddItems(userId, tournamentId)
        // 형식 검증(빈 바이트·미지원 MIME) — 실패 시 즉시 400. 유효한 이미지만 durable 적재한다.
        val productImages = images.map { ProductImage.of(it.bytes, it.contentType) }
        // 원본을 S3 raw 에 올려 입력을 durable 화한다(외부 호출, 트랜잭션 밖). 이 key 가 item 의 입력 정체성이 된다.
        val imageKeys = productImages.map { uploadRaw(it) }
        // 사전검증을 통과해도 정원은 persist 의 FOR UPDATE 가 최종 판정한다(동시 추가 race). 거기서 거부되면 방금 올린 raw 가
        // 어떤 item 에도 매이지 않은 orphan 으로 남고 워커가 영영 안 본다 — persist 실패 시 즉시 회수한다(best-effort, lifecycle 백업).
        // 파싱·상태 전이는 item PK 를, 클라이언트 응답은 tournament_item PK 를 쓴다 (PersistedTournamentItem).
        val persisted =
            runCatching { tournamentItemPersistenceService.persistPendingImageItems(userId, tournamentId, imageKeys) }
                .onFailure { imagePresignService.deleteRawsQuietly(imageKeys) }
                .getOrThrow()
        return persisted.map { it.tournamentItemId }
    }

    // 이미지 등록 v2 발급 — 클라가 S3 에 직접 올릴 presigned URL 을 발급한다(위시 presignImageUploads 와 동일 패턴).
    // v1(addItemsFromImages)이 서버로 바이트를 받아 S3 에 올리던 것을 클라→S3 직접 업로드로 바꿔 서버 대역·메모리를 아낀다.
    // 개수·권한(참여자·PENDING·비복제)을 사전 검증하고, content-type 검증·raw key 생성·presign 은 ImagePresignService 에 위임한다.
    // 발급은 pending_uploads 매핑만 남기고 tournament_item 을 만들지 않으므로 정원 최종 판정(persist 의 FOR UPDATE)은 confirm 으로 미룬다 — 여기선 사전 권한만 본다(v1 대칭).
    fun presignImageUploads(
        userId: UUID,
        tournamentId: Long,
        contentTypes: List<String>,
    ): List<PresignedRawUpload> {
        if (contentTypes.size !in MIN_IMAGE_COUNT..MAX_IMAGE_COUNT) throw TournamentException.invalidImageCount()
        tournamentItemPersistenceService.verifyCanAddItems(userId, tournamentId)
        return imagePresignService.presignRawUploads(contentTypes) { key, expiresAt ->
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
        imagePresignService.verifyUploaded(imageKeys)
        return tournamentItemPersistenceService
            .registerClaimedImages(imageKeys, userId, tournamentId)
            .map { it.tournamentItemId }
    }

    // 원본 이미지를 S3 raw prefix 에 올리고 그 object key 를 돌려준다(워커가 download(key)로 다시 읽는다). 파싱이 끝나면 워커가 회수한다.
    private fun uploadRaw(image: ProductImage): String {
        val key = "items/raw/${UUID.randomUUID()}.${image.extension}"
        imageStorage.upload(image.bytes, key, image.mimeType)
        return key
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
        if (!tournament.isPending()) throw TournamentException.notPendingTournament()
        val tournamentItem =
            tournamentItemRepository.findById(tournamentItemId)
                ?: throw TournamentException.notFoundTournamentItem()
        if (tournamentItem.tournamentId != tournamentId) throw TournamentException.notFoundTournamentItem()
        if (tournamentItem.userId != userId) throw TournamentException.forbiddenTournament()
        // 업로드 전 사전 검증(orphan 방지) — pin 버전과 병합해도 필수 필드가 비면 어차피 400 이므로 S3 전에 거른다.
        // 트랜잭션 밖 조회라 던지기 전용이고, 최종 판정은 manualEdit 이 다시 한다.
        val snapshotId = tournamentItem.snapshotId
        val snapshot =
            itemSnapshotRepository.findById(snapshotId)
                ?: error("snapshot 없음 — tournamentItemId=$tournamentItemId, snapshotId=$snapshotId")
        if (productImage == null) {
            ItemSnapshot.manual(base = snapshot, name = name, currentPrice = price, imageUrl = null, currency = currency, editedBy = userId)
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
