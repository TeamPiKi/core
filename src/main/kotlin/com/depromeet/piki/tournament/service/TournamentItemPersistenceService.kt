package com.depromeet.piki.tournament.service

import com.depromeet.piki.common.exception.AlreadyRegisteredException
import com.depromeet.piki.image.domain.ProductImageKey
import com.depromeet.piki.item.domain.NormalizedInput
import com.depromeet.piki.item.domain.ParseInput
import com.depromeet.piki.item.domain.ParsePurpose
import com.depromeet.piki.item.domain.UserInput
import com.depromeet.piki.item.service.ItemCardService
import com.depromeet.piki.item.service.ParsingEnqueuer
import com.depromeet.piki.item.service.UserInputRecorder
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.tournament.domain.Tournament
import com.depromeet.piki.tournament.domain.TournamentItem
import com.depromeet.piki.tournament.event.TournamentItemAdded
import com.depromeet.piki.tournament.repository.TournamentItemRepository
import com.depromeet.piki.tournament.repository.TournamentRepository
import com.depromeet.piki.tournament.repository.TournamentUserRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

// TournamentItemService 의 외부 호출이 트랜잭션 밖에 있도록 저장만 별도 빈으로 둔다
@Service
class TournamentItemPersistenceService(
    private val tournamentRepository: TournamentRepository,
    private val tournamentUserRepository: TournamentUserRepository,
    private val tournamentItemRepository: TournamentItemRepository,
    private val eventPublisher: ApplicationEventPublisher,
    private val itemCardService: ItemCardService,
    private val userInputRecorder: UserInputRecorder,
    private val parsingEnqueuer: ParsingEnqueuer,
) {
    // 이미 담긴 링크면 한도를 깎기 전에 409 로 끊는다(#973). 최종 판정은 persistLinkItem 이 트랜잭션 안에서 다시 한다
    @Transactional(readOnly = true)
    fun rejectIfAlreadyAdded(
        tournamentId: Long,
        link: ProductLink,
    ) {
        rejectIfAdded(tournamentId, link)
    }

    // 이미 아는 링크여도 아이템 없이 올리고 새로 파싱한다. 성공하면 정체 URL 이 같은 아이템에 붙는다
    @Transactional
    fun persistLinkItem(
        userId: UUID,
        tournamentId: Long,
        link: ProductLink,
    ): Long {
        rejectIfWontFit(userId, tournamentId, 1)
        rejectIfAdded(tournamentId, link)
        val tournamentItemId = persistPending(userId, tournamentId, ParseInput.Link(link))
        eventPublisher.publishEvent(TournamentItemAdded(tournamentId = tournamentId, actorId = userId))
        return tournamentItemId
    }

    private fun rejectIfAdded(
        tournamentId: Long,
        link: ProductLink,
    ) {
        val duplicated = entryOfSameItem(tournamentId, link) ?: entryRequestedWith(tournamentId, link)
        duplicated?.let { throw AlreadyRegisteredException.tournamentItem(TournamentErrorCode.DUPLICATE_TOURNAMENT_ITEM, it) }
    }

    private fun entryOfSameItem(
        tournamentId: Long,
        link: ProductLink,
    ): Long? {
        val canonical = NormalizedInput.of(ParseInput.Link(link))?.asCanonical() ?: return null
        return tournamentItemRepository.findIdByTournamentIdAndItemCanonical(tournamentId, canonical)
    }

    // 단축링크처럼 정체 URL 과 다른 URL 로 다시 올리는 경우는 요청 이력으로 본다
    private fun entryRequestedWith(
        tournamentId: Long,
        link: ProductLink,
    ): Long? {
        val normalized = NormalizedInput.of(ParseInput.Link(link)) ?: return null
        return tournamentItemRepository.findIdByTournamentIdAndNormalizedInput(tournamentId, normalized)
    }

    private fun rejectIfWontFit(
        userId: UUID,
        tournamentId: Long,
        incomingCount: Int,
    ) {
        validateAddable(lockTournament(tournamentId), userId, tournamentId)
        if (!hasRoomFor(tournamentId, incomingCount)) throw TournamentException.tooManyTournamentItems()
    }

    // 토너먼트 행 잠금이 첫 문장이어야 한다. 같은 토너먼트로 오는 confirm 이 여기서 줄을 서므로, 뒤이은 사전 필터가
    // 앞선 confirm 이 커밋한 요청을 정확히 본다. 일반 읽기가 먼저 오면 REPEATABLE READ 스냅샷이 잠금 전에 고정된다.
    @Transactional
    fun registerImages(
        imageKeys: List<ProductImageKey>,
        userId: UUID,
        tournamentId: Long,
    ): List<Long> {
        validateAddable(lockTournament(tournamentId), userId, tournamentId)
        val newKeys = imageKeys.distinct() - tournamentItemRepository.findRequestedImageKeys(tournamentId, imageKeys)
        if (newKeys.isEmpty()) return emptyList()
        if (!hasRoomFor(tournamentId, newKeys.size)) throw TournamentException.tooManyTournamentItems()
        val persisted = newKeys.map { persistPending(userId, tournamentId, ParseInput.Image(it)) }
        eventPublisher.publishEvent(TournamentItemAdded(tournamentId = tournamentId, actorId = userId))
        return persisted
    }

    private fun persistPending(
        userId: UUID,
        tournamentId: Long,
        input: ParseInput,
    ): Long {
        val tournamentItem = tournamentItemRepository.save(TournamentItem(tournamentId = tournamentId, userId = userId, itemId = null))
        parsingEnqueuer.enqueue(tournamentItem.slot, ParsePurpose.REGISTER, input)
        return tournamentItem.getId()
    }

    // 잠금이 트랜잭션의 첫 문장이어야 한다. 일반 읽기가 먼저 오면 REPEATABLE READ 스냅샷이 잠금 전에 고정돼 그 사이 파싱 성공이 확정한 아이템의 버전을 못 본다
    @Transactional
    fun applyUserInput(
        userId: UUID,
        tournamentId: Long,
        tournamentItemId: Long,
        name: String?,
        price: Int?,
        imageUrl: String?,
        currency: String?,
    ) {
        val locked = tournamentItemRepository.findByIdForUpdate(tournamentItemId)
        val tournament = verifyEditable(userId, tournamentId)
        val tournamentItem = locked ?: throw TournamentException.notFoundTournamentItem()
        verifyEditor(tournamentItem, tournamentId, userId)
        val values = itemCardService.cardOf(tournamentItem.cardQuery(tournament)).userInputValues(UserInput(name = name, price = price, imageUrl = imageUrl, currency = currency))
        userInputRecorder.record(tournamentItem.slot, tournamentItem.itemId, values, createdBy = userId)
    }

    // 이미지를 올리기 전에 거절해 S3 에 고아 객체가 남지 않게 한다. 최종 판정은 applyUserInput 이 락 안에서 다시 한다
    @Transactional(readOnly = true)
    fun validateUserInput(
        userId: UUID,
        tournamentId: Long,
        tournamentItemId: Long,
        name: String?,
        price: Int?,
        currency: String?,
    ) {
        val tournament = verifyEditable(userId, tournamentId)
        val tournamentItem = tournamentItemRepository.findById(tournamentItemId) ?: throw TournamentException.notFoundTournamentItem()
        verifyEditor(tournamentItem, tournamentId, userId)
        itemCardService.cardOf(tournamentItem.cardQuery(tournament)).userInputValues(UserInput(name = name, price = price, imageUrl = PRE_UPLOAD_VALIDATION_IMAGE_URL, currency = currency))
    }

    private fun verifyEditable(
        userId: UUID,
        tournamentId: Long,
    ): Tournament {
        val tournament = tournamentRepository.findTournamentById(tournamentId) ?: throw TournamentException.notFoundTournament()
        tournament.checkItemsModifiable()
        requireParticipant(tournamentId, userId)
        return tournament
    }

    private fun verifyEditor(
        tournamentItem: TournamentItem,
        tournamentId: Long,
        userId: UUID,
    ) {
        if (tournamentItem.tournamentId != tournamentId) throw TournamentException.notFoundTournamentItem()
        if (tournamentItem.userId != userId) throw TournamentException.forbiddenTournament()
    }

    // 이미지 업로드(외부 호출) 전에 권한·상태·복제를 미리 검증해 거부될 요청이 S3 에 orphan raw 를 남기지 않게 한다.
    // 정원은 동시성 때문에 persist 의 FOR UPDATE(rejectIfWontFit)가 최종 판정하므로 여기선 제외한다(다층 방어).
    @Transactional(readOnly = true)
    fun verifyCanAddItems(
        userId: UUID,
        tournamentId: Long,
    ) {
        val tournament =
            tournamentRepository.findTournamentById(tournamentId)
                ?: throw TournamentException.notFoundTournament()
        validateAddable(tournament, userId, tournamentId)
    }

    private fun lockTournament(tournamentId: Long): Tournament =
        tournamentRepository.findTournamentByIdForUpdate(tournamentId)
            ?: throw TournamentException.notFoundTournament()

    private fun validateAddable(
        tournament: Tournament,
        userId: UUID,
        tournamentId: Long,
    ) {
        tournament.checkItemsAddable()
        requireParticipant(tournamentId, userId)
    }

    private fun requireParticipant(
        tournamentId: Long,
        userId: UUID,
    ) {
        tournamentUserRepository.findByTournamentIdAndUserId(tournamentId, userId) ?: throw TournamentException.forbiddenTournament()
    }

    private fun hasRoomFor(
        tournamentId: Long,
        incomingCount: Int,
    ): Boolean = tournamentItemRepository.countByTournamentId(tournamentId) + incomingCount <= TOURNAMENT_MAX_ITEM_COUNT
}

// 사전 검증은 저장하지 않아 업로드 예정 이미지 자리를 자리표시 값으로 채운다
private const val PRE_UPLOAD_VALIDATION_IMAGE_URL = "https://validation.invalid/pre-upload.png"
