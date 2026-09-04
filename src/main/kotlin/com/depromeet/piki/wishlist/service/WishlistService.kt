package com.depromeet.piki.wishlist.service

import com.depromeet.piki.common.ratelimit.ItemQuotaGuard
import com.depromeet.piki.common.storage.ImageStorage
import com.depromeet.piki.image.domain.PendingUpload
import com.depromeet.piki.image.domain.ProductImage
import com.depromeet.piki.image.domain.UploadFormat
import com.depromeet.piki.image.service.ImagePresignService
import com.depromeet.piki.image.service.dto.PresignedRawUpload
import com.depromeet.piki.item.domain.ItemErrorCode
import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.repository.ItemRepository
import com.depromeet.piki.item.repository.ItemSnapshotRepository
import com.depromeet.piki.item.service.ItemDisplayService
import com.depromeet.piki.item.service.ItemRegistrar
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.user.service.UserService
import com.depromeet.piki.wishlist.domain.WishCursor
import com.depromeet.piki.wishlist.domain.WishDeleteIds
import com.depromeet.piki.wishlist.domain.WishErrorCode
import com.depromeet.piki.wishlist.domain.WishException
import com.depromeet.piki.wishlist.domain.WishlistSize
import com.depromeet.piki.wishlist.repository.WishRepository
import com.depromeet.piki.wishlist.service.dto.WishDetail
import com.depromeet.piki.wishlist.service.dto.WishWithItem
import com.depromeet.piki.wishlist.service.dto.WishlistPage
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

