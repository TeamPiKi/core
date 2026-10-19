package com.depromeet.piki.wishlist.service

import com.depromeet.piki.common.exception.AlreadyRegisteredException
import com.depromeet.piki.item.domain.NormalizedInput
import com.depromeet.piki.item.domain.ParseInput
import com.depromeet.piki.item.domain.ParsePurpose
import com.depromeet.piki.item.repository.ItemRepository
import com.depromeet.piki.item.service.ItemCardService
import com.depromeet.piki.item.service.ItemSlotFill
import com.depromeet.piki.item.service.ParsingEnqueuer
import com.depromeet.piki.item.service.UserInputRecorder
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.user.service.UserService
import com.depromeet.piki.wishlist.domain.Wish
import com.depromeet.piki.wishlist.domain.WishErrorCode
import com.depromeet.piki.wishlist.domain.WishException
import com.depromeet.piki.wishlist.domain.WishMemo
import com.depromeet.piki.wishlist.repository.WishMemoRepository
import com.depromeet.piki.wishlist.repository.WishRepository
import com.depromeet.piki.wishlist.service.dto.ApplyWishUserInputCommand
import com.depromeet.piki.wishlist.service.dto.ConfirmWishImageRegistrationCommand
import com.depromeet.piki.wishlist.service.dto.RecoverWishItemCommand
import com.depromeet.piki.wishlist.service.dto.RefreshWishItemCommand
import com.depromeet.piki.wishlist.service.dto.RegisterWishFromUrlCommand
import com.depromeet.piki.wishlist.service.dto.UpdateWishMemoCommand
import com.depromeet.piki.wishlist.service.dto.WishResult
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

