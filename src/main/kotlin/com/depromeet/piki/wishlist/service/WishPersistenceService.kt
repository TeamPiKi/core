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

// WishlistService 의 registerFromUrl 가 외부 LLM 호출을 트랜잭션 바깥에 두도록
// 영속화만 별도 빈으로 분리. 같은 빈에서 호출하면 Spring AOP proxy 를
// 거치지 않아 @Transactional 가 무력화되기 때문이다.
//
// item 은 정체성(link)만 들고 추출값·상태는 ItemSnapshot 이 보유한다. URL 등록 경로는 link 만 가진 item 과
// PENDING snapshot(작업 큐 적재)을 같은 트랜잭션에서 함께 저장하고, wish 가 그 snapshot 을 활성 포인터로 가리킨다.
// 파싱은 디스패처(@Scheduled)가 PENDING 을 집어 시작하므로, 여기선 워커를 트리거하지 않는다.
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

    // 유저 안에서 itemId → 그 위시의 id. itemId 는 등록당 1건이라 유저 내에서 사실상 1:1 이고,
    // 삭제된 위시는 조회에서 빠지므로 지웠다 다시 담는 것은 막히지 않는다.
    private fun existingWishId(
        itemId: Long,
        userId: UUID,
    ): Long? = wishRepository.findByItemIdsAndUserId(listOf(itemId), userId).firstOrNull()?.getId()

    // item(정체성) → snapshot(PENDING 버전) → wish 순서로 같은 트랜잭션에서 저장한다.
    // item 생성은 호출부가 트랜잭션 바깥에서 끝내고, 여기선 영속화만 한다.
    // snapshot 을 PENDING 으로 커밋하는 것이 곧 작업 큐 적재다 — 디스패처가 이 행을 집어 PROCESSING 으로 claim 한다.
    @Transactional
    fun persist(
        userId: UUID,
        item: Item,
    ): WishWithItem {
        // 활성 유저 확인·쓰기 경합 차단(#776) — user 행을 잠가 tombstone 이면 409. requireMember(비잠금)의
        // 확인과 이 트랜잭션의 wish INSERT 사이에 탈퇴 cascade 가 끼어들어 죽은 유저 wish 가 남는 것을 막는다.
        // absent(users 행 없음)는 여기서 막지 않는다 — 정상 경로는 앞단(WishlistService.requireMember)이 이미 거르고,
        // 이 방어는 "확인 후 탈퇴가 끼어든" tombstone race 전용이다(FCM 과 같은 결). 행이 있으면 잠가 직렬화한다.
        userService.rejectIfWithdrawnForUpdate(userId)
        // 공유 정체성(#825 활성화) — 이미 아는 링크 모양이면 새 item 을 만들지 않고 기존 item 에 붙는다.
        // 락 순서 규약(user → 자식)에 따라 user 락 뒤에 item 락(resolveAttachment)이 온다.
        item.link?.let { link ->
            itemSharingService.resolveExistingItem(link)?.let { shared ->
                val attachment = itemSharingService.resolveAttachment(shared.getId(), link)
                // 앞문 중복(결정 3c): 같은 사용자가 이미 담은 상품이면 새 카드 대신 409. 판정·응답의 정체성 기준은
                // 별칭으로 찾은 shared 가 아니라 실제로 붙은 attachment.item 이다 — 병합 재시도 경합에선 둘이
                // 다르고(shared=loser, attachment.item=winner), 행 락 뒤라 검사도 직렬화된다. 409 면 트랜잭션
                // 롤백으로 attach 가 만든 PENDING 도 함께 사라진다.
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
        }
        val saved = itemRepository.save(item)
        // 처음 보는 링크 모양 — 원본 입력을 별칭(item_links)으로 기록한다. 같은 트랜잭션이라 등록과 원자적이고,
        // INSERT IGNORE 라 동시 등록 경합이 등록을 죽이지 않는다.
        itemIdentityRecorder.recordRegistrationAlias(saved)
        // 저장한 snapshot 의 id 를 wish 의 활성 포인터(snapshotId)로 박는다. 5단계 갱신에서 새 버전으로 스왑된다.
        val snapshot = itemSnapshotRepository.save(ItemSnapshot.pending(saved.getId()))
        val wish = wishRepository.save(Wish(userId = userId, snapshotId = snapshot.getId()))
        return WishWithItem(wish = wish, item = saved, snapshot = snapshot)
    }

    // 이미지 등록 — confirm 또는 폴링 백스톱이 "업로드 확인된" key 들을 등록한다. pending_uploads 를 FOR UPDATE 로
    // 잠가 삭제(claim)하고, claim 에 성공한(=이 트랜잭션이 가져간) WISH 매핑만 적재한다 — confirm·폴링이 같은 key 를
    // 다퉈도 삭제는 한쪽만 성공하므로 중복 등록되지 않는다(멱등). 다른 user·토너먼트 맥락 매핑은 걸러낸다.
    @Transactional
    fun registerClaimedImages(
        imageKeys: List<String>,
        userId: UUID,
    ): List<WishWithItem> {
        // 활성 유저 확인·쓰기 경합 차단(#776). claim(pending_uploads 락)보다 **먼저** user 행을 잠가, 이 프로젝트의
        // 락 순서 규약 "user → 자식" 을 지킨다(WithdrawalPersistenceService.withdraw 와 동일). 지금은 user 를 먼저
        // 잠근 뒤 pending_uploads 를 건드리는 경로가 없어 역순 교차가 성립하지 않지만, 탈퇴 cascade 가 이 유저의
        // pending_uploads 를 함께 정리하는 순간 users→pending_uploads 가 생겨 이 경로와 교차 데드락이 된다.
        // 부수 효과로 확인~claim 구간이 user 락 안에 들어와, 그 사이 탈퇴가 끼어들 창 자체가 사라진다.
        //
        // 이 경로는 스케줄러(지연 처리)·confirm 공용이라, tombstone 이라고 예외를 던지면 트랜잭션 롤백으로
        // claim(pending_uploads 삭제)이 되살아나 스케줄러가 무한 재시도한다. 그래서 예외 대신 boolean 으로 받아
        // claim 은 소비하되 wish 생성만 건너뛴다 — 탈퇴 후 남은 pending upload 가 죽은 유저 wish 로 되살아나지 않는다.
        val active = userService.isActiveForUpdate(userId)
        val claimedKeys = pendingUploadClaimer.claim(imageKeys, PendingUploadContext.WISH, userId, tournamentId = null)
        if (claimedKeys.isEmpty()) return emptyList()
        if (!active) {
            log.info("탈퇴 유저의 지연 이미지 등록 건너뜀(claim 은 소비) userId={} keys={}", userId, claimedKeys.size)
            return emptyList()
        }
        return persistImagesInternal(userId, claimedKeys)
    }

    // 이미지 key 들을 item(정체성) → PENDING snapshot(작업 큐 적재) → wish 순서로 배치 적재하는 공통 코어.
    // 트랜잭션은 호출부(registerClaimedImages)가 연다 — self-invocation 으로 트랜잭션이 무력화되지 않게 private.
    private fun persistImagesInternal(
        userId: UUID,
        imageKeys: List<String>,
    ): List<WishWithItem> {
        val items = itemRepository.saveAll(imageKeys.map { Item(sourceImageKey = it) })
        // snapshot 을 itemId 로 매핑해 saveAll 반환 순서에 의존하지 않는다(순서 보존은 공식 계약이 아니다).
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
