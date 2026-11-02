package com.depromeet.piki.folder.service

import com.depromeet.piki.folder.domain.Folder
import com.depromeet.piki.folder.domain.FolderContents
import com.depromeet.piki.folder.domain.FolderException
import com.depromeet.piki.folder.domain.FolderName
import com.depromeet.piki.folder.domain.FolderWish
import com.depromeet.piki.folder.domain.FolderWishIds
import com.depromeet.piki.folder.domain.FolderWishSelection
import com.depromeet.piki.folder.repository.FolderRepository
import com.depromeet.piki.folder.repository.FolderWishRepository
import com.depromeet.piki.folder.service.dto.FolderMoveResult
import com.depromeet.piki.folder.service.dto.FolderResult
import com.depromeet.piki.folder.service.dto.FolderWishCountResult
import com.depromeet.piki.item.service.ItemCardService
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.user.service.UserService
import com.depromeet.piki.wishlist.domain.WishException
import com.depromeet.piki.wishlist.repository.WishRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

// 불러오기, FolderContents 에 묻기, 결과대로 저장하기만 한다. 넣기·빼기·이동의 분기는 FolderContents 에 있다
@Service
class FolderService(
    private val folderRepository: FolderRepository,
    private val folderWishRepository: FolderWishRepository,
    private val wishRepository: WishRepository,
    private val itemCardService: ItemCardService,
    private val userService: UserService,
) {
    // 폴더는 위시를 묶는 기능이라 위시리스트와 같은 회원 전용 계약을 그대로 쓴다
    private fun requireMember(userId: UUID) {
        val user = userService.findActiveById(userId)
        if (user.identityType != IdentityType.MEMBER) throw WishException.guestCannotUseWishlist()
    }

    // 쓰기는 user 행을 첫 락으로 잡아 탈퇴 cascade 와 줄 세운다. 안 잡으면 탈퇴가 폴더를 지운 뒤 만들기가 끼어들어 폴더가 남는다
    private fun requireMemberForUpdate(userId: UUID) {
        val user = userService.findActiveByIdForUpdate(userId)
        if (user.identityType != IdentityType.MEMBER) throw WishException.guestCannotUseWishlist()
    }

    @Transactional
    fun create(
        userId: UUID,
        name: FolderName,
    ): FolderResult {
        requireMemberForUpdate(userId)
        val folder = folderRepository.save(Folder(userId = userId, name = name))
        return FolderResult(folder = folder, wishCount = 0, coverImageUrls = emptyList())
    }

    @Transactional(readOnly = true)
    fun getFolders(userId: UUID): List<FolderResult> {
        requireMember(userId)
        return resultsOf(folderRepository.findAllByUserId(userId))
    }

    @Transactional
    fun rename(
        userId: UUID,
        folderId: Long,
        name: FolderName,
    ): FolderResult {
        requireMemberForUpdate(userId)
        val folder = folderRepository.findOwnedForUpdate(folderId, userId) ?: throw FolderException.notFound()
        folder.rename(name)
        return resultsOf(listOf(folder)).single()
    }

    // 폴더와 연결만 지운다. 위시는 전체 위시에 남는다
    @Transactional
    fun delete(
        userId: UUID,
        folderId: Long,
    ) {
        requireMemberForUpdate(userId)
        val folder = folderRepository.findOwnedForUpdate(folderId, userId) ?: throw FolderException.notFound()
        folderWishRepository.deleteAllByFolderId(folder.getId())
        folder.delete()
    }

    @Transactional
    fun putWishes(
        userId: UUID,
        folderId: Long,
        wishIds: FolderWishIds,
    ): FolderWishCountResult {
        requireMemberForUpdate(userId)
        val folder = folderRepository.findOwnedForUpdate(folderId, userId) ?: throw FolderException.notFound()
        verifyLiveWishesOf(userId, wishIds.values)
        val adding = contentsOf(folder).put(wishIds.values)
        folderWishRepository.saveAll(adding.map { FolderWish(folderId = folder.getId(), wishId = it) })
        return countOf(folder)
    }

    // 폴더에서만 빠진다. 위시는 지워지지 않는다. 전체 선택이면 폴더의 연결을 전부 지운다(개수 제한 없음)
    @Transactional
    fun takeOutWishes(
        userId: UUID,
        folderId: Long,
        selection: FolderWishSelection,
    ): FolderWishCountResult {
        requireMemberForUpdate(userId)
        val folder = folderRepository.findOwnedForUpdate(folderId, userId) ?: throw FolderException.notFound()
        when (selection) {
            FolderWishSelection.All -> folderWishRepository.deleteAllByFolderId(folder.getId())
            is FolderWishSelection.Ids -> folderWishRepository.deleteByFolderIdAndWishIds(folder.getId(), contentsOf(folder).takeOut(selection.ids.values))
        }
        return countOf(folder)
    }

    // 빼기와 넣기를 한 트랜잭션에서 끝내 "두 폴더에 다 있음" 이나 "어디에도 없음" 이 남지 않는다
    @Transactional
    fun moveWishes(
        userId: UUID,
        folderId: Long,
        targetFolderId: Long,
        selection: FolderWishSelection,
    ): FolderMoveResult {
        requireMemberForUpdate(userId)
        // 두 폴더를 같은 순서로 잠가 반대 방향 이동이 동시에 와도 서로를 기다리며 멈추지 않게 한다
        val locked =
            listOf(folderId, targetFolderId).distinct().sorted().associateWith {
                folderRepository.findOwnedForUpdate(it, userId) ?: throw FolderException.notFound()
            }
        val source = locked.getValue(folderId)
        val target = locked.getValue(targetFolderId)
        val moving = movingWishIds(userId, source, selection)
        val move = contentsOf(source).moveTo(contentsOf(target), moving)
        folderWishRepository.deleteByFolderIdAndWishIds(source.getId(), move.takeOut)
        folderWishRepository.saveAll(move.putIntoTarget.map { FolderWish(folderId = target.getId(), wishId = it) })
        return FolderMoveResult(source = countOf(source), target = countOf(target))
    }

    private fun movingWishIds(
        userId: UUID,
        source: Folder,
        selection: FolderWishSelection,
    ): List<Long> =
        when (selection) {
            // 전체 선택은 이 폴더의 살아 있는 위시. 내 폴더에 든 위시라 소유 확인이 필요 없고, 지운 위시의 연결은 옮기지 않는다
            FolderWishSelection.All -> folderWishRepository.findLiveWishIdsByFolderIds(listOf(source.getId()))[source.getId()].orEmpty()
            // 옮기기는 빼기 뒤 넣기라 넣기와 같은 규칙을 쓴다. 안 거르면 원래 폴더에 남은 지운 위시의 연결이 대상으로 넘어간다
            is FolderWishSelection.Ids -> selection.ids.values.also { verifyLiveWishesOf(userId, it) }
        }

    // 남의 위시나 지워진 위시가 하나라도 섞이면 요청 전체를 거절한다. 위시 다건 삭제와 같은 403 이다
    private fun verifyLiveWishesOf(
        userId: UUID,
        wishIds: List<Long>,
    ) {
        val wishes = wishRepository.findAllByIds(wishIds)
        if (wishes.size != wishIds.size) throw WishException.forbiddenWishItems()
        wishes.forEach { it.verifyOwnedBy(userId) }
    }

    private fun contentsOf(folder: Folder): FolderContents = FolderContents(folder.getId(), folderWishRepository.findWishIdsByFolderId(folder.getId()))

    private fun countOf(folder: Folder): FolderWishCountResult = FolderWishCountResult(folderId = folder.getId(), wishCount = folderWishRepository.countLiveByFolderId(folder.getId()))

    // 표지는 최근 넣은 위시부터 이미지가 있는 것만 최대 3장. 연결 id 는 전부 읽지만(개수에도 쓰임) 비싼 카드 계산은 폴더당 앞쪽 후보로 묶는다
    private fun resultsOf(folders: List<Folder>): List<FolderResult> {
        val wishIdsByFolder = folderWishRepository.findLiveWishIdsByFolderIds(folders.map { it.getId() })
        val candidatesByFolder = wishIdsByFolder.mapValues { (_, ids) -> ids.take(COVER_CANDIDATE_COUNT) }
        val imageByWish = imageUrlsOf(candidatesByFolder.values.flatten().distinct())
        return folders.map { folder ->
            val candidates = candidatesByFolder[folder.getId()].orEmpty()
            FolderResult(
                folder = folder,
                wishCount = wishIdsByFolder[folder.getId()].orEmpty().size,
                coverImageUrls = candidates.mapNotNull { imageByWish[it] }.take(COVER_IMAGE_COUNT),
            )
        }
    }

    private fun imageUrlsOf(wishIds: List<Long>): Map<Long, String> {
        if (wishIds.isEmpty()) return emptyMap()
        val wishes = wishRepository.findAllByIds(wishIds)
        val cards = itemCardService.cardsOf(wishes.map { it.cardQuery() })
        return wishes
            .mapNotNull { wish -> cards.getValue(wish.cardQuery()).display?.imageUrl?.let { wish.getId() to it } }
            .toMap()
    }

    companion object {
        private const val COVER_IMAGE_COUNT = 3

        // 이미지가 없는 위시(파싱 중·실패)를 건너뛸 여유. 앞의 12개가 모두 이미지가 없으면 표지가 3장보다 적다
        private const val COVER_CANDIDATE_COUNT = 12
    }
}
