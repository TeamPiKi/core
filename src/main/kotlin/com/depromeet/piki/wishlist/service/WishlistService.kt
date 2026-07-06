package com.depromeet.piki.wishlist.service

import com.depromeet.piki.common.storage.ImageStorage
import com.depromeet.piki.image.domain.PendingUpload
import com.depromeet.piki.image.domain.ProductImage
import com.depromeet.piki.image.service.ImagePresignService
import com.depromeet.piki.image.service.dto.PresignedRawUpload
import com.depromeet.piki.item.domain.Item
import com.depromeet.piki.item.repository.ItemRepository
import com.depromeet.piki.item.repository.ItemSnapshotRepository
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.user.service.UserService
import com.depromeet.piki.wishlist.domain.WishCursor
import com.depromeet.piki.wishlist.domain.WishDeleteIds
import com.depromeet.piki.wishlist.domain.WishException
import com.depromeet.piki.wishlist.domain.WishlistSize
import com.depromeet.piki.wishlist.repository.WishRepository
import com.depromeet.piki.wishlist.service.dto.WishPriceHistory
import com.depromeet.piki.wishlist.service.dto.WishWithItem
import com.depromeet.piki.wishlist.service.dto.WishlistPage
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

@Service
class WishlistService(
    private val wishPersistenceService: WishPersistenceService,
    private val imageStorage: ImageStorage,
    private val imagePresignService: ImagePresignService,
    private val wishRepository: WishRepository,
    private val itemRepository: ItemRepository,
    private val itemSnapshotRepository: ItemSnapshotRepository,
    private val userService: UserService,
) {
    // 위시리스트는 회원 전용. 게스트(인증은 됐으나 회원 아님)는 Security 가 아니라 여기서 도메인 계약으로 막아
    // "회원만 이용 가능" 이라는 구체 사유를 내려준다(SecurityConfig 의 wishlists authenticated() 주석 참고).
    // 인증 principal 은 userId 뿐이라 identityType 은 조회로 확인한다 — 모든 진입 메서드가 처리 전에 가장 먼저 호출한다.
    private fun requireMember(userId: UUID) {
        val user = userService.findById(userId)
        if (user.identityType != IdentityType.MEMBER) throw WishException.guestCannotUseWishlist()
    }

    // registerFromUrl 는 외부 LLM 호출(read-timeout 60s)을 동기로 기다리지 않는다.
    // link 만 가진 item 과 PENDING snapshot 을 즉시 커밋해 응답을 돌려주고(클라이언트는 "담는 중" 표시),
    // 실제 파싱은 디스패처(@Scheduled)가 PENDING 을 집어 워커에 넘겨 READY/FAILED 로 전이시킨다.
    // DB 의 PENDING 행이 작업의 진실 원천이라 @Async 큐 유실(인스턴스 재시작 등)과 무관하게 최소 1회는 claim 된다(at-least-once).
    // URL 형식·미지원 플랫폼 같은 계약 위반은 등록 시점에 동기로 거른다(400). 파싱 결과 실패만 FAILED 로 간다.
    fun registerFromUrl(
        rawUrl: String,
        userId: UUID,
    ): WishWithItem {
        requireMember(userId)
        val link = ProductLink.parse(rawUrl)
        // fetch 불가 플랫폼(봇 차단)은 담아봐야 파싱이 무의미하게 실패한다 — 등록 시점에 막아 빠르게 안내한다.
        link.verifySupportedPlatform()
        return wishPersistenceService.persist(userId, Item(link))
    }

    // 이미지 등록은 registerFromUrl(link)와 같은 비동기 outbox 흐름 — 입력이 이미지(다건)일 뿐이다.
    // 개수·형식을 동기로 검증(400)한 뒤, 원본을 S3 에 durable 적재(raw key 확보)하고 link 경로처럼 PENDING item·wish 를
    // 배치 저장해 즉시 반환한다. 실제 추출(Gemini·크롭·결과 업로드)은 디스패처(@Scheduled)가 PENDING 을 집어 워커에 넘긴다.
    // raw 를 먼저 올려 입력이 durable 하므로, @Async 유실·일시 오류로 재실행돼도 워커가 그 key 로 원본을 다시 읽는다.
    fun registerFromImages(
        images: List<MultipartFile>,
        userId: UUID,
    ): List<WishWithItem> {
        requireMember(userId)
        if (images.size !in MIN_IMAGE_COUNT..MAX_IMAGE_COUNT) throw WishException.invalidImageCount()
        // 형식 검증(빈 바이트·미지원 MIME) — 실패 시 즉시 400. 유효한 이미지만 durable 적재한다.
        val productImages = images.map { ProductImage.of(it.bytes, it.contentType) }
        // 원본을 S3 raw 에 올려 입력을 durable 화한다(외부 호출, 트랜잭션 밖). 이 key 가 item 의 입력 정체성이 된다.
        val imageKeys = productImages.map { uploadRaw(it) }
        // 위시 이미지 등록엔 정원 같은 계약 거부가 없어 정상 흐름에선 persist 가 떨어지지 않지만, 예기치 못한 영속화 실패에도
        // 방금 올린 raw 가 orphan 으로 새지 않게 즉시 회수한다(tournament 경로와 대칭, best-effort, lifecycle 백업).
        return runCatching { wishPersistenceService.persistPendingImages(userId, imageKeys) }
            .onFailure { imagePresignService.deleteRawsQuietly(imageKeys) }
            .getOrThrow()
    }

    // 이미지 등록 v2 발급 — 클라가 S3 에 직접 올릴 presigned URL 을 발급한다. v1(registerFromImages)이 서버로 바이트를
    // 받아 S3 에 올리던 것을 클라→S3 직접 업로드로 바꿔, 원본 바이트가 서버 메모리·대역을 경유하지 않게 한다.
    // 회원·개수(계약) 검증만 여기서 하고, content-type 검증·raw key 생성·presign 발급은 ImagePresignService 에 위임한다.
    fun presignImageUploads(
        contentTypes: List<String>,
        userId: UUID,
    ): List<PresignedRawUpload> {
        requireMember(userId)
        if (contentTypes.size !in MIN_IMAGE_COUNT..MAX_IMAGE_COUNT) throw WishException.invalidImageCount()
        return imagePresignService.presignRawUploads(contentTypes) { key, expiresAt ->
            PendingUpload.wish(key, userId, expiresAt)
        }
    }

    // 이미지 등록 v2 확정(빠른 경로) — 클라가 presigned 로 업로드를 마친 key 들을 받아 PENDING 위시로 적재한다.
    // key 형식·존재(HEAD) 검증 후 pending_uploads 를 claim(FOR UPDATE 삭제)하며 등록한다 — 폴링 백스톱과 같은 진입점이라
    // confirm 이 안 와도(또는 실패해도) 폴링이 회수하고, 둘이 같은 key 를 다퉈도 claim 이 한쪽만 이긴다(멱등).
    // persist 실패 시 트랜잭션이 claim 을 롤백해 pending 이 남으므로, 회수는 폴링에 맡긴다(raw 는 클라가 올린 것 + lifecycle 백업).
    fun confirmImageRegistration(
        imageKeys: List<String>,
        userId: UUID,
    ): List<WishWithItem> {
        requireMember(userId)
        if (imageKeys.size !in MIN_IMAGE_COUNT..MAX_IMAGE_COUNT) throw WishException.invalidImageCount()
        imagePresignService.verifyUploaded(imageKeys)
        return wishPersistenceService.registerClaimedImages(imageKeys, userId)
    }

    // 원본 이미지를 S3 raw prefix 에 올리고 그 object key 를 돌려준다. upload 는 공개 URL 을 반환하지만 outbox 입력엔
    // 우리가 만든 key 가 필요하다(워커가 download(key)로 다시 읽는다). 파싱이 끝나면 워커가 이 raw 를 회수한다.
    private fun uploadRaw(image: ProductImage): String {
        val key = "items/raw/${UUID.randomUUID()}.${image.extension}"
        imageStorage.upload(image.bytes, key, image.mimeType)
        return key
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

        // 표시값은 wish 의 활성 snapshot 에서 읽는다. snapshot 은 등록 시 함께 생기고 wish.snapshotId 로 고정된다.
        val snapshotsById =
            itemSnapshotRepository.findByIds(pageWishes.map { it.snapshotId }).associateBy { it.getId() }
        // item 정체성은 snapshot.itemId 단일 출처다. snapshot 에서 itemId 를 모아 item 을 한 번에 끌어온다.
        val itemsById = itemRepository.findByIds(snapshotsById.values.map { it.itemId }).associateBy { it.getId() }
        val entries =
            pageWishes.map { wish ->
                // snapshot·item 은 wish 와 함께 영속화되며 별도 삭제 경로가 없다. 없으면 영속화 경로가 깨진 코드 버그다.
                val snapshot =
                    snapshotsById[wish.snapshotId]
                        ?: error("wish ${wish.getId()} 의 snapshot ${wish.snapshotId} 가 없다")
                val item = itemsById[snapshot.itemId] ?: error("wish ${wish.getId()} 의 item ${snapshot.itemId} 가 없다")
                WishWithItem(wish = wish, item = item, snapshot = snapshot)
            }

        val nextCursor =
            pageWishes
                .lastOrNull()
                ?.getId()
                ?.toString()
                .takeIf { hasNext }
        return WishlistPage(entries = entries, nextCursor = nextCursor, hasNext = hasNext)
    }

    // wishId 로 단건 조회. 본인 위시만 볼 수 있고, 권한 검증은 도메인(verifyOwnedBy)에 맡긴다.
    // findById 가 deletedAt IS NULL 만 보므로 삭제된 위시는 notFound(404)로 떨어진다.
    @Transactional(readOnly = true)
    fun getWish(
        userId: UUID,
        wishId: Long,
    ): WishWithItem {
        requireMember(userId)
        val wish = wishRepository.findById(wishId) ?: throw WishException.notFound()
        wish.verifyOwnedBy(userId)
        // wish 가 가리키는 snapshot·item 은 반드시 존재한다. 없으면 영속화 경로가 깨진 코드 버그다.
        // item 정체성은 snapshot.itemId 단일 출처다 — snapshot 을 먼저 끌어오고 그 itemId 로 item 을 조회한다.
        val snapshot =
            itemSnapshotRepository.findById(wish.snapshotId)
                ?: error("wish ${wish.getId()} 의 snapshot ${wish.snapshotId} 가 없다")
        val item =
            itemRepository.findById(snapshot.itemId) ?: error("wish ${wish.getId()} 의 item ${snapshot.itemId} 가 없다")
        return WishWithItem(wish = wish, item = item, snapshot = snapshot)
    }

    // 위시 상품의 가격 히스토리 조회. wish 가 가리키는 활성 snapshot 에서 item 정체성(itemId)에 도달한 뒤,
    // 그 item 의 추출 완료(READY) 버전 전체를 최신순으로 끌어온다 — 갱신·새로고침마다 쌓인 버전이 가격 이력이다.
    // 단건 조회(getWish)와 같은 소유권 검증·트랜잭션 경계. wish 는 itemId 를 직접 들지 않으므로 snapshot 을 거쳐 도달한다.
    @Transactional(readOnly = true)
    fun getPriceHistory(
        userId: UUID,
        wishId: Long,
    ): WishPriceHistory {
        val wish = wishRepository.findById(wishId) ?: throw WishException.notFound()
        wish.verifyOwnedBy(userId)
        // 활성 snapshot·item 은 영속화 경로상 반드시 존재한다(없으면 코드 버그). item 정체성은 snapshot.itemId 단일 출처다.
        val activeSnapshot =
            itemSnapshotRepository.findById(wish.snapshotId)
                ?: error("wish ${wish.getId()} 의 snapshot ${wish.snapshotId} 가 없다")
        val item =
            itemRepository.findById(activeSnapshot.itemId)
                ?: error("wish ${wish.getId()} 의 item ${activeSnapshot.itemId} 가 없다")
        val history = itemSnapshotRepository.findReadyHistoryByItemId(activeSnapshot.itemId)
        return WishPriceHistory(wish = wish, item = item, history = history)
    }

    // 추출 실패(FAILED) item 을 사용자가 직접 보정해 READY 로 복구한다. 이미지를 함께 주면 그대로 S3 에 올려
    // imageUrl 을 채운다(추출·크롭 없음 — 사용자가 고른 이미지를 그대로 대표 이미지로). READY·PROCESSING 은
    // recover 가 409 로 막는다. 외부 호출(S3)을 트랜잭션에 넣지 않기 위해 검증·소유권·상태 사전확인·업로드를
    // 트랜잭션 밖에서 끝내고, 영속화만 wishPersistenceService.recoverItem(@Transactional)에 위임한다.
    fun recoverWishItem(
        userId: UUID,
        wishId: Long,
        name: String?,
        currentPrice: Int?,
        currency: String?,
        image: MultipartFile?,
    ): WishWithItem {
        requireMember(userId)
        // 이미지 형식 검증(빈 바이트·미지원 MIME) — 외부 호출 전에 동기로 거른다(400).
        val productImage = image?.let { ProductImage.of(it.bytes, it.contentType) }
        val wish = wishRepository.findById(wishId) ?: throw WishException.notFound()
        wish.verifyOwnedBy(userId)
        // 활성 snapshot 으로 사전 상태 검증 — FAILED 가 아니면 S3 에 올리기 전에 막는다(orphan 업로드 방지).
        // recover 가 READY/PROCESSING 에 사유별 409 를 던진다(트랜잭션 밖 조회라 던지기 전용, 실제 보정은 recoverItem).
        // item 정체성은 snapshot.itemId 단일 출처다. snapshot·item 은 영속화 경로상 반드시 존재한다(없으면 코드 버그).
        val activeSnapshot =
            itemSnapshotRepository.findById(wish.snapshotId)
                ?: error("wish ${wish.getId()} 의 snapshot ${wish.snapshotId} 가 없다")
        val item =
            itemRepository.findById(activeSnapshot.itemId)
                ?: error("wish ${wish.getId()} 의 item ${activeSnapshot.itemId} 가 없다")
        if (!activeSnapshot.isFailed()) activeSnapshot.recover()
        // 이미지가 있으면 S3 업로드(트랜잭션 밖). 실패 시 ImageStorageException(502).
        val imageUrl =
            productImage?.let {
                imageStorage.upload(it.bytes, "items/${UUID.randomUUID()}.${it.extension}", it.mimeType)
            }
        wishPersistenceService.recoverItem(activeSnapshot.getId(), name, currentPrice, imageUrl, currency)
        // recoverItem 이 같은 트랜잭션에서 활성 snapshot 을 보정했다. 응답 표시값은 그 snapshot 을 재조회해 읽는다.
        val snapshot =
            itemSnapshotRepository.findById(wish.snapshotId)
                ?: error("wish ${wish.getId()} 의 snapshot ${wish.snapshotId} 가 없다")
        return WishWithItem(wish = wish, item = item, snapshot = snapshot)
    }

    // 위시 item 의 상품 정보를 원본 링크로 재추출해 최신화한다(수동 새로고침). 추출(Gemini)은 디스패처가 비동기로
    // 하므로 여기엔 외부 호출이 없고, 영속화(새 PENDING 적재 + 활성 포인터 즉시 스왑)만 wishPersistenceService.refresh
    // (@Transactional, wish 행 락)에 위임한다. 등록과 같은 폴링 흐름(PENDING→PROCESSING→READY/FAILED)으로 전이한다.
    fun refreshWishItem(
        userId: UUID,
        wishId: Long,
    ): WishWithItem {
        requireMember(userId)
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
    }
}
