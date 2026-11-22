package com.depromeet.piki.profile.service

import com.depromeet.piki.block.repository.UserBlockRepository
import com.depromeet.piki.folder.repository.FolderRepository
import com.depromeet.piki.folder.repository.FolderWishRepository
import com.depromeet.piki.friendship.repository.FriendshipRepository
import com.depromeet.piki.item.service.ItemCardService
import com.depromeet.piki.product.source.SourcePlatformResolver
import com.depromeet.piki.profile.domain.ProfileExposure
import com.depromeet.piki.profile.domain.ProfileRelation
import com.depromeet.piki.profile.service.dto.PublicFolderDetail
import com.depromeet.piki.profile.service.dto.PublicFolderSummary
import com.depromeet.piki.profile.service.dto.PublicProfile
import com.depromeet.piki.user.domain.User
import com.depromeet.piki.user.domain.UserException
import com.depromeet.piki.user.service.UserService
import com.depromeet.piki.wishlist.repository.WishRepository
import com.depromeet.piki.wishlist.service.dto.WishResult
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 다른 사람 프로필과 공개 폴더 상세(#1290). 내 프로필도 같은 API 로 본다 — 관계만 본인으로 갈린다.
 *
 * **무엇을 보여줄지는 [ProfileExposure] 하나가 정한다.** 프로필 공개 여부·차단·소유 여부를 그 객체가 받고,
 * 여기서는 그 답만 쓴다. 판정이 흩어지면 "프로필에선 가렸는데 폴더 상세로는 들어가지는" 어긋남이 생긴다.
 *
 * 닉네임과 프로필 이미지는 항상 공개다(#1285). 가리는 것은 폴더·위시뿐이다.
 */
@Service
class PublicProfileService(
    private val userService: UserService,
    private val friendshipRepository: FriendshipRepository,
    private val userBlockRepository: UserBlockRepository,
    private val folderRepository: FolderRepository,
    private val folderWishRepository: FolderWishRepository,
    private val wishRepository: WishRepository,
    private val profileCountReader: ProfileCountReader,
    private val itemCardService: ItemCardService,
    private val sourcePlatformResolver: SourcePlatformResolver,
) {
    /**
     * 프로필 조회. 비로그인도 부를 수 있다([viewerId] 가 null).
     *
     * 없는 사용자·탈퇴한 사용자는 404 다 — 친구·차단과 같은 판단으로, 탈퇴를 409 로 알리면
     * 그 계정이 있었다는 사실이 드러난다.
     *
     * 상대가 나를 차단한 경우와 상대가 프로필을 비공개로 둔 경우의 **응답이 같다**(폴더 없음, 관계 친구 아님).
     * 가르면 "저 사람이 나를 차단했다" 가 드러난다. 반대로 내가 차단한 경우는 내가 한 일이라 숨기지 않는다.
     */
    @Transactional(readOnly = true)
    fun getProfile(
        viewerId: UUID?,
        ownerId: UUID,
    ): PublicProfile {
        val owner = activeOwner(ownerId)
        val relation = relationOf(viewerId, ownerId)
        val exposure =
            ProfileExposure.of(
                viewerId = viewerId,
                ownerId = ownerId,
                isOwnerProfilePublic = owner.isProfilePublic,
                isBlocked = relation == ProfileRelation.BLOCKED || blockedMe(viewerId, ownerId),
            )
        // 아무것도 못 보는 사람이면 폴더를 읽지 않고 끝낸다. **가드가 아니라 질의 절약이다** —
        // 아래 publicFoldersOf 의 필터가 같은 판정을 이미 하므로, 이 줄을 지워도 응답은 같다(실측).
        val folders =
            if (exposure.canSeeFolder(isFolderPublic = true)) publicFoldersOf(ownerId, exposure) else emptyList()
        return PublicProfile(
            userId = ownerId,
            nickname = owner.nickname,
            profileImage = owner.profileImage,
            relation = relation,
            folders = folders,
        )
    }

    /**
     * 공개 폴더 하나와 그 안의 위시 목록.
     *
     * **볼 수 없는 이유를 가르지 않고 전부 404 다** — 비공개 폴더·지운 폴더·프로필 비공개·차단이 같은 응답이다.
     * 가르면 "그 폴더는 있는데 비공개구나" 가 드러나고, 차단 사실도 새어 나간다.
     *
     * 위시 카드는 내 목록과 같은 계산([ItemCardService])을 쓴다. 다만 그 위시가 **주인의 어느 폴더에 들어
     * 있는지**는 내려보내지 않는다 — 주인의 비공개 폴더 id 가 보는 사람에게 드러나기 때문이다.
     */
    @Transactional(readOnly = true)
    fun getPublicFolder(
        viewerId: UUID?,
        ownerId: UUID,
        folderId: Long,
    ): PublicFolderDetail {
        val owner = activeOwner(ownerId)
        val exposure =
            ProfileExposure.of(
                viewerId = viewerId,
                ownerId = ownerId,
                isOwnerProfilePublic = owner.isProfilePublic,
                isBlocked = blockedEitherWay(viewerId, ownerId),
            )
        val folder = folderRepository.findOwned(folderId, ownerId) ?: throw UserException.notFound()
        if (!exposure.canSeeFolder(folder.isPublic)) throw UserException.notFound()

        val wishIds = folderWishRepository.findWishIdsByFolderId(folderId)
        val wishes = wishRepository.findAllByIds(wishIds).filter { it.isActive() }
        val cards = itemCardService.cardsOf(wishes.map { it.cardQuery() })
        return PublicFolderDetail(
            folderId = folder.getId(),
            name = folder.name,
            wishes =
                wishes.map { wish ->
                    val card = cards.getValue(wish.cardQuery())
                    WishResult(
                        wish = wish,
                        card = card,
                        sourcePlatform = sourcePlatformResolver.resolve(card.sourceLink),
                    )
                },
        )
    }

    // 보는 사람이 볼 수 있는 폴더만. 수는 폴더마다 세지 않고 한 번에 묶어 읽는다 —
    // 폴더가 수십 개인 사람의 프로필을 열 때마다 그만큼 질의가 나가면 N+1 이다.
    private fun publicFoldersOf(
        ownerId: UUID,
        exposure: ProfileExposure,
    ): List<PublicFolderSummary> {
        val visible = folderRepository.findAllByUserId(ownerId).filter { exposure.canSeeFolder(it.isPublic) }
        val wishIdsByFolder = folderWishRepository.findLiveWishIdsByFolderIds(visible.map { it.getId() })
        return visible.map { folder ->
            PublicFolderSummary(
                folderId = folder.getId(),
                name = folder.name,
                wishCount = wishIdsByFolder[folder.getId()].orEmpty().size,
            )
        }
    }

    private fun activeOwner(ownerId: UUID): User {
        val owner = userService.findActiveByIds(listOf(ownerId)).firstOrNull()
        return owner ?: throw UserException.notFound()
    }

    // 내가 차단했으면 그 사실을 숨기지 않는다 — 내가 한 일이고, 화면에 차단 해제 버튼이 뜬다
    private fun relationOf(
        viewerId: UUID?,
        ownerId: UUID,
    ): ProfileRelation {
        viewerId ?: return ProfileRelation.NOT_FRIEND
        if (viewerId == ownerId) return ProfileRelation.SELF
        if (userBlockRepository.exists(viewerId, ownerId)) return ProfileRelation.BLOCKED
        if (friendshipRepository.exists(viewerId, ownerId)) return ProfileRelation.FRIEND
        return ProfileRelation.NOT_FRIEND
    }

    // 상대가 나를 차단했는지. 관계로는 드러내지 않고 노출 판정에만 넣는다
    private fun blockedMe(
        viewerId: UUID?,
        ownerId: UUID,
    ): Boolean {
        viewerId ?: return false
        return userBlockRepository.exists(ownerId, viewerId)
    }

    private fun blockedEitherWay(
        viewerId: UUID?,
        ownerId: UUID,
    ): Boolean {
        viewerId ?: return false
        return userBlockRepository.existsBetween(viewerId, ownerId)
    }

    // 프로필 집계는 친구 목록과 같은 값을 쓴다 — 중복 질의를 만들지 않게 reader 를 공유한다
    @Transactional(readOnly = true)
    fun countsOf(ownerId: UUID): ProfileCounts = profileCountReader.countsOf(listOf(ownerId)).getValue(ownerId)
}
