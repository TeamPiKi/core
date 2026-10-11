package com.depromeet.piki.wishlist.service

import com.depromeet.piki.common.ratelimit.ItemQuotaGuard
import com.depromeet.piki.common.storage.ImageStorage
import com.depromeet.piki.image.domain.ProductImageKey
import com.depromeet.piki.image.domain.ProductImage
import com.depromeet.piki.image.domain.UploadFormat
import com.depromeet.piki.image.service.ImagePresignService
import com.depromeet.piki.image.service.dto.PresignedRawUpload
import com.depromeet.piki.item.domain.ItemErrorCode
import com.depromeet.piki.item.repository.ItemRepository
import com.depromeet.piki.item.repository.ItemSnapshotRepository
import com.depromeet.piki.item.service.ItemCardService
import com.depromeet.piki.item.service.ItemRegistrar
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.source.SourcePlatformResolver
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.user.service.UserService
import com.depromeet.piki.wishlist.domain.WishCursor
import com.depromeet.piki.wishlist.domain.WishDeleteIds
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
    private val itemCardService: ItemCardService,
    private val itemQuotaGuard: ItemQuotaGuard,
    private val userService: UserService,
    private val sourcePlatformResolver: SourcePlatformResolver,
) {
    private fun WishWithItem.withSourcePlatform(): WishWithItem = copy(sourcePlatform = sourcePlatformResolver.resolve(item?.sourceLink() ?: card.pendingLink))

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
        return wishPersistenceService.persist(userId, link).withSourcePlatform()
    }

    fun presignImageUploads(
        formats: List<UploadFormat>,
        userId: UUID,
    ): List<PresignedRawUpload> {
        requireMember(userId)
        if (formats.size !in MIN_IMAGE_COUNT..MAX_IMAGE_COUNT) throw WishException.invalidImageCount()
        itemQuotaGuard.consume(userId, formats.size, ItemErrorCode.QUOTA_EXCEEDED)
        return imagePresignService.presignProductUploads(formats)
    }

    fun confirmImageRegistration(
        imageKeys: List<ProductImageKey>,
        userId: UUID,
    ): List<WishWithItem> {
        requireMember(userId)
        if (imageKeys.size !in MIN_IMAGE_COUNT..MAX_IMAGE_COUNT) throw WishException.invalidImageCount()
        imagePresignService.verifyUploaded(imageKeys)
        return wishPersistenceService.registerImages(imageKeys, userId).map { it.withSourcePlatform() }
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
        val fetched = wishRepository.findPage(userId, cursor, size + 1)
        val hasNext = fetched.size > size
        val pageWishes = fetched.take(size)

        val cardByQuery = itemCardService.cardsOf(pageWishes.map { it.cardQuery() })
        val itemsById = itemRepository.findByIds(pageWishes.mapNotNull { it.itemId }).associateBy { it.getId() }
        val entries =
            pageWishes.map { wish ->
                WishWithItem(
                    wish = wish,
                    item = wish.itemId?.let { itemsById[it] ?: error("wish ${wish.getId()} 의 item $it 가 없다") },
                    card = cardByQuery.getValue(wish.cardQuery()),
                ).withSourcePlatform()
            }

        val nextCursor =
            pageWishes
                .lastOrNull()
                ?.getId()
                ?.toString()
                .takeIf { hasNext }
        return WishlistPage(entries = entries, nextCursor = nextCursor, hasNext = hasNext)
    }

    @Transactional(readOnly = true)
    fun getWish(
        userId: UUID,
        wishId: Long,
    ): WishDetail {
        requireMember(userId)
        val wish = wishRepository.findById(wishId) ?: throw WishException.notFound()
        wish.verifyOwnedBy(userId)
        val item = wish.itemId?.let { itemRepository.findById(it) ?: error("wish ${wish.getId()} 의 item $it 가 없다") }
        val card = itemCardService.cardOf(wish.cardQuery())
        return WishDetail(
            wish = wish,
            item = item,
            card = card,
            history = item?.let { itemSnapshotRepository.findPriceHistoryByItemId(it.getId(), PRICE_HISTORY_LIMIT) }.orEmpty(),
            sourcePlatform = sourcePlatformResolver.resolve(item?.sourceLink() ?: card.pendingLink),
        )
    }

    // S3 업로드가 트랜잭션 안에 들어가지 않게 영속화만 applyUserInput 에 맡긴다
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
        if (listOfNotNull(name, price, currency, image).isEmpty()) {
            memo?.let {
                return wishPersistenceService.updateMemo(userId = userId, wishId = wishId, memo = it).withSourcePlatform()
            }
        }
        val productImage = image?.let { ProductImage.of(it.bytes, it.contentType) }
        productImage?.let {
            wishPersistenceService.validateUserInput(userId, wishId, name, price, currency)
        }
        val imageUrl =
            productImage?.let {
                imageStorage.upload(it.bytes, "items/${UUID.randomUUID()}.${it.extension}", it.mimeType)
            }
        return wishPersistenceService.applyUserInput(
            userId = userId,
            wishId = wishId,
            name = name,
            price = price,
            imageUrl = imageUrl,
            currency = currency,
            memo = memo,
        ).withSourcePlatform()
    }

    fun refreshWishItem(
        userId: UUID,
        wishId: Long,
    ): WishWithItem {
        requireMember(userId)
        itemQuotaGuard.consume(userId, 1, ItemErrorCode.QUOTA_EXCEEDED)
        return wishPersistenceService.refresh(userId = userId, wishId = wishId).withSourcePlatform()
    }

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

    @Transactional
    fun deleteWishes(
        userId: UUID,
        wishIds: WishDeleteIds,
    ) {
        requireMember(userId)
        val wishes = wishRepository.findAllByIds(wishIds.values)
        wishes.forEach { it.verifyOwnedBy(userId) }
        wishes.forEach { it.delete() }
    }

    companion object {
        private const val MIN_IMAGE_COUNT = 1
        private const val MAX_IMAGE_COUNT = 5

        // 아이템을 여럿이 공유해 새로고침마다 이력이 쌓이므로 상한이 없으면 상세 응답이 계속 자란다
        private const val PRICE_HISTORY_LIMIT = 50
    }
}
