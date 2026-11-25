package com.depromeet.piki.explore.service

import com.depromeet.piki.explore.service.dto.CopyExploreWishCommand
import com.depromeet.piki.explore.service.dto.ExploreWishCopyResult
import com.depromeet.piki.explore.service.dto.GetExploreWishQuery
import com.depromeet.piki.item.service.UniqueConflictRetry
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.user.service.UserService
import com.depromeet.piki.wishlist.domain.Wish
import com.depromeet.piki.wishlist.domain.WishException
import com.depromeet.piki.wishlist.repository.WishRepository
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class ExploreWishCopier(
    private val exploreWishReader: ExploreWishReader,
    private val exploreWishCopyWriter: ExploreWishCopyWriter,
) {
    // 상세로 볼 수 있는 위시만 담을 수 있다. 같은 상품을 가리키는 내 위시를 만들 뿐 상품을 다시 추출하지 않는다.
    // 하트를 연달아 눌러 유니크 키에 걸리면 새 트랜잭션으로 한 번 더 실행해 먼저 생긴 위시를 돌려준다
    fun copy(command: CopyExploreWishCommand): ExploreWishCopyResult {
        val source = exploreWishReader.getWish(GetExploreWishQuery(viewerId = command.userId, wishId = command.wishId))
        val itemId = requireNotNull(source.wish.wish.itemId) { "상세로 볼 수 있는 위시는 상품이 정해져 있다" }
        return UniqueConflictRetry.once("explore.copy") { exploreWishCopyWriter.findOrCreate(command.userId, itemId) }
    }
}

@Component
class ExploreWishCopyWriter(
    private val userService: UserService,
    private val wishRepository: WishRepository,
) {
    @Transactional
    fun findOrCreate(
        userId: UUID,
        itemId: Long,
    ): ExploreWishCopyResult {
        // 탈퇴는 user 행을 먼저 잠그고 위시를 지운다. 같은 행을 잠가야 탈퇴 뒤에 위시가 끼어들지 않는다
        val user = userService.findActiveByIdForUpdate(userId)
        if (user.identityType != IdentityType.MEMBER) throw WishException.guestCannotUseWishlist()
        wishRepository.findByItemIdsAndUserId(listOf(itemId), userId).firstOrNull()?.let {
            return ExploreWishCopyResult(wishId = it.getId(), alreadySaved = true)
        }
        val created = wishRepository.save(Wish(userId = userId, itemId = itemId))
        return ExploreWishCopyResult(wishId = created.getId(), alreadySaved = false)
    }
}
