package com.depromeet.piki.wishlist.service

import com.depromeet.piki.common.ratelimit.ItemQuotaGuard
import com.depromeet.piki.common.storage.ImageStorage
import com.depromeet.piki.image.service.ImagePresignService
import com.depromeet.piki.image.service.dto.PresignedRawUpload
import com.depromeet.piki.item.domain.ItemErrorCode
import com.depromeet.piki.item.repository.ItemRepository
import com.depromeet.piki.item.repository.ItemSnapshotRepository
import com.depromeet.piki.item.service.ItemCardService
import com.depromeet.piki.item.service.ItemRegistrar
import com.depromeet.piki.product.source.SourcePlatformResolver
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.user.service.UserService
import com.depromeet.piki.wishlist.domain.WishException
import com.depromeet.piki.wishlist.repository.WishRepository
import com.depromeet.piki.wishlist.service.dto.ConfirmWishImageRegistrationCommand
import com.depromeet.piki.wishlist.service.dto.DeleteWishCommand
import com.depromeet.piki.wishlist.service.dto.DeleteWishesCommand
import com.depromeet.piki.wishlist.service.dto.GetWishQuery
import com.depromeet.piki.wishlist.service.dto.GetWishlistQuery
import com.depromeet.piki.wishlist.service.dto.PresignWishImageUploadsCommand
import com.depromeet.piki.wishlist.service.dto.RecoverWishItemCommand
import com.depromeet.piki.wishlist.service.dto.RefreshWishItemCommand
import com.depromeet.piki.wishlist.service.dto.RegisterWishFromUrlCommand
import com.depromeet.piki.wishlist.service.dto.WishDetailResult
import com.depromeet.piki.wishlist.service.dto.WishResult
import com.depromeet.piki.wishlist.service.dto.WishlistResult
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
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
    private fun WishResult.withSourcePlatform(): WishResult = copy(sourcePlatform = sourcePlatformResolver.resolve(item?.sourceLink() ?: card.pendingLink))

    // TODO AOP
    private fun requireMember(userId: UUID) {
        val user = userService.findActiveById(userId)
        if (user.identityType != IdentityType.MEMBER) throw WishException.guestCannotUseWishlist()
    }

    fun registerFromUrl(command: RegisterWishFromUrlCommand): WishResult {
        requireMember(command.userId)
        wishPersistenceService.rejectIfAlreadyRegistered(command)
        itemRegistrar.accept(command.link, command.userId)
        return wishPersistenceService.persist(command).withSourcePlatform()
    }

    fun presignImageUploads(command: PresignWishImageUploadsCommand): List<PresignedRawUpload> {
        requireMember(command.userId)
        if (command.uploadFormats.size !in MIN_IMAGE_COUNT..MAX_IMAGE_COUNT) throw WishException.invalidImageCount()
        itemQuotaGuard.consume(command.userId, command.uploadFormats.size, ItemErrorCode.QUOTA_EXCEEDED)
        return imagePresignService.presignProductUploads(command.uploadFormats)
    }

    fun confirmImageRegistration(command: ConfirmWishImageRegistrationCommand): List<WishResult> {
        requireMember(command.userId)
        if (command.imageKeys.size !in MIN_IMAGE_COUNT..MAX_IMAGE_COUNT) throw WishException.invalidImageCount()
        imagePresignService.verifyUploaded(command.imageKeys)
        return wishPersistenceService.registerImages(command).map { it.withSourcePlatform() }
    }

    @Transactional(readOnly = true)
    fun getWishlist(query: GetWishlistQuery): WishlistResult {
        requireMember(query.userId)
        val size = query.size.value
        val fetched = wishRepository.findPage(query.userId, query.cursor, size + 1)
        val hasNext = fetched.size > size
        val pageWishes = fetched.take(size)

        val cardByQuery = itemCardService.cardsOf(pageWishes.map { it.cardQuery() })
        val itemsById = itemRepository.findByIds(pageWishes.mapNotNull { it.itemId }).associateBy { it.getId() }
        val entries =
            pageWishes.map { wish ->
                WishResult(
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
        return WishlistResult(entries = entries, nextCursor = nextCursor, hasNext = hasNext)
    }

    @Transactional(readOnly = true)
    fun getWish(query: GetWishQuery): WishDetailResult {
        requireMember(query.userId)
        val wish = wishRepository.findById(query.wishId) ?: throw WishException.notFound()
        wish.verifyOwnedBy(query.userId)
        val item = wish.itemId?.let { itemRepository.findById(it) ?: error("wish ${wish.getId()} 의 item $it 가 없다") }
        val card = itemCardService.cardOf(wish.cardQuery())
        return WishDetailResult(
            wish = wish,
            item = item,
            card = card,
            history = item?.let { itemSnapshotRepository.findPriceHistoryByItemId(it.getId(), PRICE_HISTORY_LIMIT) }.orEmpty(),
            sourcePlatform = sourcePlatformResolver.resolve(item?.sourceLink() ?: card.pendingLink),
        )
    }

    // S3 업로드가 트랜잭션 안에 들어가지 않게 영속화만 applyUserInput 에 맡긴다
    fun recoverWishItem(command: RecoverWishItemCommand): WishResult {
        requireMember(command.userId)
        command.memoOnly()?.let { return wishPersistenceService.updateMemo(it).withSourcePlatform() }
        command.image?.let { wishPersistenceService.validateUserInput(command) }
        val imageUrl =
            command.image?.let {
                imageStorage.upload(it.bytes, "items/${UUID.randomUUID()}.${it.extension}", it.mimeType)
            }
        return wishPersistenceService.applyUserInput(command.toUserInput(imageUrl)).withSourcePlatform()
    }

    fun refreshWishItem(command: RefreshWishItemCommand): WishResult {
        requireMember(command.userId)
        itemQuotaGuard.consume(command.userId, 1, ItemErrorCode.QUOTA_EXCEEDED)
        return wishPersistenceService.refresh(command).withSourcePlatform()
    }

    @Transactional
    fun deleteWish(command: DeleteWishCommand) {
        requireMember(command.userId)
        val wish = wishRepository.findById(command.wishId) ?: return
        wish.verifyOwnedBy(command.userId)
        wish.delete()
    }

    @Transactional
    fun deleteWishes(command: DeleteWishesCommand) {
        requireMember(command.userId)
        val wishes = wishRepository.findAllByIds(command.wishDeleteIds.values)
        wishes.forEach { it.verifyOwnedBy(command.userId) }
        wishes.forEach { it.delete() }
    }

    companion object {
        private const val MIN_IMAGE_COUNT = 1
        private const val MAX_IMAGE_COUNT = 5

        // 아이템을 여럿이 공유해 새로고침마다 이력이 쌓이므로 상한이 없으면 상세 응답이 계속 자란다
        private const val PRICE_HISTORY_LIMIT = 50
    }
}
