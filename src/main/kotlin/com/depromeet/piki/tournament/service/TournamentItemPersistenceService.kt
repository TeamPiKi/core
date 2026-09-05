package com.depromeet.piki.tournament.service

import com.depromeet.piki.item.domain.Item
import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.repository.ItemRepository
import com.depromeet.piki.item.repository.ItemSnapshotRepository
import com.depromeet.piki.item.service.ItemDisplayService
import com.depromeet.piki.item.service.ItemIdentityRecorder
import com.depromeet.piki.item.service.ItemSharingService
import com.depromeet.piki.common.exception.AlreadyRegisteredException
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.tournament.domain.Tournament
import com.depromeet.piki.tournament.domain.TournamentItem
import com.depromeet.piki.tournament.event.TournamentItemAdded
import com.depromeet.piki.tournament.repository.TournamentItemRepository
import com.depromeet.piki.tournament.repository.TournamentRepository
import com.depromeet.piki.tournament.repository.TournamentUserRepository
import com.depromeet.piki.tournament.service.dto.PersistedTournamentItem
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

// TournamentItemService 의 외부 호출(링크 추출·이미지 추출)이 트랜잭션 밖에 있도록
// 아이템 저장과 토너먼트 아이템 등록만 별도 빈으로 분리한다.
// 같은 빈에서 @Transactional 메서드를 직접 호출하면 Spring AOP proxy 를 거치지 않아 트랜잭션이 무력화된다.
//
// 2단계 쓰기 이중화: item 을 저장/전이하는 곳마다 같은 트랜잭션에서 대응 ItemSnapshot 도 평행하게 처리한다.
@Service
class TournamentItemPersistenceService(
    private val tournamentRepository: TournamentRepository,
    private val tournamentUserRepository: TournamentUserRepository,
    private val tournamentItemRepository: TournamentItemRepository,
    private val itemRepository: ItemRepository,
    private val itemSnapshotRepository: ItemSnapshotRepository,
    private val eventPublisher: ApplicationEventPublisher,
    private val itemIdentityRecorder: ItemIdentityRecorder,
    private val itemSharingService: ItemSharingService,
    private val itemDisplayService: ItemDisplayService,
) {
    // 이 토너먼트에 이미 출전 중인 item → 그 tournament_item id. 중복 판정이 "무엇과 겹치는지"까지 답해야 해서(#973)
    // 존재 여부가 아니라 매핑으로 만든다. snapshot 을 못 찾는 행은 자연히 빠지는데, 그런 행은 정원·중복 어느 쪽에도 셀 수 없다.
    private fun existingTournamentItemIdByItemId(tournamentId: Long): Map<Long, Long> {
        val tournamentItemIdBySnapshotId =
            tournamentItemRepository.findAllByTournamentId(tournamentId).associate { it.snapshotId to it.getId() }
        if (tournamentItemIdBySnapshotId.isEmpty()) return emptyMap()
        return itemSnapshotRepository
            .findByIds(tournamentItemIdBySnapshotId.keys.toList())
            .associate { it.itemId to tournamentItemIdBySnapshotId.getValue(it.getId()) }
    }

    // 처음 보는 링크 모양의 중복 — 기존 출전분 중 raw link 가 같은 행의 tournament_item id. 없으면 null.
    // 사전 확인과 최종 판정이 같은 규칙을 봐야 하므로(한쪽만 고치면 조용히 어긋난다) 두 곳이 이 함수를 공유한다.
    private fun duplicatedByRawLink(
        existingByItemId: Map<Long, Long>,
        link: ProductLink,
    ): Long? =
        itemRepository
            .findByIds(existingByItemId.keys.toList())
            .firstOrNull { it.link == link }
            ?.let { existingByItemId.getValue(it.getId()) }

    // 등록 전 사전 확인 — 이미 담긴 링크면 한도를 깎기 전에 409 로 끊는다(#973).
    // persistLinkItem 과 같은 두 갈래(raw link 모양 / 공유 정체성)를 본다.
    //
    // 락 밖 조회라 근사치다. 최종 판정은 persistLinkItem 이 정원 검사와 함께 트랜잭션 안에서 다시 하므로,
    // 여기서 놓친 중복도 거기서 걸린다. 그 창에서만 차감이 낭비된다.
    @Transactional(readOnly = true)
    fun rejectIfAlreadyAdded(
        tournamentId: Long,
        link: ProductLink,
    ) {
        val existingByItemId = existingTournamentItemIdByItemId(tournamentId)
        if (existingByItemId.isEmpty()) return
        val duplicated =
            duplicatedByRawLink(existingByItemId, link)
                ?: itemSharingService.resolveExistingItem(link)?.let { existingByItemId[it.getId()] }
        duplicated?.let {
            throw AlreadyRegisteredException.tournamentItem(TournamentErrorCode.DUPLICATE_TOURNAMENT_ITEM, it)
        }
    }

    @Transactional
    fun persistLinkItem(
        userId: UUID,
        tournamentId: Long,
        link: ProductLink,
    ): PersistedTournamentItem {
        rejectIfWontFit(userId, tournamentId, 1)
        // 공유 정체성(#825 활성화) — 이미 아는 링크 모양이면 기존 item 에 붙는다. 중복 검사도 정체성(itemId) 기준으로
        // 올라가, 같은 상품을 다른 링크 모양(단축 vs 정식)으로 담는 중복까지 잡는다. 처음 보는 모양은 raw link 비교(기존)로 남긴다.
        val shared = itemSharingService.resolveExistingItem(link)
        val existingByItemId = existingTournamentItemIdByItemId(tournamentId)
        // 처음 보는 링크 모양의 중복은 raw link 비교(기존 방식)로 잡는다. 정체성 기준 검사는 attach 뒤에서.
        duplicatedByRawLink(existingByItemId, link)?.let {
            throw AlreadyRegisteredException.tournamentItem(TournamentErrorCode.DUPLICATE_TOURNAMENT_ITEM, it)
        }
        shared?.let { sharedItem ->
            // attach 메타(reused·refreshNeeded)는 위시 등록 응답부터 노출한다(#853) — 토너먼트 응답 노출은 클라 요구가 생기면.
            val attachment = itemSharingService.resolveAttachment(sharedItem.getId(), link)
            // 정체성 중복 검사·반환 itemId 는 실제로 붙은 attachment.item 기준 — 병합 재시도 경합에선 별칭으로
            // 찾은 shared(loser)와 다르고(승자로 재해석), 이 기준이어야 반환 itemId 와 snapshot 소속이 일치한다.
            existingByItemId[attachment.item.getId()]?.let {
                throw AlreadyRegisteredException.tournamentItem(TournamentErrorCode.DUPLICATE_TOURNAMENT_ITEM, it)
            }
            val tournamentItem =
                tournamentItemRepository.save(
                    TournamentItem(
                        tournamentId = tournamentId,
                        userId = userId,
                        snapshotId = attachment.snapshot.getId(),
                    ),
                )
            eventPublisher.publishEvent(TournamentItemAdded(tournamentId = tournamentId, actorId = userId))
            return PersistedTournamentItem(
                itemId = attachment.item.getId(),
                snapshotId = attachment.snapshot.getId(),
                tournamentItemId = tournamentItem.getId(),
            )
        }
        val persisted = persistNew(Item(link), userId, tournamentId)
        eventPublisher.publishEvent(TournamentItemAdded(tournamentId = tournamentId, actorId = userId))
        return persisted
    }

    @Transactional
    fun rejectIfWontFit(
        userId: UUID,
        tournamentId: Long,
        incomingCount: Int,
    ) {
        validateAddable(lockTournament(tournamentId), userId, tournamentId)
        if (!hasRoomFor(tournamentId, incomingCount)) throw TournamentException.tooManyTournamentItems()
    }

    // 정원이 사이에 찼으면 null. 호출자는 그 키부터 담기를 멈춘다.
    // 참여자 검증은 rejectIfWontFit 이 같은 요청에서 방금 끝냈으므로 반복하지 않는다. 잠근 행의 상태만 다시 본다.
    // announce 는 첫 성공에만 true 로 넘긴다. AFTER_COMMIT 리스너가 타려면 발행이 트랜잭션 안이어야 해서 여기서 쏜다.
    @Transactional
    fun registerImage(
        imageKey: String,
        userId: UUID,
        tournamentId: Long,
        announce: Boolean,
    ): PersistedTournamentItem? {
        val tournament = lockTournament(tournamentId)
        if (!tournament.isPending()) throw TournamentException.notPendingTournament()
        if (!hasRoomFor(tournamentId, 1)) return null
        val persisted = persistNew(Item(sourceImageKey = imageKey), userId, tournamentId)
        if (announce) eventPublisher.publishEvent(TournamentItemAdded(tournamentId = tournamentId, actorId = userId))
        return persisted
    }

    // 새 정체성을 세우는 절차. 링크·이미지 두 경로가 이 한 벌을 쓴다.
    private fun persistNew(
        item: Item,
        userId: UUID,
        tournamentId: Long,
    ): PersistedTournamentItem {
        val saved = itemRepository.save(item)
        itemIdentityRecorder.recordRegistrationAlias(saved)
        val snapshot = itemSnapshotRepository.save(ItemSnapshot.pending(saved.getId()))
        val tournamentItem =
            tournamentItemRepository.save(
                TournamentItem(tournamentId = tournamentId, userId = userId, snapshotId = snapshot.getId()),
            )
        return PersistedTournamentItem(
            itemId = saved.getId(),
            snapshotId = snapshot.getId(),
            tournamentItemId = tournamentItem.getId(),
        )
    }

    // 수기 수정 영속화(#825 결정 4) — 기존 행을 고치지 않고 MANUAL 새 버전을 쌓아 출전 pin 을 옮긴다(repinSnapshot).
    // S3 업로드(외부 호출)는 호출부가 트랜잭션 바깥에서 끝내고, 여기선 권한·소유 검증 + 새 버전 적재 + pin 이동만
    // 짧은 트랜잭션으로 묶는다. 상태 제한이 없다 — 진행 중이던 파싱은 자기 행에서 계속돼 완료 시 이력으로 남는다.
    @Transactional
    fun manualEdit(
        userId: UUID,
        tournamentId: Long,
        tournamentItemId: Long,
        name: String?,
        price: Int?,
        imageUrl: String?,
        currency: String?,
    ) {
        val tournament =
            tournamentRepository.findTournamentById(tournamentId)
                ?: throw TournamentException.notFoundTournament()
        // 클론은 원본 아이템을 이어받을 뿐 소유 행이 없다 — 수정 시 원본을 건드리므로 막는다(#977, 추가 금지 032 와 같은 결).
        tournament.sourceTournamentId?.let { throw TournamentException.clonedTournamentCannotModifyItems() }
        if (!tournament.isPending()) throw TournamentException.notPendingTournament()
        tournamentUserRepository.findByTournamentIdAndUserId(tournamentId, userId)
            ?: throw TournamentException.forbiddenTournament()
        // 행 락 — 동시 수기 수정 두 건이 같은 pin 을 읽고 나중 커밋이 먼저 만든 MANUAL 버전을 덮는(유령 버전) 경합을 직렬화한다.
        val tournamentItem =
            tournamentItemRepository.findByIdForUpdate(tournamentItemId)
                ?: throw TournamentException.notFoundTournamentItem()
        if (tournamentItem.tournamentId != tournamentId) throw TournamentException.notFoundTournamentItem()
        if (tournamentItem.userId != userId) throw TournamentException.forbiddenTournament()
        val base = editBasisOf(tournamentItem)
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
        tournamentItem.repinSnapshot(manual.getId())
    }

    // 업로드 전 사전 검증(던지기 전용) — S3 orphan 방지가 유일한 존재 이유라 호출부가 이미지 있을 때만 부른다.
    // 권한·상태 검증과 병합 base 선택(editBasisOf)을 manualEdit 과 같은 코드로 타므로 dry-run 이 실제 저장과
    // 갈라질 수 없다. 락 밖 조회라 최종 판정은 manualEdit(락 안)이 다시 한다.
    @Transactional(readOnly = true)
    fun validateManualEdit(
        userId: UUID,
        tournamentId: Long,
        tournamentItemId: Long,
        name: String?,
        price: Int?,
        currency: String?,
    ) {
        val tournament =
            tournamentRepository.findTournamentById(tournamentId)
                ?: throw TournamentException.notFoundTournament()
        tournament.sourceTournamentId?.let { throw TournamentException.clonedTournamentCannotModifyItems() }
        if (!tournament.isPending()) throw TournamentException.notPendingTournament()
        val tournamentItem =
            tournamentItemRepository.findById(tournamentItemId)
                ?: throw TournamentException.notFoundTournamentItem()
        if (tournamentItem.tournamentId != tournamentId) throw TournamentException.notFoundTournamentItem()
        if (tournamentItem.userId != userId) throw TournamentException.forbiddenTournament()
        ItemSnapshot.manual(
            base = editBasisOf(tournamentItem),
            name = name,
            price = price,
            imageUrl = PRE_UPLOAD_VALIDATION_IMAGE_URL,
            currency = currency,
            editedBy = userId,
        )
    }

    // base 는 "이 카드가 보여주던 값" 이다. 수정은 PENDING 전용(호출부 가드)이고 PENDING 카드는 pin 이 아니라
    // 표시값(#858, 최신 기계 READY 우선 파생)을 그리므로, pin 을 base 로 쓰면 화면과 다른 값 위에 병합돼
    // 화면에 가격이 떠 있는데 "가격이 필요하다"로 튕길 수 있다. 시작 후 격리는 start 의 repin 이 진다.
    private fun editBasisOf(tournamentItem: TournamentItem): ItemSnapshot {
        val pointer =
            itemSnapshotRepository.findById(tournamentItem.snapshotId)
                ?: error("snapshot 없음 — snapshotId=${tournamentItem.snapshotId}")
        return itemDisplayService.resolveDisplay(pointer)
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
        if (!tournament.isPending()) throw TournamentException.notPendingTournament()
        tournament.sourceTournamentId?.let { throw TournamentException.clonedTournamentCannotAddItems() }
        tournamentUserRepository.findByTournamentIdAndUserId(tournamentId, userId)
            ?: throw TournamentException.forbiddenTournament()
    }

    private fun hasRoomFor(
        tournamentId: Long,
        incomingCount: Int,
    ): Boolean = tournamentItemRepository.countByTournamentId(tournamentId) + incomingCount <= TOURNAMENT_MAX_ITEM_COUNT
}

// 수기 수정 사전 검증(dry-run)에서 업로드 예정 이미지 자리를 메우는 자리표시 값 — 저장되지 않는다.
private const val PRE_UPLOAD_VALIDATION_IMAGE_URL = "https://validation.invalid/pre-upload.png"
