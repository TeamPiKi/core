package com.depromeet.piki.tournament.service

import com.depromeet.piki.image.domain.PendingUploadContext
import com.depromeet.piki.image.service.PendingUploadClaimer
import com.depromeet.piki.item.domain.Item
import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.repository.ItemRepository
import com.depromeet.piki.item.repository.ItemSnapshotRepository
import com.depromeet.piki.item.service.ItemIdentityRecorder
import com.depromeet.piki.item.service.ItemSharingService
import com.depromeet.piki.common.exception.AlreadyRegisteredException
import com.depromeet.piki.product.domain.ProductLink
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
    private val pendingUploadClaimer: PendingUploadClaimer,
    private val eventPublisher: ApplicationEventPublisher,
    private val itemIdentityRecorder: ItemIdentityRecorder,
    private val itemSharingService: ItemSharingService,
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
        validateAndCheckCapacity(userId, tournamentId, 1)
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
            val tournamentItem = tournamentItemRepository.saveAll(
                listOf(TournamentItem(tournamentId = tournamentId, userId = userId, snapshotId = attachment.snapshot.getId())),
            ).first()
            eventPublisher.publishEvent(TournamentItemAdded(tournamentId = tournamentId, actorId = userId))
            return PersistedTournamentItem(
                itemId = attachment.item.getId(),
                snapshotId = attachment.snapshot.getId(),
                tournamentItemId = tournamentItem.getId(),
            )
        }
        val item = itemRepository.save(Item(link))
        // 처음 보는 링크 모양 — 원본 입력을 별칭(item_links)으로 기록한다(위시 등록과 같은 결).
        itemIdentityRecorder.recordRegistrationAlias(item)
        // 저장한 snapshot 의 id 를 tournament_item 에 고정한다. 출전 시점 버전이 박혀 위시 갱신과 격리된다.
        // URL 경로는 PENDING 으로 작업 큐에 적재하고 디스패처가 집어 파싱한다 — 워커를 여기서 트리거하지 않는다.
        val snapshot = itemSnapshotRepository.save(ItemSnapshot.pending(item.getId()))
        val tournamentItem = tournamentItemRepository.saveAll(
            listOf(
                TournamentItem(
                    tournamentId = tournamentId,
                    userId = userId,
                    snapshotId = snapshot.getId(),
                ),
            ),
        ).first()
        // 링크 1개 추가. 커밋된 뒤에만 구독자에게 전달되도록 트랜잭션 안에서 발행한다 (롤백 시 미발행).
        eventPublisher.publishEvent(TournamentItemAdded(tournamentId = tournamentId, actorId = userId))
        return PersistedTournamentItem(itemId = item.getId(), snapshotId = snapshot.getId(), tournamentItemId = tournamentItem.getId())
    }

    // 이미지 등록 — confirm 또는 폴링 백스톱이 "업로드 확인된" key 들을 등록한다. pending_uploads 를 FOR UPDATE 로
    // 잠가 삭제(claim)하고, claim 에 성공한 TOURNAMENT 매핑(해당 user·tournament)만 적재한다 — confirm·폴링이 같은 key 를
    // 다퉈도 삭제는 한쪽만 성공하므로 중복 등록되지 않는다(멱등). 다른 맥락 매핑은 걸러낸다.
    @Transactional
    fun registerClaimedImages(
        imageKeys: List<String>,
        userId: UUID,
        tournamentId: Long,
    ): List<PersistedTournamentItem> {
        val claimedKeys = pendingUploadClaimer.claim(imageKeys, PendingUploadContext.TOURNAMENT, userId, tournamentId)
        if (claimedKeys.isEmpty()) return emptyList()
        return persistImageItemsInternal(userId, tournamentId, claimedKeys)
    }

    // 이미지 key 들을 정원 검증(FOR UPDATE) 후 item → PENDING snapshot → tournament_item 으로 배치 적재하는 공통 코어.
    // 트랜잭션은 호출부가 연다 — self-invocation 으로 트랜잭션이 무력화되지 않게 private.
    private fun persistImageItemsInternal(
        userId: UUID,
        tournamentId: Long,
        imageKeys: List<String>,
    ): List<PersistedTournamentItem> {
        validateAndCheckCapacity(userId, tournamentId, imageKeys.size)
        val items = itemRepository.saveAll(imageKeys.map { Item(sourceImageKey = it) })
        // snapshot·tournament_item 을 itemId·snapshotId 로 되짚어 saveAll 반환 순서에 의존하지 않는다(순서 보존은 공식 계약이 아니다 — WishPersistenceService 와 동일).
        // 입력(imageKey)이 durable 하므로 link 경로처럼 PENDING 으로 적재한다 — 디스패처가 집어 파싱한다.
        val snapshotByItemId = itemSnapshotRepository.saveAll(items.map { ItemSnapshot.pending(it.getId()) }).associateBy { it.itemId }
        val tournamentItemBySnapshotId =
            tournamentItemRepository
                .saveAll(
                    items.map { item ->
                        val snapshot = snapshotByItemId[item.getId()] ?: error("item ${item.getId()} 의 snapshot 이 없다")
                        TournamentItem(tournamentId = tournamentId, userId = userId, snapshotId = snapshot.getId())
                    },
                ).associateBy { it.snapshotId }
        // 이미지를 여러 장 한 번에 올려도 "아이템이 추가됐다"는 사실은 1건이라 이벤트도 1회만 발행한다.
        eventPublisher.publishEvent(TournamentItemAdded(tournamentId = tournamentId, actorId = userId))
        return items.map { item ->
            val snapshot = snapshotByItemId[item.getId()] ?: error("item ${item.getId()} 의 snapshot 이 없다")
            val tournamentItem = tournamentItemBySnapshotId[snapshot.getId()] ?: error("snapshot ${snapshot.getId()} 의 tournament_item 이 없다")
            PersistedTournamentItem(itemId = item.getId(), snapshotId = tournamentItem.snapshotId, tournamentItemId = tournamentItem.getId())
        }
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
        // 토너먼트는 출전 시점 pin snapshot 을 base 로 쓴다. 최신(findLatestByItemId)이 아니라 tournamentItem.snapshotId
        // 기준이어야, 같은 item 에 버전이 여러 개 생겨도 이 카드가 보던 버전 위에 수정이 얹혀 격리가 유지된다.
        val snapshotId = tournamentItem.snapshotId
        val base =
            itemSnapshotRepository.findById(snapshotId)
                ?: error("snapshot 없음 — tournamentItemId=$tournamentItemId, snapshotId=$snapshotId")
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

    // 이미지 업로드(외부 호출) 전에 권한·상태·복제를 미리 검증해 거부될 요청이 S3 에 orphan raw 를 남기지 않게 한다.
    // 정원은 동시성 때문에 persist 의 FOR UPDATE(validateAndCheckCapacity)가 최종 판정하므로 여기선 제외한다(다층 방어).
    @Transactional(readOnly = true)
    fun verifyCanAddItems(
        userId: UUID,
        tournamentId: Long,
    ) {
        val tournament =
            tournamentRepository.findTournamentById(tournamentId)
                ?: throw TournamentException.notFoundTournament()
        if (!tournament.isPending()) throw TournamentException.notPendingTournament()
        tournament.sourceTournamentId?.let { throw TournamentException.clonedTournamentCannotAddItems() }
        tournamentUserRepository.findByTournamentIdAndUserId(tournamentId, userId)
            ?: throw TournamentException.forbiddenTournament()
    }

    private fun validateAndCheckCapacity(
        userId: UUID,
        tournamentId: Long,
        incomingCount: Int,
    ) {
        val tournament =
            tournamentRepository.findTournamentByIdForUpdate(tournamentId)
                ?: throw TournamentException.notFoundTournament()
        if (!tournament.isPending()) throw TournamentException.notPendingTournament()
        tournament.sourceTournamentId?.let { throw TournamentException.clonedTournamentCannotAddItems() }
        tournamentUserRepository.findByTournamentIdAndUserId(tournamentId, userId)
            ?: throw TournamentException.forbiddenTournament()
        val existingCount = tournamentItemRepository.countByTournamentId(tournamentId)
        if (existingCount + incomingCount >
            TOURNAMENT_MAX_ITEM_COUNT
        ) {
            throw TournamentException.tooManyTournamentItems()
        }
    }
}
