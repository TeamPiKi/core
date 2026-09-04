package com.depromeet.piki.wishlist.service

import com.depromeet.piki.common.exception.AlreadyRegisteredException
import com.depromeet.piki.image.domain.PendingUploadContext
import com.depromeet.piki.image.service.PendingUploadClaimer
import com.depromeet.piki.item.domain.Item
import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.repository.ItemRepository
import com.depromeet.piki.item.repository.ItemSnapshotRepository
import com.depromeet.piki.item.service.ItemDisplayService
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
    private val itemDisplayService: ItemDisplayService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 등록 전 사전 확인 — 이미 담은 상품이면 한도를 깎기 전에 409 로 끊는다(#973).
    //
    // 락 밖 조회라 근사치다: 병합 경합 창에서는 여기서 본 item 과 persist 가 실제로 붙는 item(승자)이 다를 수 있다.
    // 정확한 판정은 persist 가 행 락 안에서 다시 하므로, 여기서 놓친 중복도 거기서 걸린다. 그 창에서만 차감이 낭비된다.
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

    // confirm 과 폴링 백스톱이 공유하는 진입점.
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

    // 수기 수정 영속화(#825 결정 4) — 기존 행을 고치지 않고 MANUAL 새 버전을 쌓아 활성 포인터를 스왑한다.
    // S3 업로드(외부 호출)는 호출부가 트랜잭션 바깥에서 끝낸다. 상태 제한이 없다: 기계 버전은 불변이라 어떤 상태든
    // 덮어써질 위험 자체가 없고, 진행 중이던 파싱은 자기 행에서 계속돼 완료 시 이력으로 남는다.
    // wish 행 락으로 refresh 와 직렬화한다 — 둘 다 활성 포인터를 스왑하는 경로라, 락 없이는 서로의 스왑을 덮는다
    // (옛 FAILED-상태 분리 방어를 대체하는 장치). base 는 락 안에서 읽은 현재 활성 버전이다.
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
        val base = editBasisOf(wish)
        val item = itemRepository.findById(base.itemId) ?: error("item ${base.itemId} 가 없다")
        val manual =
            itemSnapshotRepository.save(
                ItemSnapshot.manual(
                    base = base,
                    name = name,
                    price = price,
                    imageUrl = imageUrl,
                    currency = currency,
                    editedBy = userId,
                ),
            )
        wish.swapSnapshot(manual.getId())
        memo?.let { wish.updateMemo(it) }
        return WishWithItem(wish = wish, item = item, snapshot = manual)
    }

    // 업로드 전 사전 검증(던지기 전용) — S3 orphan 방지가 유일한 존재 이유라 호출부가 이미지 있을 때만 부른다.
    // 병합 base 를 manualEdit 과 같은 코드(editBasisOf)로 고르므로 dry-run 과 실제 저장이 갈라질 수 없다.
    // 락 밖 조회라 최종 판정은 manualEdit(락 안)이 다시 한다.
    @Transactional(readOnly = true)
    fun validateManualEdit(
        userId: UUID,
        wishId: Long,
        name: String?,
        price: Int?,
        currency: String?,
    ) {
        val wish = wishRepository.findById(wishId) ?: throw WishException.notFound()
        wish.verifyOwnedBy(userId)
        ItemSnapshot.manual(
            base = editBasisOf(wish),
            name = name,
            price = price,
            imageUrl = PRE_UPLOAD_VALIDATION_IMAGE_URL,
            currency = currency,
            editedBy = userId,
        )
    }

    // base 는 포인터가 아니라 카드가 보여준 표시값(#858) — 포인터가 미완성이어도 카드엔 최신 기계 READY 가
    // 떠 있고, 사용자는 그 값을 보며 일부 필드만 고친다. 포인터를 base 로 쓰면 안 고친 필드가 빈 값에서
    // 병합돼, 화면에 가격이 떠 있는데 "가격이 필요하다"로 튕긴다(담기 게이트와 같은 어긋남, #1006).
    private fun editBasisOf(wish: Wish): ItemSnapshot {
        val pointer =
            itemSnapshotRepository.findById(wish.snapshotId)
                ?: error("wish ${wish.getId()} 의 snapshot ${wish.snapshotId} 가 없다")
        return itemDisplayService.resolveDisplay(pointer)
    }

    // memo 만 온 수정 — 버전(snapshot)을 쌓지 않고 wish 행만 갱신한다. 포인터를 안 바꿔도 행 락은 필요하다:
    // UPDATE 가 전 컬럼을 쓰므로(dynamic update 아님), 락 없이 읽은 뒤 flush 하면 그 사이 스왑 경로(manualEdit·refresh)가
    // 커밋한 snapshotId 를 읽던 옛 값으로 되덮는다(lost update). 같은 행 락으로 스왑 경로와 직렬화한다.
    @Transactional
    fun updateMemo(
        userId: UUID,
        wishId: Long,
        memo: String,
    ): WishWithItem {
        val wish = wishRepository.findByIdForUpdate(wishId) ?: throw WishException.notFound()
        wish.verifyOwnedBy(userId)
        wish.updateMemo(memo)
        val snapshot =
            itemSnapshotRepository.findById(wish.snapshotId)
                ?: error("wish ${wish.getId()} 의 snapshot ${wish.snapshotId} 가 없다")
        val item = itemRepository.findById(snapshot.itemId) ?: error("item ${snapshot.itemId} 가 없다")
        return WishWithItem(wish = wish, item = item, snapshot = snapshot)
    }

    // 위시 item 을 원본 링크로 재추출해 최신화한다(수동 새로고침). 새 PENDING snapshot 을 작업 큐에 적재하고
    // wish 활성 포인터를 즉시 그 버전으로 스왑한다 — 디스패처가 PENDING 을 집어 추출해 READY/FAILED 로 전이한다(등록과 동일 흐름).
    // 옛 snapshot 행은 유지돼 토너먼트 출전 격리를 지킨다. 외부 호출(추출)은 디스패처가 트랜잭션 밖에서 하므로 여기선 적재만 한다.
    // 동시 새로고침은 wish 행 락(findByIdForUpdate)으로 직렬화하고, 이미 진행 중이면 멱등(no-op)으로 새 추출을 만들지 않는다.
    @Transactional
    fun refresh(
        userId: UUID,
        wishId: Long,
    ): WishWithItem {
        val wish = wishRepository.findByIdForUpdate(wishId) ?: throw WishException.notFound()
        wish.verifyOwnedBy(userId)
        // item 정체성은 snapshot.itemId 단일 출처. snapshot·item 은 영속화 경로상 반드시 존재한다(없으면 코드 버그).
        val activeSnapshot =
            itemSnapshotRepository.findById(wish.snapshotId)
                ?: error("wish ${wish.getId()} 의 snapshot ${wish.snapshotId} 가 없다")
        val item =
            itemRepository.findById(activeSnapshot.itemId)
                ?: error("wish ${wish.getId()} 의 item ${activeSnapshot.itemId} 가 없다")
        // link 없는 item(이미지 등록분)은 재추출 입력이 없어 새로고침 대상이 아니다(400).
        item.link ?: throw WishException.notRefreshable()
        // 이미 진행 중(PENDING·PROCESSING)이면 새 추출을 만들지 않고 현재 진행 상태를 그대로 반환(멱등).
        if (activeSnapshot.isInProgress()) return WishWithItem(wish = wish, item = item, snapshot = activeSnapshot)
        // 공유(#825) — 같은 item 의 다른 참조(다른 위시·출전)가 이미 파싱을 돌리고 있으면 새 작업 대신 그 진행에
        // 합류한다(#826). 활성 포인터를 그 버전으로 스왑해 완료 시 함께 갱신된다.
        itemSnapshotRepository.findLatestInProgressByItemId(item.getId())?.let { inProgress ->
            wish.swapSnapshot(inProgress.getId())
            return WishWithItem(wish = wish, item = item, snapshot = inProgress)
        }
        // 판정은 표시값(#858) — 포인터가 FAILED 여도 같은 item 을 남이 담아 추출이 성공했으면 카드엔 그 값이
        // 떠 있고, item 이 추출 가능하다는 증거이므로 새로고침을 막을 이유가 없다. 표시값까지 FAILED(기계 READY
        // 부재)면 재추출도 결정론적으로 재실패할 것이라 보정(recover, 수기 수정)으로 유도한다(409).
        // 포인터부터 보는 단락 평가 — FAILED 가 아니면 표시값도 FAILED 일 수 없어(파생 후보가 READY 뿐) 쿼리가 무의미하다.
        if (activeSnapshot.isFailed() && itemDisplayService.resolveDisplay(activeSnapshot).isFailed()) {
            throw WishException.failedNotRefreshable()
        }
        // 새 PENDING 버전을 작업 큐에 적재하고 활성 포인터를 즉시 스왑한다.
        val newSnapshot = itemSnapshotRepository.save(ItemSnapshot.pending(item.getId()))
        wish.swapSnapshot(newSnapshot.getId())
        return WishWithItem(wish = wish, item = item, snapshot = newSnapshot)
    }
}

// 수기 수정 사전 검증(dry-run)에서 업로드 예정 이미지 자리를 메우는 자리표시 값 — 저장되지 않는다.
private const val PRE_UPLOAD_VALIDATION_IMAGE_URL = "https://validation.invalid/pre-upload.png"
