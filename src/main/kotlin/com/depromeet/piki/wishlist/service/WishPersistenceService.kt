package com.depromeet.piki.wishlist.service

import com.depromeet.piki.common.exception.AlreadyRegisteredException
import com.depromeet.piki.image.domain.PendingUploadContext
import com.depromeet.piki.image.service.PendingUploadClaimer
import com.depromeet.piki.item.domain.Item
import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.repository.ItemRepository
import com.depromeet.piki.item.repository.ItemSnapshotRepository
import com.depromeet.piki.item.service.ItemIdentityRecorder
import com.depromeet.piki.item.service.ItemSharingService
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.user.service.UserService
import com.depromeet.piki.wishlist.domain.Wish
import com.depromeet.piki.wishlist.domain.WishErrorCode
import com.depromeet.piki.wishlist.domain.WishException
import com.depromeet.piki.wishlist.repository.WishRepository
import com.depromeet.piki.wishlist.service.dto.WishWithItem
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

// WishlistService 에서 분리된 빈이다. 같은 빈 안에서 부르면 AOP proxy 를 안 거쳐 @Transactional 이 무력화된다.
@Service
class WishPersistenceService(
    private val wishRepository: WishRepository,
    private val itemRepository: ItemRepository,
    private val itemSnapshotRepository: ItemSnapshotRepository,
    private val pendingUploadClaimer: PendingUploadClaimer,
    private val userService: UserService,
    private val itemIdentityRecorder: ItemIdentityRecorder,
    private val itemSharingService: ItemSharingService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun rejectIfAlreadyRegistered(
        userId: UUID,
        link: ProductLink,
    ) {
        val shared = itemSharingService.resolveExistingItem(link) ?: return
        existingWishId(shared.getId(), userId)?.let {
            throw AlreadyRegisteredException.wish(WishErrorCode.ALREADY_EXISTS, it)
        }
    }

    // 삭제된 위시는 조회에서 빠지므로 지웠다 다시 담는 것은 막히지 않는다.
    private fun existingWishId(
        itemId: Long,
        userId: UUID,
    ): Long? = wishRepository.findByItemIdsAndUserId(listOf(itemId), userId).firstOrNull()?.getId()

    // 아는 링크 모양이면 그 정체성에 붙고, 처음 보는 모양이면 새로 세운다.
    @Transactional
    fun persist(
        userId: UUID,
        link: ProductLink,
    ): WishWithItem = attachToShared(userId, link) ?: createFresh(userId, link)

    // 이미 아는 링크 모양에 붙는 길(#825). 모르는 모양이면 null 을 돌려 새로 만드는 길로 넘긴다.
    private fun attachToShared(
        userId: UUID,
        link: ProductLink,
    ): WishWithItem? {
        val shared = itemSharingService.resolveExistingItem(link) ?: return null
        val attachment = itemSharingService.resolveAttachment(shared.getId(), link)
        // 중복 판정의 기준은 별칭으로 찾은 shared 가 아니라 실제로 붙은 attachment.item 이다 - 병합 재시도
        // 경합에선 둘이 다르다(shared=loser, attachment.item=winner). 행 락 뒤라 이 검사도 직렬화된다.
        // 409 면 트랜잭션 롤백으로 attach 가 만든 PENDING 도 함께 사라진다.
        existingWishId(attachment.item.getId(), userId)?.let {
            throw AlreadyRegisteredException.wish(WishErrorCode.ALREADY_EXISTS, it)
        }
        val wish = wishRepository.save(Wish(userId = userId, snapshotId = attachment.snapshot.getId()))
        return WishWithItem(
            wish = wish,
            item = attachment.item,
            snapshot = attachment.snapshot,
            reused = attachment.reused,
            refreshNeeded = attachment.refreshNeeded,
        )
    }

    // 처음 보는 링크를 새 정체성으로 세우는 길. snapshot 을 PENDING 으로 커밋하는 것이 곧 작업 큐 적재다.
    private fun createFresh(
        userId: UUID,
        link: ProductLink,
    ): WishWithItem {
        val saved = itemRepository.save(Item(link))
        itemIdentityRecorder.recordRegistrationAlias(saved)
        val snapshot = itemSnapshotRepository.save(ItemSnapshot.pending(saved.getId()))
        val wish = wishRepository.save(Wish(userId = userId, snapshotId = snapshot.getId()))
        return WishWithItem(wish = wish, item = saved, snapshot = snapshot)
    }

    // wish 의 활성 포인터가 가리키는 버전과 그 정체성. 영속화 경로상 반드시 존재하므로 부재는 코드 버그다.
    private fun activeVersionOf(wish: Wish): WishWithItem {
        val snapshot =
            itemSnapshotRepository.findById(wish.snapshotId)
                ?: error("wish ${wish.getId()} 의 snapshot ${wish.snapshotId} 가 없다")
        val item =
            itemRepository.findById(snapshot.itemId)
                ?: error("wish ${wish.getId()} 의 item ${snapshot.itemId} 가 없다")
        return WishWithItem(wish = wish, item = item, snapshot = snapshot)
    }

    // confirm 과 폴링 백스톱이 공유하는 진입점. pending_uploads 를 FOR UPDATE 로 잠가 삭제(claim)하므로
    // 둘이 같은 key 를 다퉈도 한쪽만 이긴다(멱등).
    @Transactional
    fun registerClaimedImages(
        imageKeys: List<String>,
        userId: UUID,
    ): List<WishWithItem> {
        // claim 보다 **먼저** user 행을 잠가 락 순서 규약 "user 다음 자식" 을 지킨다(#776). 지금은 역순 교차가
        // 성립하지 않지만, 탈퇴 cascade 가 이 유저의 pending_uploads 를 함께 정리하는 순간 교차 데드락이 된다.
        //
        // 예외가 아니라 boolean 으로 받는다 - 이 경로는 스케줄러 공용이라, tombstone 에 예외를 던지면
        // 롤백이 claim 을 되살려 무한 재시도가 된다. claim 은 소비하고 wish 생성만 건너뛴다.
        val active = userService.isActiveForUpdate(userId)
        val claimedKeys = pendingUploadClaimer.claim(imageKeys, PendingUploadContext.WISH, userId, tournamentId = null)
        if (claimedKeys.isEmpty()) return emptyList()
        if (!active) {
            log.info("탈퇴 유저의 지연 이미지 등록 건너뜀(claim 은 소비) userId={} keys={}", userId, claimedKeys.size)
            return emptyList()
        }
        return persistImagesInternal(userId, claimedKeys)
    }

    // 트랜잭션은 호출부가 연다 - self-invocation 으로 무력화되지 않게 private.
    private fun persistImagesInternal(
        userId: UUID,
        imageKeys: List<String>,
    ): List<WishWithItem> {
        val items = itemRepository.saveAll(imageKeys.map { Item(sourceImageKey = it) })
        // itemId 로 매핑한다 - saveAll 반환 순서는 공식 계약이 아니다.
        val snapshotsByItemId =
            itemSnapshotRepository.saveAll(items.map { ItemSnapshot.pending(it.getId()) }).associateBy { it.itemId }
        return items.map { item ->
            val snapshot = snapshotsByItemId[item.getId()] ?: error("item ${item.getId()} 의 snapshot 이 없다")
            val wish = wishRepository.save(Wish(userId = userId, snapshotId = snapshot.getId()))
            WishWithItem(wish = wish, item = item, snapshot = snapshot)
        }
    }

    // 기존 행을 고치지 않고 MANUAL 새 버전을 쌓아 활성 포인터를 스왑한다(#825 결정 4).
    // wish 행 락으로 refresh 와 직렬화한다 - 둘 다 포인터를 스왑하는 경로라 락 없이는 서로의 스왑을 덮는다.
    @Transactional
    fun manualEdit(
        userId: UUID,
        wishId: Long,
        name: String?,
        price: Int?,
        imageUrl: String?,
        currency: String?,
        memo: String?,
    ): WishWithItem {
        val wish = wishRepository.findByIdForUpdate(wishId) ?: throw WishException.notFound()
        wish.verifyOwnedBy(userId)
        val current = activeVersionOf(wish)
        val manual =
            itemSnapshotRepository.save(
                ItemSnapshot.manual(
                    base = current.snapshot,
                    name = name,
                    price = price,
                    imageUrl = imageUrl,
                    currency = currency,
                    editedBy = userId,
                ),
            )
        wish.swapSnapshot(manual.getId())
        memo?.let { wish.updateMemo(it) }
        return WishWithItem(wish = wish, item = current.item, snapshot = manual)
    }

    // 포인터를 안 바꿔도 행 락이 필요하다: UPDATE 가 전 컬럼을 쓰므로(dynamic update 아님), 락 없이 읽은 뒤
    // flush 하면 그 사이 스왑 경로(manualEdit·refresh)가 커밋한 snapshotId 를 옛 값으로 되덮는다(lost update).
    @Transactional
    fun updateMemo(
        userId: UUID,
        wishId: Long,
        memo: String,
    ): WishWithItem {
        val wish = wishRepository.findByIdForUpdate(wishId) ?: throw WishException.notFound()
        wish.verifyOwnedBy(userId)
        wish.updateMemo(memo)
        return activeVersionOf(wish)
    }

    // 원본 링크로 재추출해 최신화한다(수동 새로고침). 새 PENDING 을 작업 큐에 적재하고 포인터를 스왑하면
    // 등록과 같은 흐름을 탄다. 옛 snapshot 행은 남아 토너먼트 출전 격리를 지킨다.
    @Transactional
    fun refresh(
        userId: UUID,
        wishId: Long,
    ): WishWithItem {
        val wish = wishRepository.findByIdForUpdate(wishId) ?: throw WishException.notFound()
        wish.verifyOwnedBy(userId)
        val current = activeVersionOf(wish)
        val item = current.item
        item.link ?: throw WishException.notRefreshable()
        // 이미 진행 중이면 새 추출을 만들지 않는다(멱등).
        if (current.snapshot.isInProgress()) return current
        // 같은 item 의 다른 참조가 이미 파싱 중이면 새 작업 대신 그 진행에 합류한다(#826).
        itemSnapshotRepository.findLatestInProgressByItemId(item.getId())?.let { inProgress ->
            wish.swapSnapshot(inProgress.getId())
            return WishWithItem(wish = wish, item = item, snapshot = inProgress)
        }
        // FAILED 는 보정(recover)이 맡는다. 새로고침을 상태로 갈라 둬야 두 경로가 서로의 활성 포인터를
        // 침범하지 않는다 - 보정 진행 중엔 FAILED 라 새로고침이 여기서 막힌다.
        if (current.snapshot.isFailed()) throw WishException.failedNotRefreshable()
        val newSnapshot = itemSnapshotRepository.save(ItemSnapshot.pending(item.getId()))
        wish.swapSnapshot(newSnapshot.getId())
        return WishWithItem(wish = wish, item = item, snapshot = newSnapshot)
    }
}
