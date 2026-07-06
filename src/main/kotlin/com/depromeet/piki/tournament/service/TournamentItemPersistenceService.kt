package com.depromeet.piki.tournament.service

import com.depromeet.piki.image.domain.PendingUploadContext
import com.depromeet.piki.image.service.PendingUploadClaimer
import com.depromeet.piki.item.domain.Item
import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.repository.ItemRepository
import com.depromeet.piki.item.repository.ItemSnapshotRepository
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
) {
    @Transactional
    fun persistLinkItem(
        userId: UUID,
        tournamentId: Long,
        link: ProductLink,
    ): PersistedTournamentItem {
        validateAndCheckCapacity(userId, tournamentId, 1)
        val existingItems = tournamentItemRepository.findAllByTournamentId(tournamentId)
        if (existingItems.isNotEmpty()) {
            val existingSnapshots = itemSnapshotRepository.findByIds(existingItems.map { it.snapshotId })
            val existingLinks = itemRepository.findByIds(existingSnapshots.map { it.itemId }).mapNotNull { it.link }
            if (link in existingLinks) throw TournamentException.duplicateTournamentItem()
        }
        val item = itemRepository.save(Item(link))
        // 저장한 snapshot 의 id 를 tournament_item 에 고정한다. 출전 시점 버전이 박혀 위시 갱신과 격리된다.
        // URL 경로는 PENDING 으로 적재(outbox)하고 디스패처가 집어 파싱한다 — 워커를 여기서 트리거하지 않는다.
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

    // v1(multipart) 이미지 아이템 추가 — 서버가 바이트를 받아 올린 뒤 pending 매핑 없이 바로 적재한다.
    @Transactional
    fun persistPendingImageItems(
        userId: UUID,
        tournamentId: Long,
        imageKeys: List<String>,
    ): List<PersistedTournamentItem> = persistImageItemsInternal(userId, tournamentId, imageKeys)

    // v2 이미지 등록 — confirm 또는 폴링 백스톱이 "업로드 확인된" key 들을 등록한다. pending_uploads 를 FOR UPDATE 로
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

    // FAILED 버전의 수동 보정 영속화 — S3 업로드(외부 호출)는 호출부가 트랜잭션 바깥에서 끝내고,
    // 여기선 권한·소유 검증 + snapshot.recover(값 변경 + FAILED→READY 전이)만 짧은 트랜잭션으로 묶는다(dirty checking).
    // recover 가 READY/PROCESSING 을 409 로 막는다(도메인 자기방어). item 은 정체성이라 건드리지 않는다.
    @Transactional
    fun recoverItem(
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
        if (!tournament.isPending()) throw TournamentException.notPendingTournament()
        tournamentUserRepository.findByTournamentIdAndUserId(tournamentId, userId)
            ?: throw TournamentException.forbiddenTournament()
        val tournamentItem =
            tournamentItemRepository.findById(tournamentItemId)
                ?: throw TournamentException.notFoundTournamentItem()
        if (tournamentItem.tournamentId != tournamentId) throw TournamentException.notFoundTournamentItem()
        if (tournamentItem.userId != userId) throw TournamentException.forbiddenTournament()
        // 토너먼트는 출전 시점 고정 snapshot 을 본다. 최신(findLatestByItemId)이 아니라 tournamentItem.snapshotId 를
        // 갱신해야, 5단계 갱신으로 같은 item 에 snapshot 이 여러 개 생겨도 토너먼트가 고정한 버전만 보정돼 격리가 유지된다.
        val snapshotId = tournamentItem.snapshotId
        val snapshot =
            itemSnapshotRepository.findById(snapshotId)
                ?: error("snapshot 없음 — tournamentItemId=$tournamentItemId, snapshotId=$snapshotId")
        snapshot.recover(name = name, currentPrice = price, imageUrl = imageUrl, currency = currency)
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
