package com.depromeet.piki.tournament.service

import com.depromeet.piki.common.ratelimit.ItemQuotaGuard
import com.depromeet.piki.common.storage.ImageStorage
import com.depromeet.piki.image.domain.PendingUpload
import com.depromeet.piki.image.domain.ProductImage
import com.depromeet.piki.image.service.ImagePresignService
import com.depromeet.piki.image.service.dto.PresignedRawUpload
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.routing.DomainAccessPolicy
import com.depromeet.piki.tournament.repository.TournamentRepository
import com.depromeet.piki.tournament.repository.TournamentUserRepository
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

@Service
class TournamentItemService(
    private val tournamentItemPersistenceService: TournamentItemPersistenceService,
    private val accessPolicy: DomainAccessPolicy,
    private val imageStorage: ImageStorage,
    private val imagePresignService: ImagePresignService,
    private val tournamentRepository: TournamentRepository,
    private val tournamentUserRepository: TournamentUserRepository,
    private val itemQuotaGuard: ItemQuotaGuard,
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
        val link = ProductLink.parse(url)
        // fetch 불가 플랫폼(봇 차단)은 담아봐야 파싱이 무의미하게 실패한다 — 등록 시점에 막아 빠르게 안내한다(400).
        // 미지원 목록은 DB 정책(백오피스에서 배포 없이 변경)이 진다 — DomainAccessPolicy 참고.
        accessPolicy.verifyRegistrable(link)
        // 권한·상태를 차감 전에 확인한다(이미지 경로와 같은 이유) — 참여자도 아닌 요청이 오너의 몫을 깎으면 안 된다.
        // persist 안에서 정원까지 포함해 최종 판정을 다시 하므로 여기 검증은 사전 확인이다.
        tournamentItemPersistenceService.verifyCanAddItems(userId, tournamentId)
        // 이미 담긴 링크면 차감 전에 거른다(#973) — 추가되지 않을 요청이 오너의 몫을 깎으면 안 된다.
        // 특히 응답이 유실된 뒤의 재시도가 이 경로로 들어오는데, 그때마다 몫을 잃으면 담지도 못한 채 한도만 소모된다.
        tournamentItemPersistenceService.rejectIfAlreadyAdded(tournamentId, link)
        itemQuotaGuard.consume(ownerIdOf(tournamentId), 1, TournamentErrorCode.ITEM_QUOTA_EXCEEDED)
        // URL 경로는 PENDING snapshot 을 커밋만 하고(작업 큐 적재) 즉시 반환한다. 파싱은 디스패처(@Scheduled)가
        // PENDING 을 집어 워커에 넘긴다 — @Async 유실과 무관하게 최소 1회는 claim 된다(at-least-once).
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
        // content-type 검증을 차감 앞으로 당긴다 — 지원하지 않는 MIME 을 보낸 요청이 오너의 몫을 깎고 400 을 받지 않게 한다
        // (위시 presignImageUploads 와 같은 순서).
        contentTypes.forEach { ProductImage.extensionForMimeType(it) }
        // 위시 v2 와 같은 이유로 발급 시점에 차감한다 — confirm 이 안 와도 폴링 백스톱이 pending 을 회수해 큐에 넣으므로,
        // confirm 에서만 세면 그 경로가 한도를 우회한다. confirm 은 차감하지 않는다(이중 차감 방지).
        itemQuotaGuard.consume(ownerIdOf(tournamentId), contentTypes.size, TournamentErrorCode.ITEM_QUOTA_EXCEEDED)
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
