package com.depromeet.piki.wishlist.service

import com.depromeet.piki.common.ratelimit.ItemQuotaGuard
import com.depromeet.piki.common.storage.ImageStorage
import com.depromeet.piki.image.service.ImagePresignService
import com.depromeet.piki.image.service.dto.PresignedRawUpload
import com.depromeet.piki.item.domain.ItemErrorCode
import com.depromeet.piki.item.repository.ItemSnapshotRepository
import com.depromeet.piki.item.service.ItemCardService
import com.depromeet.piki.item.service.ItemRegistrar
import com.depromeet.piki.item.service.UniqueConflictRetry
import com.depromeet.piki.product.source.SourcePlatformResolver
import com.depromeet.piki.tournament.service.TournamentWinnerReader
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.user.service.UserService
import com.depromeet.piki.item.service.ItemCard
import com.depromeet.piki.wishlist.domain.Wish
import com.depromeet.piki.wishlist.domain.WishCursor
import com.depromeet.piki.wishlist.domain.WishException
import com.depromeet.piki.wishlist.repository.WishMemoRepository
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
    private val wishMemoRepository: WishMemoRepository,
    private val itemSnapshotRepository: ItemSnapshotRepository,
    private val itemCardService: ItemCardService,
    private val itemQuotaGuard: ItemQuotaGuard,
    private val userService: UserService,
    private val sourcePlatformResolver: SourcePlatformResolver,
    private val tournamentWinnerReader: TournamentWinnerReader,
    private val wishFolderPort: WishFolderPort,
) {
    private fun WishResult.withSourcePlatform(): WishResult = copy(sourcePlatform = sourcePlatformResolver.resolve(card.sourceLink))

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

    // 폴더·제외 폴더·검색어로 거른다. 제외와 검색어는 페이지를 읽은 뒤 거르므로, 한 페이지가 찰 때까지 다음 묶음을 더 읽는다.
    // 검색어는 화면 이름(사용자가 고친 이름 포함)과 비교해야 해 카드를 계산한 뒤에만 거를 수 있다.
    // 거르는 조건이 없으면 첫 묶음이 곧 한 페이지(size + 1)라 예전과 같은 쿼리 하나로 끝난다.
    //
    // 조회지만 일부러 트랜잭션으로 묶지 않는다(#1270 리뷰). 검색어가 드물게 맞으면 묶음을 여러 번 읽는데, 트랜잭션 하나로 묶으면
    // 그동안 DB 연결 하나를 계속 쥐어 다른 API 가 연결을 기다린다. 묶지 않으면 쿼리마다 연결을 빌렸다 바로 돌려준다
    // (open-in-view 꺼짐). 읽기만 하고 잠그지 않아 묶음마다 시점이 달라도 문제없고, 묶음 사이에 지워진 위시는 빠진다.
    fun getWishlist(query: GetWishlistQuery): WishlistResult {
        requireMember(query.userId)
        val size = query.size.value
        val chunkSize = chunkSizeOf(size, query)

        // 다음 페이지가 있는지 알려고 한 개를 더 모은다
        val matched = mutableListOf<PositionedWish>()
        forEachMatch(query, query.cursor?.position, chunkSize) { matched += it; matched.size <= size }

        val hasNext = matched.size > size
        val page = matched.take(size)
        val pageWishes = page.map { it.wish }
        // 우승 칩(#1236) — 이 페이지에 보이는 상품만 한 번에 물어 N+1 을 만들지 않는다
        val winnersByItem = tournamentWinnerReader.findWinnerTournamentIdsByItem(query.userId, pageWishes.mapNotNull { it.itemId })
        val foldersByWish = wishFolderPort.folderIdsOf(pageWishes.map { it.getId() })
        val entries =
            page.map { positioned ->
                val wish = positioned.wish
                WishResult(
                    wish = wish,
                    card = requireNotNull(positioned.card) { "거른 항목은 카드를 계산해 둔다" },
                    winnerTournamentIds = wish.itemId?.let { winnersByItem[it] }.orEmpty(),
                    folderIds = foldersByWish[wish.getId()].orEmpty(),
                ).withSourcePlatform()
            }

        val nextCursor = page.lastOrNull()?.position?.toString().takeIf { hasNext }
        return WishlistResult(entries = entries, nextCursor = nextCursor, hasNext = hasNext)
    }

    // "상품 999+" 와 "검색 결과 N개" 의 개수. 목록과 같은 거르기를 쓴다.
    // 검색어가 없으면 COUNT 쿼리로 끝난다. 검색어가 있으면 화면 이름을 계산해야 해 내 위시를 끝까지 묶음으로 읽는다.
    // 목록처럼 트랜잭션으로 묶지 않아 오래 걸려도 DB 연결을 쥐지 않는다
    fun countWishes(query: GetWishlistQuery): Long {
        requireMember(query.userId)
        query.search ?: return countWithoutSearch(query)
        var count = 0L
        forEachMatch(query, after = null, chunkSize = FILTER_CHUNK_SIZE) {
            count++
            true
        }
        return count
    }

    private fun countWithoutSearch(query: GetWishlistQuery): Long {
        query.folderId?.let { return wishFolderPort.countInFolder(query.userId, it, query.excludeFolderId) }
        // 쿼리 하나로 센다. 전체 수와 폴더 수를 따로 세어 빼면 사이에 지워진 위시로 음수가 될 수 있다(#1273 리뷰)
        query.excludeFolderId?.let { return wishFolderPort.countOutsideFolder(query.userId, it) }
        return wishRepository.countByUserId(query.userId)
    }

    // 거르기(제외 폴더 · 검색어)를 통과한 위시를 순서대로 넘긴다. onMatch 가 false 를 돌려주면 멈춘다
    private fun forEachMatch(
        query: GetWishlistQuery,
        after: Long?,
        chunkSize: Int,
        onMatch: (PositionedWish) -> Boolean,
    ) {
        var cursor = after
        while (true) {
            val chunk = chunkOf(query, cursor, chunkSize)
            // 빈 묶음이어도 묻는다 — 남의 폴더는 위시가 없어도 404 여야 한다
            val excluded = excludedAmong(query, chunk)
            val candidates = chunk.wishes.filterNot { it.wish.getId() in excluded }
            val cards = itemCardService.cardsOf(candidates.map { it.wish.cardQuery() })
            for (candidate in candidates) {
                val card = cards.getValue(candidate.wish.cardQuery())
                if (matchesSearch(query, card) && !onMatch(candidate.copy(card = card))) return
            }
            cursor = chunk.lastPosition ?: return
            if (chunk.exhausted) return
        }
    }

    private fun chunkSizeOf(
        size: Int,
        query: GetWishlistQuery,
    ): Int {
        // 거르는 조건이 없으면 한 페이지만 읽는다
        query.excludeFolderId ?: query.search ?: return size + 1
        return maxOf(size + 1, FILTER_CHUNK_SIZE)
    }

    // 제외 폴더에 든 위시 중 이 묶음에 있는 것만 묻는다. 폴더 연결 전체를 읽지 않는다(#1270 리뷰)
    private fun excludedAmong(
        query: GetWishlistQuery,
        chunk: WishChunk,
    ): Set<Long> {
        val folderId = query.excludeFolderId ?: return emptySet()
        return wishFolderPort.wishIdsInFolder(query.userId, folderId, chunk.wishes.map { it.wish.getId() })
    }

    private fun matchesSearch(
        query: GetWishlistQuery,
        card: ItemCard,
    ): Boolean {
        val search = query.search ?: return true
        return search.matches(card.display?.name)
    }

    // 폴더로 거르면 폴더에 넣은 순서(연결 위치), 아니면 위시 id 순서로 한 묶음을 읽는다
    private fun chunkOf(
        query: GetWishlistQuery,
        after: Long?,
        limit: Int,
    ): WishChunk {
        val folderId = query.folderId ?: return wishRepository.findPage(query.userId, after?.let(WishCursor::at), limit).let { wishes ->
            WishChunk(wishes.map { PositionedWish(it.getId(), it) }, lastPosition = wishes.lastOrNull()?.getId(), exhausted = wishes.size < limit)
        }
        val entries = wishFolderPort.entriesInFolder(query.userId, folderId, after, limit)
        val wishById = wishRepository.findAllByIds(entries.map { it.wishId }).associateBy { it.getId() }
        return WishChunk(
            // 폴더 쿼리와 위시 쿼리 사이에 지워진 위시는 빠진다
            wishes = entries.mapNotNull { entry -> wishById[entry.wishId]?.let { PositionedWish(entry.position, it) } },
            lastPosition = entries.lastOrNull()?.position,
            exhausted = entries.size < limit,
        )
    }

    private data class PositionedWish(
        val position: Long,
        val wish: Wish,
        val card: ItemCard? = null,
    )

    private data class WishChunk(
        val wishes: List<PositionedWish>,
        val lastPosition: Long?,
        val exhausted: Boolean,
    )

    private fun WishResult.withFolderIds(): WishResult = copy(folderIds = wishFolderPort.folderIdsOf(listOf(wish.getId()))[wish.getId()].orEmpty())

    @Transactional(readOnly = true)
    fun getWish(query: GetWishQuery): WishDetailResult {
        requireMember(query.userId)
        val wish = wishRepository.findById(query.wishId) ?: throw WishException.notFound()
        wish.verifyOwnedBy(query.userId)
        val itemId = wish.itemId
        val winnerTournamentIds = itemId?.let { tournamentWinnerReader.findWinnerTournamentIdsByItem(query.userId, listOf(it))[it] }.orEmpty()
        return WishDetailResult(
            wishResult =
                WishResult(wish = wish, card = itemCardService.cardOf(wish.cardQuery()), winnerTournamentIds = winnerTournamentIds)
                    .withSourcePlatform()
                    .withFolderIds(),
            memo = wishMemoRepository.findCurrentByWishId(wish.getId())?.content,
            history = itemId?.let { itemSnapshotRepository.findPriceHistoryByItemId(it, PRICE_HISTORY_LIMIT) }.orEmpty(),
        )
    }

    // S3 업로드가 트랜잭션 안에 들어가지 않게 영속화만 applyUserInput 에 맡긴다
    fun recoverWishItem(command: RecoverWishItemCommand): WishResult {
        requireMember(command.userId)
        command.memoOnly()?.let { return wishPersistenceService.updateMemo(it).withSourcePlatform().withFolderIds() }
        command.image?.let { wishPersistenceService.validateUserInput(command) }
        val imageUrl =
            command.image?.let {
                imageStorage.upload(it.bytes, "items/${UUID.randomUUID()}.${it.extension}", it.mimeType)
            }
        return UniqueConflictRetry
            .once("wish=${command.wishId}") { wishPersistenceService.applyUserInput(command.toApplyUserInput(imageUrl)) }
            .withSourcePlatform()
            .withFolderIds()
    }

    fun refreshWishItem(command: RefreshWishItemCommand): WishResult {
        requireMember(command.userId)
        itemQuotaGuard.consume(command.userId, 1, ItemErrorCode.QUOTA_EXCEEDED)
        return wishPersistenceService.refresh(command).withSourcePlatform().withFolderIds()
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

        // 거르는 조건이 있을 때 한 번에 읽는 위시 수. 걸러져 줄어들 몫을 감안해 페이지보다 크게 읽는다
        private const val FILTER_CHUNK_SIZE = 100
    }
}