@Service
class WishlistService(
    private val wishPersistenceService: WishPersistenceService,
    private val itemRegistrar: ItemRegistrar,
    private val imageStorage: ImageStorage,
    private val imagePresignService: ImagePresignService,
    private val wishRepository: WishRepository,
    private val itemRepository: ItemRepository,
    private val itemSnapshotRepository: ItemSnapshotRepository,
    private val itemDisplayService: ItemDisplayService,
    private val itemQuotaGuard: ItemQuotaGuard,
    private val userService: UserService,
) {

    // TODO AOP
    private fun requireMember(userId: UUID) {
        val user = userService.findActiveById(userId)
        if (user.identityType != IdentityType.MEMBER) throw WishException.guestCannotUseWishlist()
    }

    fun registerFromUrl(
        rawUrl: String,
        userId: UUID,
    ): WishWithItem {
        requireMember(userId)
        val link = ProductLink.parse(rawUrl)
        wishPersistenceService.rejectIfAlreadyRegistered(userId, link)
        itemRegistrar.accept(link, userId)
        return wishPersistenceService.persist(userId, link)
    }

    fun presignImageUploads(
        contentTypes: List<String>,
        userId: UUID,
    ): List<PresignedRawUpload> {
        requireMember(userId)
        if (contentTypes.size !in MIN_IMAGE_COUNT..MAX_IMAGE_COUNT) throw WishException.invalidImageCount()
        val formats = contentTypes.map { UploadFormat.of(it) }
        itemQuotaGuard.consume(userId, formats.size, ItemErrorCode.QUOTA_EXCEEDED)
        return imagePresignService.presignRawUploads(formats) { key, expiresAt ->
            PendingUpload.wish(key, userId, expiresAt)
        }
    }

    fun confirmImageRegistration(
        imageKeys: List<String>,
        userId: UUID,
    ): List<WishWithItem> {
        requireMember(userId)
        if (imageKeys.size !in MIN_IMAGE_COUNT..MAX_IMAGE_COUNT) throw WishException.invalidImageCount()
        imagePresignService.verifyUploaded(imageKeys)
        return wishPersistenceService.registerClaimedImages(imageKeys, userId)
    }

    @Transactional(readOnly = true)
    fun getWishlist(
        userId: UUID,
        rawCursor: String?,
        rawSize: Int?,
    ): WishlistPage {
        requireMember(userId)
        val cursor = WishCursor.parse(rawCursor)
        val size = WishlistSize.of(rawSize).value
        // hasNext 판단을 위해 한 건 더 조회하고, 초과분은 응답에서 잘라낸다.
        val fetched = wishRepository.findPage(userId, cursor, size + 1)
        val hasNext = fetched.size > size
        val pageWishes = fetched.take(size)

        // 포인터 버전을 끌어온 뒤 표시값은 파생한다(#857) — 카드는 항상 그 상품의 마지막 기계 READY 를 향하고,
        // 수기 존중·진행 중 유지 등 규칙은 ItemDisplayService 가 진다. 포인터는 정체성 도달·수기 존중 판정의 표식이다.
        val snapshotsById =
            itemSnapshotRepository
                .findByIds(pageWishes.map { it.snapshotId })
                .associateBy { it.getId() }
        val displayById = itemDisplayService.resolveDisplay(snapshotsById.values)
        // item 정체성은 snapshot.itemId 단일 출처다. snapshot 에서 itemId 를 모아 item 을 한 번에 끌어온다.
        val itemsById = itemRepository
            .findByIds(snapshotsById.values.map { it.itemId })
            .associateBy { it.getId() }
        val entries =
            pageWishes.map { wish ->
                // snapshot·item 은 wish 와 함께 영속화되며 별도 삭제 경로가 없다. 없으면 영속화 경로가 깨진 코드 버그다.
                val pointer =
                    snapshotsById[wish.snapshotId]
                        ?: error("wish ${wish.getId()} 의 snapshot ${wish.snapshotId} 가 없다")
                val item = itemsById[pointer.itemId] ?: error("wish ${wish.getId()} 의 item ${pointer.itemId} 가 없다")
                WishWithItem(wish = wish, item = item, snapshot = displayById[pointer.getId()] ?: pointer)
            }

        val nextCursor =
            pageWishes
                .lastOrNull()
                ?.getId()
                ?.toString()
                .takeIf { hasNext }
        return WishlistPage(entries = entries, nextCursor = nextCursor, hasNext = hasNext)
    }

    // wishId 로 상세 조회 — 표시값과 그 상품의 가격 이력을 함께 내려준다. 본인 위시만 볼 수 있고,
    // 권한 검증은 도메인(verifyOwnedBy)에 맡긴다. findById 가 deletedAt IS NULL 만 보므로 삭제된 위시는 notFound(404).
    //
    // 이력을 별도 API 로 두지 않는 이유: 옛 히스토리 API 는 wish.snapshotId(포인터)를 그대로 "활성" 이라 불러
    // 표시값 파생(#857)을 타지 않았다. item 을 여러 사용자가 공유하므로 남이 새로고침하면 포인터는 옛 버전에 남고,
    // 그러면 같은 위시에 대해 두 API 의 답이 어긋난다. 한 응답에서 표시값을 단일 출처로 두어 그 어긋남을 없앤다.
    @Transactional(readOnly = true)
    fun getWish(
        userId: UUID,
        wishId: Long,
    ): WishDetail {
        requireMember(userId)
        val wish = wishRepository.findById(wishId) ?: throw WishException.notFound()
        wish.verifyOwnedBy(userId)
        // wish 가 가리키는 snapshot·item 은 반드시 존재한다. 없으면 영속화 경로가 깨진 코드 버그다.
        // item 정체성은 snapshot.itemId 단일 출처다 — snapshot 을 먼저 끌어오고 그 itemId 로 item 을 조회한다.
        val pointer =
            itemSnapshotRepository.findById(wish.snapshotId)
                ?: error("wish ${wish.getId()} 의 snapshot ${wish.snapshotId} 가 없다")
        val item =
            itemRepository.findById(pointer.itemId) ?: error("wish ${wish.getId()} 의 item ${pointer.itemId} 가 없다")
        // 표시값 파생(#857) — 목록(getWishlist)과 같은 규칙.
        val history = itemSnapshotRepository.findPriceHistoryByItemId(pointer.itemId, PRICE_HISTORY_LIMIT)
        return WishDetail(
            wish = wish,
            item = item,
            snapshot = itemDisplayService.resolveDisplay(pointer),
            history = history,
        )
    }

    // 위시 item 의 수기 수정(#825 결정 4) — 상태 무관 언제든 허용하며, 기존 버전을 고치지 않고 MANUAL 새 버전을
    // 쌓아 활성 포인터를 스왑한다(편집자 기록·이력 보존). 이미지를 함께 주면 그대로 S3 에 올려 imageUrl 로 쓴다
    // (추출·크롭 없음 — 사용자가 고른 이미지를 그대로 대표 이미지로). 외부 호출(S3)을 트랜잭션에 넣지 않기 위해
    // 검증·소유권 사전확인·업로드를 트랜잭션 밖에서 끝내고, 영속화만 manualEdit(@Transactional, wish 행 락)에 위임한다.
    fun recoverWishItem(
        userId: UUID,
        wishId: Long,
        name: String?,
        price: Int?,
        currency: String?,
        image: MultipartFile?,
        memo: String?,
    ): WishWithItem {
        requireMember(userId)
        // memo 만 온 요청은 버전을 쌓지 않는다 — memo 는 wish 행의 개인 필드라 snapshot 이력과 무관하다.
        // item 필드가 함께 오면 아래 수기 수정 경로가 같은 트랜잭션(manualEdit)에서 memo 도 반영한다.
        if (listOfNotNull(name, price, currency, image).isEmpty()) {
            memo?.let {
                val result = wishPersistenceService.updateMemo(userId = userId, wishId = wishId, memo = it)
                // 표시값 파생(#857) — 조회와 같은 규칙으로 응답의 item 을 맞춘다.
                return result.copy(snapshot = itemDisplayService.resolveDisplay(result.snapshot))
            }
        }
        // 이미지 형식 검증(빈 바이트·미지원 MIME) — 외부 호출 전에 동기로 거른다(400).
        val productImage = image?.let { ProductImage.of(it.bytes, it.contentType) }
        val wish = wishRepository.findById(wishId) ?: throw WishException.notFound()
        wish.verifyOwnedBy(userId)
        // 업로드 전 사전 검증(orphan 방지) — 병합 결과가 400 이면 S3 에 올리기 전에 같은 규칙으로 거른다.
        // 이미지가 오면 업로드가 imageUrl 을 채울 것이므로 자리표시 URL 로 그 자리만 메워 이름·가격 병합을 검증한다
        // (이 값은 저장되지 않는다 — 던지기 전용 dry-run). 락 밖 조회라 최종 판정은 manualEdit(락 안)이 다시 한다.
        val activeSnapshot =
            itemSnapshotRepository.findById(wish.snapshotId)
                ?: error("wish ${wish.getId()} 의 snapshot ${wish.snapshotId} 가 없다")
        ItemSnapshot.manual(
            base = activeSnapshot,
            name = name,
            price = price,
            imageUrl = productImage?.let { PRE_UPLOAD_VALIDATION_IMAGE_URL },
            currency = currency,
            editedBy = userId,
        )
        // 이미지가 있으면 S3 업로드(트랜잭션 밖). 실패 시 ImageStorageException(502).
        val imageUrl =
            productImage?.let {
                imageStorage.upload(it.bytes, "items/${UUID.randomUUID()}.${it.extension}", it.mimeType)
            }
        return wishPersistenceService.manualEdit(
            userId = userId,
            wishId = wishId,
            name = name,
            price = price,
            imageUrl = imageUrl,
            currency = currency,
            memo = memo,
        )
    }

    // 위시 item 의 상품 정보를 원본 링크로 재추출해 최신화한다(수동 새로고침). 추출(Gemini)은 디스패처가 비동기로
    // 하므로 여기엔 외부 호출이 없고, 영속화(새 PENDING 적재 + 활성 포인터 즉시 스왑)만 wishPersistenceService.refresh
    // (@Transactional, wish 행 락)에 위임한다. 등록과 같은 폴링 흐름(PENDING→PROCESSING→READY/FAILED)으로 전이한다.
    fun refreshWishItem(
        userId: UUID,
        wishId: Long,
    ): WishWithItem {
        requireMember(userId)
        // 재추출도 파싱을 한 번 더 돌리므로 신규 등록과 같은 비용이다 — 1 로 차감한다.
        // refresh 계약 검증(링크 없음·FAILED 항목 등)은 persistence 안쪽이라 여기선 앞서 깎이는데, 그 두 사유는
        // 클라가 refresh 버튼을 띄우지 않는 상태라 정상 흐름에서 반복 호출되지 않는다.
        itemQuotaGuard.consume(userId, 1, ItemErrorCode.QUOTA_EXCEEDED)
        return wishPersistenceService.refresh(userId = userId, wishId = wishId)
    }

    // 멱등 삭제: 없거나 이미 삭제됐으면 "이미 목표 상태(없음)"이므로 성공으로 본다(no-op).
    // 단 존재하는 위시가 남의 것이면 소유권은 보안 경계라 403 으로 막는다.
    @Transactional
    fun deleteWish(
        userId: UUID,
        wishId: Long,
    ) {
        requireMember(userId)
        val wish = wishRepository.findById(wishId) ?: return
        wish.verifyOwnedBy(userId)
        wish.delete()
    }

    // 여러 위시를 한 번에 멱등 삭제한다. 없거나 이미 삭제된 id 는 조회에서 빠져 자연히 무시된다(목표 상태 달성).
    // 존재하는 것 중 남의 위시가 하나라도 있으면 소유권 경계로 403, @Transactional 이라 본인 것도 함께 롤백된다.
    @Transactional
    fun deleteWishes(
        userId: UUID,
        wishIds: WishDeleteIds,
    ) {
        requireMember(userId)
        // WishDeleteIds 가 distinct·개수(1~100) 검증을 끝낸 값이라 여기선 조회·소유검증·삭제만 한다.
        val wishes = wishRepository.findAllByIds(wishIds.values)
        wishes.forEach { it.verifyOwnedBy(userId) }
        wishes.forEach { it.delete() }
    }

    companion object {
        private const val MIN_IMAGE_COUNT = 1
        private const val MAX_IMAGE_COUNT = 5

        // 상세 응답에 싣는 가격 이력의 상한. item 을 여러 사용자가 공유해 새로고침이 누적되는데 상세는 진입마다
        // 호출되므로, 상한이 없으면 응답이 시간에 비례해 계속 자란다. 가격 추이 용도에는 이 정도면 충분하고,
        // 전체 이력이 실제로 필요해지면 그때 페이징을 갖춘 API 를 따로 만든다 (상세 응답 안에 페이징을 중첩하면
        // 방금 없앤 별도 히스토리 API 를 다시 만드는 꼴이다).
        private const val PRICE_HISTORY_LIMIT = 50
    }
}

// 수기 수정 사전 검증(dry-run)에서 업로드 예정 이미지 자리를 메우는 자리표시 값 — 저장되지 않는다.
private const val PRE_UPLOAD_VALIDATION_IMAGE_URL = "https://validation.invalid/pre-upload.png"