// WishlistService 에서 분리된 빈이다. 같은 빈 안에서 부르면 AOP proxy 를 안 거쳐 @Transactional 이 무력화된다.
@Service
class WishPersistenceService(
    private val wishRepository: WishRepository,
    private val wishMemoRepository: WishMemoRepository,
    private val itemRepository: ItemRepository,
    private val itemCardService: ItemCardService,
    private val userInputRecorder: UserInputRecorder,
    private val parsingEnqueuer: ParsingEnqueuer,
    private val userService: UserService,
) {
    // 이미 담은 상품이면 한도를 깎기 전에 409 로 끊는다(#973)
    @Transactional(readOnly = true)
    fun rejectIfAlreadyRegistered(command: RegisterWishFromUrlCommand) {
        rejectIfRegistered(command.userId, command.link)
    }

    // 이미 아는 링크여도 아이템 없이 담고 새로 파싱한다. 성공하면 정체 URL 이 같은 아이템에 붙는다
    @Transactional
    fun persist(command: RegisterWishFromUrlCommand): WishResult {
        lockRegistrant(command.userId)
        rejectIfRegistered(command.userId, command.link)
        return createPending(command.userId, ParseInput.Link(command.link))
    }

    // 같은 업로드를 두 번 확정하면 두 번째는 건너뛴다. 여러 장을 한 번에 확정하므로 링크처럼 거부하지 않는다
    @Transactional
    fun registerImages(command: ConfirmWishImageRegistrationCommand): List<WishResult> {
        lockRegistrant(command.userId)
        val newKeys = command.imageKeys.distinct() - wishRepository.findRequestedImageKeys(command.userId, command.imageKeys)
        return newKeys.map { createPending(command.userId, ParseInput.Image(it)) }
    }

    // 트랜잭션의 첫 문장이어야 한다. 일반 읽기가 먼저 오면 REPEATABLE READ 스냅샷이 잠금 전에 고정돼 앞선 등록을 못 본다
    private fun lockRegistrant(userId: UUID) {
        userService.findActiveByIdForUpdate(userId)
    }

    private fun createPending(
        userId: UUID,
        input: ParseInput,
    ): WishResult {
        val wish = wishRepository.save(Wish(userId = userId, itemId = null))
        parsingEnqueuer.enqueue(wish.slot, ParsePurpose.REGISTER, input)
        return WishResult(wish = wish, item = null, card = itemCardService.cardOf(wish.cardQuery()))
    }

    // 요청 시점에 같은 상품인지 모르면 받고, 확정 때 합친다(ItemSlotPort.fill)
    private fun rejectIfRegistered(
        userId: UUID,
        link: ProductLink,
    ) {
        val existing = wishOfSameItem(userId, link) ?: wishRequestedWith(userId, ParseInput.Link(link))
        existing?.let { throw AlreadyRegisteredException.wish(WishErrorCode.ALREADY_EXISTS, it) }
    }

    private fun wishOfSameItem(
        userId: UUID,
        link: ProductLink,
    ): Long? {
        val canonical = NormalizedInput.of(ParseInput.Link(link))?.asCanonical() ?: return null
        return wishRepository.findIdByUserIdAndItemCanonical(userId, canonical)
    }

    // 단축링크처럼 정체 URL 과 다른 URL 로 다시 담는 경우는 요청 이력으로 본다
    private fun wishRequestedWith(
        userId: UUID,
        input: ParseInput,
    ): Long? {
        val normalized = NormalizedInput.of(input) ?: return null
        return wishRepository.findIdByUserIdAndNormalizedInput(userId, normalized)
    }

    @Transactional
    fun applyUserInput(command: ApplyWishUserInputCommand): WishResult {
        val wish = wishRepository.findByIdForUpdate(command.wishId) ?: throw WishException.notFound()
        wish.verifyOwnedBy(command.userId)
        val values = itemCardService.cardOf(wish.cardQuery()).userInputValues(command.userInput)
        val recorded = userInputRecorder.record(wish.slot, wish.itemId, values, createdBy = command.userId)
        val survivor = survivorOf(wish, recorded.fill)
        command.memo?.let { writeMemo(survivor, it) }
        return WishResult(wish = survivor, item = recorded.item, card = itemCardService.cardOf(survivor.cardQuery()))
    }

    // 이미지를 올리기 전에 거절해 S3 에 고아 객체가 남지 않게 한다. 최종 판정은 applyUserInput 이 락 안에서 다시 한다
    @Transactional(readOnly = true)
    fun validateUserInput(command: RecoverWishItemCommand) {
        val wish = wishRepository.findById(command.wishId) ?: throw WishException.notFound()
        wish.verifyOwnedBy(command.userId)
        itemCardService.cardOf(wish.cardQuery()).userInputValues(command.userInput(PRE_UPLOAD_VALIDATION_IMAGE_URL))
    }

    // 합치기가 메모를 옮기는 동안 잠금을 기다린다. 잠그지 않으면 옮긴 뒤 사라지는 위시에 쓴 메모가 조용히 버려진다
    @Transactional
    fun updateMemo(command: UpdateWishMemoCommand): WishResult {
        val wish = wishRepository.findByIdForUpdate(command.wishId) ?: throw WishException.notFound()
        wish.verifyOwnedBy(command.userId)
        writeMemo(wish, command.memo)
        return WishResult(wish = wish, item = wish.itemId?.let(itemRepository::findById), card = itemCardService.cardOf(wish.cardQuery()))
    }

    // 아이템이 없는 위시는 같은 입력으로 다시 돌려도 실패하므로 사용자 입력으로 보내려고 409 로 막는다
    @Transactional
    fun refresh(command: RefreshWishItemCommand): WishResult {
        val wish = wishRepository.findByIdForUpdate(command.wishId) ?: throw WishException.notFound()
        wish.verifyOwnedBy(command.userId)
        val item = wish.itemId?.let(itemRepository::findById)
        val card = itemCardService.cardOf(wish.cardQuery())
        if (card.isParsing()) return WishResult(wish = wish, item = item, card = card)
        item ?: throw WishException.failedNotRefreshable()
        parsingEnqueuer.enqueue(wish.slot, ParsePurpose.REFRESH, item.refreshInput())
        return WishResult(wish = wish, item = item, card = itemCardService.cardOf(wish.cardQuery()))
    }

    private fun writeMemo(
        wish: Wish,
        rawMemo: String,
    ) {
        val memo = WishMemo.written(wish.getId(), rawMemo)
        if (memo.sameContentAs(wishMemoRepository.findCurrentByWishId(wish.getId()))) return
        wishMemoRepository.save(memo)
    }

    private fun survivorOf(
        wish: Wish,
        fill: ItemSlotFill,
    ): Wish =
        when (fill) {
            ItemSlotFill.Filled -> wish
            is ItemSlotFill.AlreadyHeld -> wishRepository.findById(fill.holder.id) ?: error("위시 ${fill.holder.id} 가 없다")
        }
}

private const val PRE_UPLOAD_VALIDATION_IMAGE_URL = "https://validation.invalid/pre-upload.png"
