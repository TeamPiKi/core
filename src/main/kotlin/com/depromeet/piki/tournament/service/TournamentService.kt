package com.depromeet.piki.tournament.service

import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.domain.ItemStatus
import com.depromeet.piki.item.repository.ItemRepository
import com.depromeet.piki.item.repository.ItemSnapshotRepository
import com.depromeet.piki.item.service.ItemDisplayService
import com.depromeet.piki.tournament.domain.RoundBracket
import com.depromeet.piki.tournament.domain.Tournament
import com.depromeet.piki.tournament.domain.TournamentHistory
import com.depromeet.piki.tournament.domain.TournamentItem
import com.depromeet.piki.tournament.domain.TournamentPlayType
import com.depromeet.piki.tournament.domain.TournamentStatus
import com.depromeet.piki.tournament.domain.TournamentUser
import com.depromeet.piki.tournament.event.TournamentCompleted
import com.depromeet.piki.tournament.event.TournamentItemAdded
import com.depromeet.piki.tournament.event.TournamentItemDeleted
import com.depromeet.piki.tournament.event.TournamentJoined
import com.depromeet.piki.tournament.event.TournamentPlayedFromLink
import com.depromeet.piki.tournament.event.TournamentResultReady
import com.depromeet.piki.tournament.event.TournamentStarted
import com.depromeet.piki.tournament.repository.TournamentItemRepository
import com.depromeet.piki.tournament.repository.TournamentRepository
import com.depromeet.piki.tournament.repository.TournamentUserRepository
import com.depromeet.piki.tournament.service.dto.AddTournamentItemsFromWish
import com.depromeet.piki.tournament.service.dto.CreateTournament
import com.depromeet.piki.tournament.service.dto.CreateTournamentResult
import com.depromeet.piki.tournament.service.dto.GroupResult
import com.depromeet.piki.tournament.service.dto.GroupResultItem
import com.depromeet.piki.tournament.service.dto.ParticipantSummary
import com.depromeet.piki.tournament.service.dto.PlayLinkInfo
import com.depromeet.piki.tournament.service.dto.RankedItem
import com.depromeet.piki.tournament.service.dto.RecordMatch
import com.depromeet.piki.tournament.service.dto.RecordMatchResult
import com.depromeet.piki.tournament.service.dto.TournamentDetail
import com.depromeet.piki.tournament.service.dto.TournamentInvitePreview
import com.depromeet.piki.tournament.service.dto.TournamentItemDetail
import com.depromeet.piki.tournament.service.dto.StartResult
import com.depromeet.piki.tournament.service.dto.TournamentStartResult
import com.depromeet.piki.tournament.service.dto.TournamentSummary
import com.depromeet.piki.user.domain.UserException
import com.depromeet.piki.user.repository.UserRepository
import com.depromeet.piki.wishlist.repository.WishRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

@Service
class TournamentService(
    private val tournamentUserRepository: TournamentUserRepository,
    private val tournamentRepository: TournamentRepository,
    private val tournamentItemRepository: TournamentItemRepository,
    private val userRepository: UserRepository,
    private val itemRepository: ItemRepository,
    private val itemSnapshotRepository: ItemSnapshotRepository,
    private val itemDisplayService: ItemDisplayService,
    private val wishRepository: WishRepository,
    private val eventPublisher: ApplicationEventPublisher,
) {
    // 탈퇴(tombstone) 계정의 토너먼트 생성을 막는다. anonymize 는 닉네임·프로필만 비우고 행은 남기므로,
    // 탈퇴 시 토큰 무효화가 부분 실패한 창에서 죽은 계정이 토너먼트를 만들 수 있다
    // (위시가 findActiveById 로 막는 것과 같은 사유, #691).
    //
    // users 행 존재는 강제하지 않는다(findActiveById 가 아니라 findById + Elvis) — 인증만 되면 행 없이도 호출되던
    // 기존 계약을 이 가드가 404 로 바꾸지 않기 위해서다(FCM 토큰 등록의 rejectIfWithdrawnForUpdate 와 같은 결).
    //
    // 회원 전용 게이트(#339)가 여기 함께 있었으나 클라이언트 대응 전까지 임시로 걷어냈다(#965). 그래서 게스트도
    // 다시 토너먼트를 만들 수 있고, 그 토너먼트의 아이템 등록은 오너인 게스트 몫에서 깎인다. 게스트 계정은
    // 무한 발급되므로(POST /auth/guest) 계정별 한도(ItemQuotaGuard)는 이 창 동안 게스트에 대해 실효가 없고,
    // 남는 방어선은 전역 가용량 상한 하나다. 재적용은 아래 한 줄을 되살리면 된다(code·예외는 남겨 뒀다):
    //   if (user.identityType != IdentityType.MEMBER) throw TournamentException.guestCannotCreateTournament()
    private fun rejectIfDeleted(userId: UUID) {
        val user = userRepository.findById(userId) ?: return
        user.deletedAt?.let { throw UserException.deletedUser() }
    }

    @Transactional
    fun create(
        userId: UUID,
        command: CreateTournament,
    ): CreateTournamentResult {
        rejectIfDeleted(userId)
        val inviteCode = generateUniqueInviteCode()
        val inviteExpiresAt = LocalDateTime
            .now()
            .plusMinutes(command.inviteDurationMinutes)
        val tournament =
            tournamentRepository.saveTournament(
                Tournament(
                    ownerTournamentUserId = 0L,
                    name = command.name,
                    inviteCode = inviteCode,
                    inviteExpiresAt = inviteExpiresAt,
                ),
            )
        val tournamentUser =
            tournamentUserRepository.save(
                TournamentUser(tournamentId = tournament.getId(), userId = userId),
            )
        tournament.assignOwner(tournamentUser.getId())
        return CreateTournamentResult(
            tournamentId = tournament.getId(),
            inviteCode = inviteCode,
            inviteExpiresAt = inviteExpiresAt,
        )
    }

    @Transactional
    fun join(
        userId: UUID,
        tournamentId: Long,
        inviteCode: String?,
    ) {
        val tournament =
            tournamentRepository.findTournamentByIdForUpdate(tournamentId)
                ?: throw TournamentException.notFoundTournament()
        tournament.checkJoinable(inviteCode)
        tournamentUserRepository
            .findByTournamentIdAndUserId(tournamentId, userId)
            ?.let { throw TournamentException.alreadyParticipant() }
        if (tournamentUserRepository.countByTournamentId(tournamentId) >= TOURNAMENT_MAX_PARTICIPANT_COUNT) {
            throw TournamentException.participantLimitExceeded()
        }
        tournamentUserRepository.save(TournamentUser(tournamentId = tournamentId, userId = userId))
        // 참여가 커밋된 뒤에만 구독자에게 전달되도록 트랜잭션 안에서 발행한다 (롤백 시 미발행).
        eventPublisher.publishEvent(TournamentJoined(tournamentId = tournamentId, actorId = userId))
    }

    // 아이템 등록 한도(#339)를 차감하지 않는다 — 위시에 이미 있는 item 을 참조만 하므로 새 파싱·LLM 호출이 없다.
    // 그 item 을 위시에 담을 때 이미 한 번 차감됐다. 차감 여부의 기준은 경로가 아니라 "새 파싱 작업이 큐에
    // 들어가는가" 이고, 이동은 여기 해당하지 않는다(같은 기준으로 새로고침은 파싱이 한 번 더 돌아 차감한다).
    @Transactional
    fun addItemsFromWish(
        userId: UUID,
        command: AddTournamentItemsFromWish,
    ): List<Long> {
        val tournament =
            tournamentRepository.findTournamentByIdForUpdate(command.tournamentId)
                ?: throw TournamentException.notFoundTournament()
        if (!tournament.isPending()) throw TournamentException.notPendingTournament()
        if (!tournament.isRoot()) throw TournamentException.clonedTournamentCannotAddItems()
        tournamentUserRepository.findByTournamentIdAndUserId(command.tournamentId, userId)
            ?: throw TournamentException.forbiddenTournament()
        // 토너먼트에 이미 출전한 item 들 — tournament_item 의 고정 snapshot 에서 itemId 를 모은다(snapshot 단일 출처).
        val existingTournamentItems = tournamentItemRepository.findAllByTournamentId(command.tournamentId)
        val existingItemIds =
            itemSnapshotRepository.findByIds(existingTournamentItems.map { it.snapshotId }).map { it.itemId }.toSet()
        // 요청 내 중복 확인 — wishCount 는 unique itemId 기준이라 먼저 걸러야 정확하다
        val requestedItemIds = command.itemIds.toSet()
        if (requestedItemIds.size != command.itemIds.size) throw TournamentException.duplicateTournamentItem()
        val wishCount = wishRepository.countByItemIdsAndUserId(command.itemIds, userId)
        if (wishCount < command.itemIds.size) throw TournamentException.itemNotInWishlist()
        if (requestedItemIds.any { it in existingItemIds }) throw TournamentException.duplicateTournamentItem()
        if (existingItemIds.size + command.itemIds.size > TOURNAMENT_MAX_ITEM_COUNT) {
            throw TournamentException.tooManyTournamentItems()
        }
        val foundItems = itemRepository.findByIds(command.itemIds)
        val foundItemIds = foundItems
            .map { it.getId() }
            .toSet()
        if (command.itemIds.any { it !in foundItemIds }) throw TournamentException.notFoundItems()
        // 출전 시점에 위시의 활성 snapshot 을 tournament_item 에 고정한다 — 이후 위시 갱신과 무관하게 그 버전을 본다.
        // item 정체성은 snapshot.itemId 단일 출처다 — wish 의 활성 snapshot 을 끌어와 itemId→snapshot 으로 맵핑한다.
        val activeSnapshotByItemId =
            itemSnapshotRepository
                .findByIds(wishRepository.findByItemIdsAndUserId(command.itemIds, userId).map { it.snapshotId })
                .associateBy { it.itemId }
        // 파싱 대기·진행 중(PENDING·PROCESSING)이거나 실패(FAILED)한 상품은 이름·가격이 비어 출전에 부적합하다. 활성 snapshot 이 READY 인 것만 허용.
        if (activeSnapshotByItemId.size != command.itemIds.size || activeSnapshotByItemId.values.any { !it.isReady() }) {
            throw TournamentException.itemNotReady()
        }
        val savedItemIds = tournamentItemRepository
            .saveAll(
                command.itemIds.map { itemId ->
                    val snapshot =
                        activeSnapshotByItemId[itemId]
                            ?: error("wish 의 활성 snapshot 없음 — itemId=$itemId, userId=$userId")
                    TournamentItem(
                        tournamentId = command.tournamentId,
                        userId = userId,
                        snapshotId = snapshot.getId(),
                    )
                },
            )
            .map { it.getId() }
        // 여러 개를 한 번에 추가해도 "아이템이 추가됐다"는 사실은 1건이라 이벤트도 1회만 발행한다.
        eventPublisher.publishEvent(TournamentItemAdded(tournamentId = command.tournamentId, actorId = userId))
        return savedItemIds
    }

    @Transactional
    fun start(
        userId: UUID,
        tournamentId: Long,
    ): StartResult {
        // 상태 전이(PENDING→IN_PROGRESS) + 이벤트 발행을 하므로 행 락으로 읽는다. 락 없이 읽으면 동시 요청이 둘 다
        // PENDING 검증을 통과해 TournamentStarted 를 중복 발행(참가자에게 시작 알림 중복 도달)할 수 있다.
        // 다른 상태 전이 메서드(join·recordMatch 등)와 동일한 forUpdate 패턴.
        val tournament =
            tournamentRepository.findTournamentByIdForUpdate(tournamentId)
                ?: throw TournamentException.notFoundTournament()
        val callerTU =
            tournamentUserRepository.findByTournamentIdAndUserId(tournamentId, userId)
                ?: throw TournamentException.forbiddenTournament()
        return if (callerTU.getId() == tournament.ownerTournamentUserId) {
            startAsOwner(tournament, callerTU, userId, tournamentId)
        } else {
            startAsMember(tournament, userId, tournamentId)
        }
    }

    private fun startAsOwner(
        tournament: Tournament,
        owner: com.depromeet.piki.tournament.domain.TournamentUser,
        userId: UUID,
        tournamentId: Long,
    ): StartResult {
        if (!tournament.isPending()) throw TournamentException.notPendingTournament()
        val tournamentItems = getEffectiveTournamentItems(tournament)
        if (tournamentItems.size !in TOURNAMENT_MIN_ITEM_COUNT..TOURNAMENT_MAX_ITEM_COUNT) {
            throw TournamentException.invalidItemCount()
        }
        val snapshotById = snapshotsOf(tournamentItems)
        // start = "겨루는 값 확정" 순간(#857). 대기실까지는 표시값이 파생(최신 기계 READY 우선)으로 움직이므로,
        // 그 파생 결과를 여기서 포인터에 박제(repin)해 "겨룬 값 = 진행·완료 화면 값 = 히스토리 값" 을 고정한다.
        // 시작 후 화면·히스토리는 파생 없이 포인터를 그대로 읽는다(당시를 보는 것이 확정).
        val displayById = itemDisplayService.resolveDisplay(snapshotById.values)
        val pinnedByTournamentItemId =
            tournamentItems.associate { tournamentItem ->
                val display = displayById[tournamentItem.snapshotId] ?: tournamentItem.requireSnapshot(snapshotById)
                if (display.getId() != tournamentItem.snapshotId) tournamentItem.repinSnapshot(display.getId())
                tournamentItem.getId() to display
            }
        // item 정체성은 snapshot.itemId 단일 출처다 — 고정 snapshot 에서 itemId 를 모아 item 존재를 검증한다.
        val itemById =
            itemRepository
                .findByIds(pinnedByTournamentItemId.values.map { it.itemId })
                .associate { it.getId() to it }
        if (pinnedByTournamentItemId.values.any { it.itemId !in itemById }) throw TournamentException.notFoundItems()
        for (tournamentItem in tournamentItems) {
            val snapshot = pinnedByTournamentItemId.getValue(tournamentItem.getId())
            if (!snapshot.isReady()) throw TournamentException.itemNotReadyToStart()
            snapshot.price ?: throw TournamentException.itemPriceRequired()
        }
        tournament.start()
        // 시작이 커밋된 뒤에만 참가자에게 전달되도록 트랜잭션 안에서 발행한다 (롤백 시 미발행).
        eventPublisher.publishEvent(TournamentStarted(tournamentId = tournamentId, actorId = userId))
        return StartResult(
            tournamentId = tournamentId,
            items = tournamentItems
                .map { item ->
                    val snapshot = pinnedByTournamentItemId.getValue(item.getId())
                    TournamentStartResult(
                        tournamentItemId = item.getId(),
                        name = snapshot.name,
                        price = snapshot.price,
                        currency = snapshot.currency,
                        imageUrl = snapshot.imageUrl,
                    )
                }
                .sortedWith(compareBy({ it.price }, { it.tournamentItemId })),
        )
    }

    private fun startAsMember(
        rootTournament: Tournament,
        userId: UUID,
        rootTournamentId: Long,
    ): StartResult {
        // 오너가 이미 시작한 뒤에만 멤버가 클론을 만들 수 있다.
        if (rootTournament.isPending()) throw TournamentException.notInProgressTournament()
        // 이미 본인이 소유한 클론이 있으면 중복 생성 방지.
        // 참여자 기준이 아니라 소유자(ownerTournamentUserId) 기준 — 타인 클론에 참여만 한 경우를 본인 클론으로 오인하지 않는다.
        val existingClones = tournamentRepository.findBySourceTournamentId(rootTournamentId)
        val ownedTournamentUserIds = tournamentUserRepository
            .findByIds(existingClones.map { it.ownerTournamentUserId }.toSet())
            .filter { it.userId == userId }
            .map { it.getId() }
            .toSet()
        val alreadyCloned = existingClones.any { it.ownerTournamentUserId in ownedTournamentUserIds }
        if (alreadyCloned) throw TournamentException.alreadyCloned()

        val effectiveItems = getEffectiveTournamentItems(rootTournament)
        require(effectiveItems.isNotEmpty()) { "ROOT 토너먼트에 아이템 없음 — tournamentId=$rootTournamentId" }

        val inviteCode = generateUniqueInviteCode()
        val clone = tournamentRepository.saveTournament(
            Tournament(
                ownerTournamentUserId = 0L,
                name = rootTournament.name,
                inviteCode = inviteCode,
                inviteExpiresAt = LocalDateTime.now().plusMinutes(TOURNAMENT_INVITE_DEFAULT_DURATION_MINUTES),
                sourceTournamentId = rootTournamentId,
            ),
        )
        val cloneTU = tournamentUserRepository.save(TournamentUser(tournamentId = clone.getId(), userId = userId))
        clone.assignOwner(cloneTU.getId())
        clone.start()

        val snapshotById = snapshotsOf(effectiveItems)
        return StartResult(
            tournamentId = clone.getId(),
            items = effectiveItems
                .map { item ->
                    val snapshot = item.requireSnapshot(snapshotById)
                    TournamentStartResult(
                        tournamentItemId = item.getId(),
                        name = snapshot.name,
                        price = snapshot.price,
                        currency = snapshot.currency,
                        imageUrl = snapshot.imageUrl,
                    )
                }
                .sortedWith(compareBy({ it.price }, { it.tournamentItemId })),
        )
    }

    @Transactional(readOnly = true)
    fun getTournamentById(
        tournamentId: Long,
        userId: UUID,
    ): TournamentDetail {
        val tournament =
            tournamentRepository.findTournamentById(tournamentId)
                ?: throw TournamentException.notFoundTournament()
        val currentUser = tournamentUserRepository.findByTournamentIdAndUserId(tournamentId, userId)
            ?: throw TournamentException.forbiddenTournament()
        val isOwner = currentUser.getId() == tournament.ownerTournamentUserId
        val isRoot = tournament.isRoot()

        return when (tournament.status) {
            TournamentStatus.PENDING -> {
                // CLONE 은 DB 아이템 행이 없으므로 ROOT 아이템을 해소한다 (ROOT 는 자기 아이템).
                val tournamentItems = getEffectiveTournamentItems(tournament)
                // 대기실은 표시값 파생(#857) — 최신 기계 READY 우선, 수기는 자기 맥락에서만. 시작되면 start 가
                // 파생 결과를 박제하므로 진행·완료 분기는 포인터 그대로 읽는다.
                val snapshotById = displayedSnapshotsOf(tournamentItems)
                val tournamentUsers = tournamentUserRepository.findByTournamentId(tournamentId)
                val userById = userRepository
                    .findByIds(
                        tournamentUsers
                            .map { it.userId }
                            .toSet(),
                    )
                    .associateBy { it.id }
                val itemCountByUserId = tournamentItems.groupingBy { it.userId }.eachCount()
                TournamentDetail.Pending(
                    tournamentId = tournament.getId(),
                    name = tournament.name,
                    inviteCode = tournament.inviteCode,
                    inviteExpiresAt = tournament.inviteExpiresAt,
                    items = tournamentItems.map { toItemDetail(it, snapshotById) },
                    participants =
                        tournamentUsers.mapNotNull { tu ->
                            userById[tu.userId]?.let { user ->
                                TournamentDetail.ParticipantDetail(
                                    userId = user.id,
                                    nickname = user.nickname,
                                    profileImage = user.profileImage,
                                    isWithdrawn = !user.isActive(),
                                    itemCount = itemCountByUserId[tu.userId] ?: 0,
                                )
                            }
                        },
                    isOwner = isOwner,
                    isRoot = isRoot,
                    sourceTournamentId = tournament.sourceTournamentId,
                )
            }

            TournamentStatus.IN_PROGRESS -> {
                // 본인이 이미 완료한 경우 — 다른 참여자가 아직 진행 중이어도 본인 결과를 반환한다.
                if (currentUser.isCompleted()) {
                    val userHistories = tournamentRepository.findHistoriesByTournamentIdAndTournamentUserId(
                        tournamentId, currentUser.getId(),
                    )
                    return buildCompleted(tournament, userHistories, computeGroupFlags(tournament), isOwner, canAddItemForTournament(tournament, userId))
                }

                // 본인 history만 사용 — 다른 참여자의 매치는 본인 진행 상태에 영향을 주지 않는다.
                val histories = tournamentRepository.findHistoriesByTournamentIdAndTournamentUserId(
                    tournamentId, currentUser.getId(),
                )

                // ROOT 가 IN_PROGRESS 인데 멤버 본인의 히스토리가 없으면, CLONE 을 아직 시작하지 않은 대기 상태다.
                // ROOT(sourceTournamentId 없음)면 pending+ownerStarted 로 "주최자가 시작했습니다, 지금 시작하세요" UI 를 분기한다.
                if (!isOwner && histories.isEmpty()) {
                    tournament.sourceTournamentId ?: return buildMemberPendingOnRoot(tournament)
                }
                // 히스토리는 currentRound ASC, id ASC 정렬이라 lastOrNull()은 라운드가 바뀌면 틀림 — ID 최대값이 가장 최근 매치
                val lastHistory = histories
                    .maxByOrNull { it.getId() }
                    ?.let { TournamentDetail.HistoryEntry.from(it) }
                // CLONE 토너먼트는 DB 아이템이 없으므로 ROOT 아이템을 해소한다.
                val allTournamentItems = getEffectiveTournamentItems(tournament)
                val currentRound = computeExpectedRound(allTournamentItems.size, histories)
                // 브래킷 파생이 라운드 시작 시점 집합(= remainingItems 의 상위집합)을 쓰므로 전체 아이템의 snapshot 을 잡는다.
                val snapshotById = snapshotsOf(allTournamentItems)
                // 단일 패스: 탈락 아이템 + 현재 라운드 대결 완료 아이템 동시 수집
                val eliminatedItemIds = mutableSetOf<Long>()
                val foughtInCurrentRoundIds = mutableSetOf<Long>()
                for (h in histories) {
                    eliminatedItemIds.add(h.loser())
                    if (h.currentRound == currentRound) {
                        foughtInCurrentRoundIds.add(h.firstTournamentItemId)
                        foughtInCurrentRoundIds.add(h.secondTournamentItemId)
                    }
                }
                // 생존 중(탈락 X) + 현재 라운드 미대결 아이템
                val remainingTournamentItems = allTournamentItems.filter { item ->
                    item.getId() !in eliminatedItemIds && item.getId() !in foughtInCurrentRoundIds
                }
                val remainingItems = remainingTournamentItems
                    .map { toItemDetail(it, snapshotById) }
                    .sortedWith(compareBy({ it.price }, { it.tournamentItemId }))
                val bracket = deriveBracket(allTournamentItems, snapshotById, histories, currentRound, currentUser.getId())
                TournamentDetail.InProgress(
                    tournamentId = tournament.getId(),
                    name = tournament.name,
                    currentRound = currentRound,
                    lastHistory = lastHistory,
                    remainingItems = remainingItems,
                    currentMatch = bracket
                        .firstUnplayed(playedPairsIn(histories, currentRound))
                        ?.let { toMatchDetail(it, allTournamentItems, snapshotById) },
                    isOwner = isOwner,
                    isRoot = isRoot,
                    sourceTournamentId = tournament.sourceTournamentId,
                )
            }

            TournamentStatus.COMPLETED -> {
                val histories = tournamentRepository.findHistoriesByTournamentIdAndTournamentUserId(
                    tournamentId, currentUser.getId(),
                )
                // Design B: 멤버는 ROOT 가 아닌 본인 CLONE 에서 플레이한다.
                // ROOT 가 COMPLETED 이고 멤버의 ROOT history 가 없으면 본인 CLONE 의 결과로 대신 응답한다.
                if (!isOwner && histories.isEmpty()) {
                    val clones = tournamentRepository.findBySourceTournamentId(tournamentId)
                    val ownerTUById = tournamentUserRepository
                        .findByIds(clones.map { it.ownerTournamentUserId }.toSet())
                        .associateBy { it.getId() }
                    // 본인이 소유한 CLONE 만 인정한다 (타인 CLONE 에 참여만 한 경우 제외).
                    val myClone = clones.firstOrNull { ownerTUById[it.ownerTournamentUserId]?.userId == userId }
                    // 옵션 A: 아직 본인 CLONE 을 시작하지 않은 참여자는 ROOT 가 COMPLETED 여도 403 대신
                    // 시작 가능 상태(pending+ownerStarted)를 받아 본인 CLONE 을 만들어 진행할 수 있다.
                        ?: return buildMemberPendingOnRoot(tournament)
                    val myCloneOwnerTU = ownerTUById.getValue(myClone.ownerTournamentUserId)
                    if (!myCloneOwnerTU.isCompleted()) throw TournamentException.forbiddenTournament()
                    val cloneHistories = tournamentRepository.findHistoriesByTournamentIdAndTournamentUserId(
                        myClone.getId(), myCloneOwnerTU.getId(),
                    )
                    return buildCompleted(myClone, cloneHistories, computeGroupFlags(tournament), false, true)
                }
                buildCompleted(tournament, histories, computeGroupFlags(tournament), isOwner, canAddItemForTournament(tournament, userId))
            }
        }
    }

    // 아직 본인 CLONE 을 시작하지 않은 멤버에게 내려주는 "시작 가능" 대기 응답.
    // ROOT 가 IN_PROGRESS·COMPLETED 어느 쪽이든, 멤버는 ROOT 아이템·참여자를 보며 본인 플레이를 시작할 수 있다.
    private fun buildMemberPendingOnRoot(root: Tournament): TournamentDetail.Pending {
        val tournamentItems = tournamentItemRepository.findAllByTournamentId(root.getId())
        val snapshotById = snapshotsOf(tournamentItems)
        val tournamentUsers = tournamentUserRepository.findByTournamentId(root.getId())
        val userById = userRepository
            .findByIds(tournamentUsers.map { it.userId }.toSet())
            .associateBy { it.id }
        val itemCountByUserId = tournamentItems.groupingBy { it.userId }.eachCount()
        return TournamentDetail.Pending(
            tournamentId = root.getId(),
            name = root.name,
            inviteCode = root.inviteCode,
            inviteExpiresAt = root.inviteExpiresAt,
            items = tournamentItems.map { toItemDetail(it, snapshotById) },
            participants = tournamentUsers.mapNotNull { tu ->
                userById[tu.userId]?.let { user ->
                    TournamentDetail.ParticipantDetail(
                        userId = user.id,
                        nickname = user.nickname,
                        profileImage = user.profileImage,
                        isWithdrawn = !user.isActive(),
                        itemCount = itemCountByUserId[tu.userId] ?: 0,
                    )
                }
            },
            isOwner = false,
            isRoot = root.isRoot(),
            sourceTournamentId = null,
            ownerStarted = true,
        )
    }

    @Transactional(readOnly = true)
    fun getTournamentItem(
        userId: UUID,
        tournamentId: Long,
        tournamentItemId: Long,
    ): TournamentItemDetail {
        val tournament = tournamentRepository.findTournamentById(tournamentId)
            ?: throw TournamentException.notFoundTournament()
        tournamentUserRepository.findByTournamentIdAndUserId(tournamentId, userId)
            ?: throw TournamentException.forbiddenTournament()
        val tournamentItem = tournamentItemRepository.findById(tournamentItemId)
            ?: throw TournamentException.notFoundTournamentItem()
        // 클론은 DB 아이템 행이 없어 원본(source) 아이템을 이어받아 조회한다(목록·시작과 동일). 스코프는 자기 id 가 아니라
        // effective(원본) 기준으로 검사해야 목록에서 받은 id 로 단건 조회가 통과한다(#977). ROOT 는 source 가 없어 자기 id.
        val effectiveTournamentId = tournament.sourceTournamentId ?: tournamentId
        if (tournamentItem.tournamentId != effectiveTournamentId) throw TournamentException.notFoundTournamentItem()
        // 표시값: 대기실(PENDING)은 파생(#857), 시작 후는 start 가 박제한 포인터 그대로(겨룬 값 고정).
        // sourceUrl(상품 링크)은 그 snapshot 의 item(정체성)에서 읽는다.
        val pointer = tournamentItem.requireSnapshot(snapshotsOf(listOf(tournamentItem)))
        val snapshot = if (tournament.isPending()) itemDisplayService.resolveDisplay(pointer) else pointer
        val item = itemRepository.findById(snapshot.itemId)
            ?: throw TournamentException.notFoundTournamentItem()
        // 이 상품이 요청자 본인의 위시에 담겨 있으면 그 위시의 개인 메모를 함께 내린다(#906). 조회를 요청자
        // 소유 wish 로 한정하므로 남의 메모는 구조적으로 내려갈 수 없다. 게스트·미담음·삭제된 위시는 조회에 안 잡힌다.
        val memo = wishRepository.findByItemIdsAndUserId(listOf(item.getId()), userId).firstOrNull()?.memo
        return TournamentItemDetail(
            tournamentItemId = tournamentItem.getId(),
            itemId = item.getId(),
            sourceUrl = item.link?.toString(),
            name = snapshot.name,
            imageUrl = snapshot.imageUrl,
            price = snapshot.price,
            currency = snapshot.currency,
            status = snapshot.status,
            memo = memo,
        )
    }

    @Transactional(readOnly = true)
    fun getTournaments(
        userId: UUID,
        statuses: List<TournamentStatus>?,
        playType: TournamentPlayType?,
        ownedOnly: Boolean,
        limit: Int?,
    ): List<TournamentSummary> {
        limit?.let { if (it < 1) throw TournamentException.invalidLimit() }

        // 가시성 필터·playType·최근순·limit 을 쿼리가 끝낸다. 가시성은 per-user effective status 로 판정한다(#882):
        // owner(내가 만든 ROOT·내 CLONE)는 전역 status 그대로, 참여자(클론 없는 ROOT)는 완료돼도 나에겐 IN_PROGRESS 로 캡한다.
        // ownedOnly=true(홈)는 참여 갈래를 꺼 "내가 owner 인 것" 만 노출한다. status 와는 AND 로 걸린다.
        // playType 은 파생 상태라 앱에서 거르면 limit 이 먼저 걸려 요구한 개수보다 적게 나온다 (쿼리에서 함께 판정).
        // 참가자·프로필은 남은 토너먼트에 대해서만 읽는다 (홈 카드 limit=3 이 내 전체 이력을 선로드하지 않게).
        val limited = tournamentRepository.findVisibleByUserId(userId, statuses, playType, ownedOnly, limit)
        if (limited.isEmpty()) return emptyList()

        val tournamentUsers = tournamentUserRepository.findByTournamentIds(limited.map { it.getId() })
        val userIds =
            tournamentUsers
                .map { it.userId }
                .toSet()
        val profileImageByUserId =
            userRepository
                .findByIds(userIds)
                .associate { it.id to it.profileImage }
        val profileImagesByTournamentId =
            tournamentUsers
                .groupBy { it.tournamentId }
                .mapValues { (_, users) -> users.mapNotNull { profileImageByUserId[it.userId] } }

        // 썸네일도 남은 토너먼트에 대해서만 조회한다 (잘릴 것의 아이템은 안 읽음).
        // CLONE 은 자기 tournament_item 이 없고 sourceTournamentId(ROOT)의 아이템을 쓰므로, ROOT id 로 조회한 뒤 CLONE 에 매핑한다.
        val rootIdByTournamentId = limited.associate { it.getId() to (it.sourceTournamentId ?: it.getId()) }
        val thumbnailsByRootId = thumbnailUrlsByTournamentId(rootIdByTournamentId.values.distinct())

        // 내 tournament_user id 를 토너먼트별로 — effectiveStatus 계산에서 "내가 이 방의 owner 냐" 판정에 쓴다.
        val myTournamentUserIdByTournamentId =
            tournamentUsers
                .filter { it.userId == userId }
                .associate { it.tournamentId to it.getId() }

        return limited.map { tournament ->
            // 쿼리 가시성과 동일한 per-user effective status(#882): owner(내가 만든 ROOT·내 CLONE)는 전역 status 그대로,
            // 참여자(owner 아니고 내 클론 없는 ROOT)는 완료돼도 나에겐 IN_PROGRESS 로 캡한다(쿼리가 그런 ROOT 만 참여 갈래로 반환).
            // 소유 판정은 쿼리와 같이 ownerTournamentUserId 로만 한다 — "CLONE 이면 내 것" 은 성립하지 않는다
            // (초대코드 join 이 ROOT 를 강제하지 않아 남의 CLONE 에 참여자로 들어갈 수 있다).
            val effectiveStatus =
                when {
                    tournament.ownerTournamentUserId == myTournamentUserIdByTournamentId[tournament.getId()] -> tournament.status
                    tournament.status == TournamentStatus.COMPLETED -> TournamentStatus.IN_PROGRESS
                    else -> tournament.status
                }
            TournamentSummary.of(
                tournament = tournament,
                participantProfileImages = profileImagesByTournamentId[tournament.getId()] ?: emptyList(),
                thumbnailUrls = thumbnailsByRootId[rootIdByTournamentId.getValue(tournament.getId())] ?: emptyList(),
                effectiveStatus = effectiveStatus,
            )
        }
    }

    // 토너먼트별 대표 썸네일(최근 등록 아이템 중 이미지 있는 것 최대 2장) 배치 조립.
    // 기존 배치 조회 2회(tournament_items → item_snapshots)만 쓰고 N+1 을 만들지 않는다.
    private fun thumbnailUrlsByTournamentId(tournamentIds: List<Long>): Map<Long, List<String>> {
        if (tournamentIds.isEmpty()) return emptyMap()
        val items = tournamentItemRepository.findAllByTournamentIds(tournamentIds)
        if (items.isEmpty()) return emptyMap()
        // READY 스냅샷의 이미지만 후보로 삼는다 — FAILED/PROCESSING 의 stale 이미지가 카드에 노출되지 않게 상태로 거른다.
        val readyImageUrlBySnapshotId =
            itemSnapshotRepository
                .findByIds(items.map { it.snapshotId })
                .associate { snapshot -> snapshot.getId() to snapshot.imageUrl?.takeIf { snapshot.status == ItemStatus.READY } }
        return items
            .groupBy { it.tournamentId }
            .mapValues { (_, tournamentItems) ->
                TournamentThumbnails.select(
                    tournamentItems.map {
                        TournamentThumbnails.Candidate(recency = it.getId(), imageUrl = readyImageUrlBySnapshotId[it.snapshotId])
                    },
                )
            }
    }

    @Transactional
    fun recordMatch(
        userId: UUID,
        command: RecordMatch,
    ): RecordMatchResult {
        val tournament =
            tournamentRepository.findTournamentByIdForUpdate(command.tournamentId)
                ?: throw TournamentException.notFoundTournament()
        // 진행 중 검사는 "새 매치를 기록해도 되나" 를 묻는 것이라 멱등 판정 뒤로 미룬다 —
        // 결승을 기록하면 토너먼트가 즉시 COMPLETED 로 바뀌므로, 여기서 먼저 막으면
        // 가장 흔한 재시도(결승 응답을 못 받고 재전송)만 멱등에서 빠진다.
        val tournamentUser =
            tournamentUserRepository.findByTournamentIdAndUserId(command.tournamentId, userId)
                ?: throw TournamentException.forbiddenTournament()
        // ROOT 토너먼트는 오너만 플레이한다. 멤버는 본인 CLONE 에서 진행해야 한다.
        if (tournament.isRoot() && tournamentUser.getId() != tournament.ownerTournamentUserId) {
            throw TournamentException.forbiddenTournament()
        }
        if (command.selectedTournamentItemId != command.firstTournamentItemId &&
            command.selectedTournamentItemId != command.secondTournamentItemId
        ) {
            throw TournamentException.invalidWinner()
        }

        // CLONE 토너먼트는 DB 에 아이템 행이 없어 ROOT 의 아이템을 사용한다.
        val allTournamentItems = getEffectiveTournamentItems(tournament)
        val tournamentItemIds = allTournamentItems.map { it.getId() }.toSet()
        if (command.firstTournamentItemId !in tournamentItemIds ||
            command.secondTournamentItemId !in tournamentItemIds
        ) {
            throw TournamentException.invalidTournamentItem()
        }

        // 본인 history만 사용 — 다른 참여자의 매치는 본인 진행에 영향을 주지 않는다.
        val histories = tournamentRepository.findHistoriesByTournamentIdAndTournamentUserId(
            command.tournamentId, tournamentUser.getId(),
        )
        val snapshotById = snapshotsOf(allTournamentItems)

        // 멱등(#683): 같은 조합이 이미 기록됐으면 재전송·뒤로가기로 인한 재시도다.
        // 이미 기록된 매치의 패자는 아래 탈락 집합에 들어 있으므로, 탈락 검사보다 먼저 판정해야
        // 정상 재시도가 409 ELIMINATED 로 오인되지 않는다.
        histories
            .firstOrNull { h ->
                RoundBracket
                    .MatchPair(h.firstTournamentItemId, h.secondTournamentItemId)
                    .isSamePair(command.firstTournamentItemId, command.secondTournamentItemId)
            }?.let { recorded ->
                // 결과를 뒤집으려는 시도는 멱등이 아니다.
                if (recorded.selectedTournamentItemId != command.selectedTournamentItemId) {
                    throw TournamentException.matchAlreadyRecorded()
                }
                // 결승을 재전송한 경우 토너먼트는 이미 COMPLETED 다 — 최초 응답과 같은 순위 결과를 재구성해 돌려준다.
                // 그러지 않으면 클라이언트가 최종 순위를 못 받고, 방금 선택을 마친 사용자에게
                // "토너먼트가 진행 중일 때만 할 수 있어요" 가 뜬다.
                if (tournament.isCompleted()) {
                    return RecordMatchResult(
                        nextMatch = null,
                        completed = buildCompleted(
                            tournament, histories, computeGroupFlags(tournament),
                            tournamentUser.getId() == tournament.ownerTournamentUserId,
                            canAddItemForTournament(tournament, userId),
                        ),
                    )
                }
                // 그 매치가 속한 라운드로 다음 매치를 다시 파생한다. 라운드가 이미 끝났으면 null 이 나오고,
                // 클라이언트는 현행대로 GET 을 다시 불러 다음 라운드를 받는다.
                return RecordMatchResult(
                    nextMatch = nextMatchOf(
                        allTournamentItems, snapshotById, histories, recorded.currentRound, tournamentUser.getId(),
                    ),
                    completed = null,
                )
            }

        // 재시도가 아닌 새 매치 기록이므로 여기서부터는 진행 중이어야 한다.
        if (!tournament.isInProgress()) throw TournamentException.notInProgressTournament()

        val eliminatedItemIds = histories.map { it.loser() }.toSet()
        if (command.firstTournamentItemId in eliminatedItemIds || command.secondTournamentItemId in eliminatedItemIds) {
            throw TournamentException.eliminatedTournamentItem()
        }
        val expectedRound = computeExpectedRound(tournamentItemIds.size, histories)
        if (command.currentRound != expectedRound) throw TournamentException.invalidCurrentRound()

        // 브래킷 무결성(#683): 소속·미탈락·라운드만 보던 기존 검증은 클라가 임의 조합([0]vs[3])을 보내도 통과했다.
        // 서버가 파생한 페어 집합에 없는 조합은 거부한다. 단 진행 순서는 검증하지 않는다 —
        // 라운드 내 매치는 서로 독립이라 순서가 최종 결과를 바꾸지 않고, 강제하면 열린 탭·재전송에서 오탐 400 만 는다.
        val bracket = deriveBracket(allTournamentItems, snapshotById, histories, expectedRound, tournamentUser.getId())
        if (!bracket.contains(command.firstTournamentItemId, command.secondTournamentItemId)) {
            throw TournamentException.invalidMatchPair()
        }

        val newHistory = TournamentHistory(
            tournamentId = command.tournamentId,
            tournamentUserId = tournamentUser.getId(),
            currentRound = command.currentRound,
            firstTournamentItemId = command.firstTournamentItemId,
            secondTournamentItemId = command.secondTournamentItemId,
            selectedTournamentItemId = command.selectedTournamentItemId,
        )
        tournamentRepository.saveHistory(newHistory)

        if (!tournament.isFinalRound(command.currentRound)) {
            return RecordMatchResult(
                nextMatch = bracket
                    .firstUnplayed(playedPairsIn(histories + newHistory, command.currentRound))
                    ?.let { toMatchDetail(it, allTournamentItems, snapshotById) },
                completed = null,
            )
        }

        // Design B: 토너먼트당 플레이어가 한 명이므로 최종 라운드 완료 즉시 COMPLETED 로 전환한다.
        tournamentUser.complete()
        tournament.complete()

        // 완료 알림 발행(#473). CLONE 완료(멤버/게스트) → ROOT 주최자에게 "완료했어요",
        // ROOT 완료(주최자 본인 진행) → 참여자에게 "결과 나왔어요". rootId 는 클론이면 원본, ROOT 면 자기 자신.
        val rootTournamentId = tournament.sourceTournamentId ?: tournament.getId()
        if (tournament.isRoot()) {
            eventPublisher.publishEvent(TournamentResultReady(rootTournamentId = rootTournamentId, actorId = userId))
        } else {
            eventPublisher.publishEvent(TournamentCompleted(rootTournamentId = rootTournamentId, actorId = userId))
        }

        val isOwner = tournamentUser.getId() == tournament.ownerTournamentUserId
        return RecordMatchResult(
            nextMatch = null,
            completed = buildCompleted(
                tournament, histories + newHistory, computeGroupFlags(tournament), isOwner,
                canAddItemForTournament(tournament, userId),
            ),
        )
    }

    private data class GroupFlags(
        val hasGroupResult: Boolean,
        val isGroupTournament: Boolean,
    )

    // 그룹 결과 관련 두 플래그를 한 번에 구한다 — 루트 기준 클론 목록·전체 TU 를 공유해 조회를 중복하지 않는다.
    //   hasGroupResult    : 완료한 고유 사용자 수 >= 2 → 그룹 결과 "조회 가능"(progressive gate, core#456).
    //   isGroupTournament : 참여한 고유 사용자 수 >= 2 → "소셜(그룹) 토너먼트 여부"(완료 무관, core#370 원래 정의).
    // 배너 "노출"은 isGroupTournament 로, "활성/비활성"은 hasGroupResult 로 가른다 — 첫 완주자가 누구든 새로고침 없이
    // 배너를 본다(#975). 솔로는 참여자가 항상 정확히 1이라 false.
    // record 가 아니라 userId 로 센다 — 같은 사용자가 ROOT TU 와 자기 CLONE 을 모두 가질 수 있어서다(주최자가 자기
    // 플레이링크로 self-clone 을 만드는 경로에 가드가 없다). 그대로 record 를 세면 1명이 2로 잡혀 solo 가 그룹으로 오인된다.
    private fun computeGroupFlags(tournament: Tournament): GroupFlags {
        val rootId = tournament.sourceTournamentId ?: tournament.getId()
        val clones = tournamentRepository.findBySourceTournamentId(rootId)
        val rootUsers = tournamentUserRepository.findByTournamentId(rootId)
        val cloneOwnerById = tournamentUserRepository
            .findByIds(clones.map { it.ownerTournamentUserId }.toSet())
            .associateBy { it.getId() }
        val participantUserIds = buildSet {
            rootUsers.forEach { add(it.userId) }
            clones.forEach { clone -> cloneOwnerById[clone.ownerTournamentUserId]?.let { add(it.userId) } }
        }
        val completedUserIds = buildSet {
            rootUsers.filter { it.isCompleted() }.forEach { add(it.userId) }
            clones.filter { it.isCompleted() }.forEach { clone -> cloneOwnerById[clone.ownerTournamentUserId]?.let { add(it.userId) } }
        }
        return GroupFlags(
            hasGroupResult = completedUserIds.size >= 2,
            isGroupTournament = participantUserIds.size >= 2,
        )
    }

    // ROOT 는 항상 아이템 담기 가능. CLONE 은 소셜 초대로 ROOT 에 TournamentUser 가 있으면 true,
    // 플레이링크 경유(ROOT 에 없음)이면 false.
    private fun canAddItemForTournament(tournament: Tournament, userId: UUID): Boolean {
        if (tournament.isRoot()) return true
        val rootId = tournament.sourceTournamentId ?: error("CLONE must have sourceTournamentId")
        return tournamentUserRepository.findByTournamentIdAndUserId(rootId, userId)
            ?.let { true } ?: false
    }

    private fun buildCompleted(
        tournament: Tournament,
        histories: List<TournamentHistory>,
        groupFlags: GroupFlags,
        isOwner: Boolean,
        canAddItem: Boolean,
    ): TournamentDetail.Completed {
        val isRoot = tournament.isRoot()
        val rankedPairs = computeRanking(histories)
        val tournamentItemById = tournamentItemRepository
            .findByIds(rankedPairs.map { it.first })
            .associateBy { it.getId() }
        val snapshotById = snapshotsOf(tournamentItemById.values)
        return TournamentDetail.Completed(
            tournamentId = tournament.getId(),
            name = tournament.name,
            result = rankedPairs.map { (tournamentItemId, rank) ->
                val tournamentItem = tournamentItemById.getValue(tournamentItemId)
                val snapshot = tournamentItem.requireSnapshot(snapshotById)
                RankedItem(
                    rank = rank,
                    tournamentItemId = tournamentItemId,
                    itemId = snapshot.itemId,
                    name = snapshot.name,
                    price = snapshot.price,
                    currency = snapshot.currency,
                    imageUrl = snapshot.imageUrl,
                )
            },
            hasGroupResult = groupFlags.hasGroupResult,
            isGroupTournament = groupFlags.isGroupTournament,
            isOwner = isOwner,
            isRoot = isRoot,
            canAddItem = canAddItem,
            playLinkExpiresAt = tournament.playLinkExpiresAt,
            sourceTournamentId = tournament.sourceTournamentId,
        )
    }

    @Transactional
    fun deleteTournament(
        userId: UUID,
        tournamentId: Long,
    ) {
        val tournament =
            tournamentRepository.findTournamentByIdForUpdate(tournamentId)
                ?: throw TournamentException.notFoundTournament()
        val tournamentUser =
            tournamentUserRepository.findByTournamentIdAndUserId(tournamentId, userId)
                ?: throw TournamentException.forbiddenTournament()
        if (tournamentUser.getId() != tournament.ownerTournamentUserId) throw TournamentException.forbiddenTournament()
        if (tournament.isInProgress()) throw TournamentException.inProgressTournamentCannotBeDeleted()
        if (tournament.isPending()) {
            // PENDING: 아무도 플레이하지 않은 상태라 전체 cascade 삭제한다.
            tournamentItemRepository.softDeleteAllByTournamentId(tournamentId)
            tournamentUserRepository.softDeleteAllByTournamentId(tournamentId)
            tournamentRepository.softDeleteTournament(tournamentId)
        } else {
            // COMPLETED: 주최자의 TU 만 제거하고 플레이 링크를 무효화한다.
            // 토너먼트·히스토리·멤버 CLONE 은 유지되어 다른 참여자가 계속 접근 가능하고
            // 그룹 결과에서도 주최자 내역이 보존된다.
            tournamentUserRepository.softDeleteByTournamentIdAndUserId(tournamentId, userId)
            tournament.expirePlayLink()
        }
    }

    @Transactional
    fun updateInviteExpiry(
        userId: UUID,
        tournamentId: Long,
        newExpiresAt: LocalDateTime,
    ): LocalDateTime {
        val now = LocalDateTime.now()
        require(!newExpiresAt.isAfter(now.plusHours(24))) { "초대 마감 시각은 24시간 이내여야 합니다" }
        val tournament =
            tournamentRepository.findTournamentByIdForUpdate(tournamentId)
                ?: throw TournamentException.notFoundTournament()
        val tournamentUser =
            tournamentUserRepository.findByTournamentIdAndUserId(tournamentId, userId)
                ?: throw TournamentException.forbiddenTournament()
        if (tournamentUser.getId() != tournament.ownerTournamentUserId) throw TournamentException.forbiddenTournament()
        if (!tournament.isPending()) throw TournamentException.notPendingTournament()
        tournament.updateInviteExpiry(newExpiresAt)
        return newExpiresAt
    }

    // userId 는 optional — preview 는 permitAll 이라 미인증(토큰 없음)이면 null 로 들어온다.
    // 토큰이 있으면 그 유저의 참여 여부(joined)를 계산하고, 없으면 알 수 없으므로 false.
    @Transactional(readOnly = true)
    fun getInvitePreview(
        tournamentId: Long,
        userId: UUID?,
    ): TournamentInvitePreview {
        val tournament =
            tournamentRepository.findTournamentById(tournamentId)
                ?: throw TournamentException.notFoundTournament()
        tournament.checkJoinable(null)
        val itemCount = tournamentItemRepository.countByTournamentId(tournamentId)
        val participantCount = tournamentUserRepository.countByTournamentId(tournamentId)
        val joined = userId?.let { tournamentUserRepository.existsByTournamentIdAndUserId(tournamentId, it) } ?: false
        return TournamentInvitePreview(
            tournamentId = tournamentId,
            tournamentName = tournament.name,
            itemCount = itemCount,
            participantCount = participantCount,
            joined = joined,
        )
    }

    @Transactional(readOnly = true)
    fun getInvitePreviewByCode(
        code: String,
        userId: UUID?,
    ): TournamentInvitePreview {
        val tournament =
            tournamentRepository.findTournamentByInviteCode(code)
                ?: throw TournamentException.invalidInviteCode()
        tournament.checkJoinable(null)
        val itemCount = tournamentItemRepository.countByTournamentId(tournament.getId())
        val participantCount = tournamentUserRepository.countByTournamentId(tournament.getId())
        val joined = userId?.let { tournamentUserRepository.existsByTournamentIdAndUserId(tournament.getId(), it) } ?: false
        return TournamentInvitePreview(
            tournamentId = tournament.getId(),
            tournamentName = tournament.name,
            itemCount = itemCount,
            participantCount = participantCount,
            joined = joined,
        )
    }

    @Transactional
    fun createPlayLink(
        userId: UUID,
        tournamentId: Long,
    ): LocalDateTime {
        val tournament =
            tournamentRepository.findTournamentByIdForUpdate(tournamentId)
                ?: throw TournamentException.notFoundTournament()
        if (!tournament.isCompleted()) throw TournamentException.notCompletedTournament()
        val tournamentUser =
            tournamentUserRepository.findByTournamentIdAndUserId(tournamentId, userId)
                ?: throw TournamentException.forbiddenTournament()
        if (tournamentUser.getId() != tournament.ownerTournamentUserId) throw TournamentException.forbiddenTournament()
        if (!tournament.isRoot()) throw TournamentException.clonedTournamentCannotSharePlayLink()
        tournament.playLinkExpiresAt?.let { throw TournamentException.playLinkAlreadyCreated() }
        val expiresAt = LocalDateTime
            .now()
            .plusDays(PLAY_LINK_DURATION_DAYS)
        tournament.createPlayLink(expiresAt)
        return expiresAt
    }

    @Transactional(readOnly = true)
    fun getPlayLinkInfo(tournamentId: Long): PlayLinkInfo {
        val tournament =
            tournamentRepository.findTournamentById(tournamentId)
                ?: throw TournamentException.notFoundTournament()
        val expiresAt = tournament.playLinkExpiresAt ?: throw TournamentException.playLinkNotCreated()
        if (!tournament.isPlayLinkValid()) throw TournamentException.playLinkExpired()
        val itemCount = tournamentItemRepository.countByTournamentId(tournamentId)
        return PlayLinkInfo(
            sourceTournamentId = tournamentId,
            tournamentName = tournament.name,
            itemCount = itemCount,
            playLinkExpiresAt = expiresAt,
        )
    }

    // create 와 달리 회원 게이트를 두지 않는다(#339) — 여기서 만들어지는 것은 CLONE 이고, CLONE 은
    // 아이템 추가가 막혀 있어(clonedTournamentCannotAddItems) 추출·LLM 비용을 만들 수 없다. 플레이 링크로
    // 들어와 바로 플레이하는 것은 게스트의 핵심 시나리오라, 비용이 0 인 이 경로까지 회원 전용으로 만들지 않는다.
    @Transactional
    fun createFromPlayLink(
        userId: UUID,
        sourceTournamentId: Long,
    ): Long {
        val sourceTournament =
            tournamentRepository.findTournamentByIdForUpdate(sourceTournamentId)
                ?: throw TournamentException.notFoundTournament()

        // get-or-create: 이미 "본인이 소유한" 클론이 있으면 그 id 로 "이어서 진행하기".
        // 참여자(TournamentUser) 기준이 아니라 소유자(ownerTournamentUserId) 기준으로 판별한다 —
        // 타인 클론에 초대코드로 참여만 한 경우를 본인 클론으로 오인해 잘못 라우팅하지 않기 위함.
        // 원본 플레이링크 만료와 무관하게 돌려준다 — 클론은 자체 라이프사이클을 가진다.
        val clones = tournamentRepository.findBySourceTournamentId(sourceTournamentId)
        val ownedTournamentUserIds = tournamentUserRepository
            .findByIds(clones.map { it.ownerTournamentUserId }.toSet())
            .filter { it.userId == userId }
            .map { it.getId() }
            .toSet()
        clones
            .firstOrNull { it.ownerTournamentUserId in ownedTournamentUserIds }
            ?.let { return it.getId() }

        // 신규 클론 생성 경로에서만 플레이링크 유효성을 검증한다.
        sourceTournament.playLinkExpiresAt ?: throw TournamentException.playLinkNotCreated()
        if (!sourceTournament.isPlayLinkValid()) throw TournamentException.playLinkExpired()

        val inviteCode = generateUniqueInviteCode()
        val newTournament = tournamentRepository.saveTournament(
            Tournament(
                ownerTournamentUserId = 0L,
                name = sourceTournament.name,
                inviteCode = inviteCode,
                inviteExpiresAt = LocalDateTime
                    .now()
                    .plusMinutes(TOURNAMENT_INVITE_DEFAULT_DURATION_MINUTES),
                sourceTournamentId = sourceTournamentId,
            ),
        )
        val tournamentUser = tournamentUserRepository.save(
            TournamentUser(tournamentId = newTournament.getId(), userId = userId),
        )
        newTournament.assignOwner(tournamentUser.getId())
        // 플레이링크로 새 클론을 만들어 플레이를 시작한 사실을 ROOT 주최자에게 알린다(#473). get-or-create 의 신규 생성 분기에서만 발행한다.
        eventPublisher.publishEvent(TournamentPlayedFromLink(rootTournamentId = sourceTournamentId, actorId = userId))
        // CLONE 은 DB 에 아이템 행을 두지 않는다. getEffectiveTournamentItems 가 sourceTournamentId 경유로 원본 아이템을 해소한다.
        return newTournament.getId()
    }

    @Transactional(readOnly = true)
    fun getGroupResult(
        userId: UUID,
        tournamentId: Long,
    ): GroupResult {
        val tournament =
            tournamentRepository.findTournamentById(tournamentId)
                ?: throw TournamentException.notFoundTournament()
        if (!tournament.isRoot()) throw TournamentException.clonedTournamentCannotViewGroupResult()
        val allClones = tournamentRepository.findBySourceTournamentId(tournamentId)
        val requesterRootTU = tournamentUserRepository.findByTournamentIdAndUserId(tournamentId, userId)
        val cloneOwnerTUById = tournamentUserRepository
            .findByIds(allClones.map { it.ownerTournamentUserId }.toSet())
            .associateBy { it.getId() }
        // 본인이 소유한 CLONE (멤버·게스트). 게스트는 ROOT TU 없이 본인 CLONE 만 가진다.
        val requesterOwnedClone = allClones.firstOrNull { cloneOwnerTUById[it.ownerTournamentUserId]?.userId == userId }
        // 참여자(ROOT TU 또는 ROOT 클론 소유자)가 아니면 조회 불가.
        // 정책 변경: 게스트(완료된 플레이링크 CLONE 소유자)도 그룹 결과를 조회할 수 있다.
        requesterRootTU ?: requesterOwnedClone ?: throw TournamentException.forbiddenTournament()

        // Progressive gate: 본인 플레이가 완료됐고 전체 완료 인원 ≥2 일 때만 조회 가능하다.
        // 주최자는 ROOT 진행, 멤버·게스트는 본인 CLONE 진행이 완료 기준이다.
        val requesterIsOwner = requesterRootTU?.getId() == tournament.ownerTournamentUserId
        val requesterHasCompleted = if (requesterIsOwner) {
            requesterRootTU?.isCompleted() ?: false
        } else {
            requesterOwnedClone?.isCompleted() ?: false
        }
        // completedRootTUs·completedClones 는 아래 plays 빌드에도 쓰이므로 미리 구해 게이트와 공유한다.
        // computeGroupFlags 를 별도 호출하면 findBySourceTournamentId 등을 중복 조회하게 되므로 인라인으로 처리한다.
        val completedRootTUs = tournamentUserRepository.findCompletedByTournamentId(tournamentId)
        val completedClones = allClones.filter { it.isCompleted() }
        // 완료자는 record 가 아니라 userId 로 센다 — 주최자가 자기 self-clone 을 완주하면 ROOT·CLONE 두 record 가
        // 같은 사용자다(computeGroupFlags 와 동일 기준). record 로 세면 solo 가 게이트를 통과해버린다.
        val completedUserIds = buildSet {
            completedRootTUs.forEach { add(it.userId) }
            completedClones.forEach { clone -> cloneOwnerTUById[clone.ownerTournamentUserId]?.let { add(it.userId) } }
        }
        if (!requesterHasCompleted || completedUserIds.size < 2) {
            throw TournamentException.groupResultNotAvailable()
        }

        // "play" = 한 참여자의 독립적인 토너먼트 진행 단위.
        // 루트 토너먼트의 각 완료 TU + 각 완료된 클론 토너먼트의 오너 TU.
        data class Play(val tournamentId: Long, val tuId: Long, val userUUID: UUID)
        // cloneOwnerTUById 는 위 권한 게이트에서 allClones 전체로 구해 재사용한다 (completedClones ⊆ allClones).

        val plays = buildList {
            completedRootTUs.forEach { tu -> add(Play(tournamentId, tu.getId(), tu.userId)) }
            completedClones.forEach { clone ->
                val ownerTU = cloneOwnerTUById[clone.ownerTournamentUserId] ?: return@forEach
                add(Play(clone.getId(), ownerTU.getId(), ownerTU.userId))
            }
        }.distinctBy { it.userUUID } // 같은 사용자의 ROOT·self-clone 플레이가 결과에 두 번 실리지 않게 dedup (ROOT 플레이 우선).

        val userById = userRepository
            .findByIds(plays.map { it.userUUID }.toSet())
            .associateBy { it.id }

        // "선택자" = 해당 아이템을 자신의 1위(우승)로 고른 참여자
        // itemId 단위로 집계하고 정렬 후 그룹 rank 를 부여한다.
        val winnersByItemId = mutableMapOf<Long, MutableList<ParticipantSummary>>()
        val referenceItemsById: MutableMap<Long, RankedItem> = mutableMapOf()

        val allTournamentIds = plays.map { it.tournamentId }.distinct()
        val allHistories = tournamentRepository.findHistoriesByTournamentIds(allTournamentIds)
        val allTournamentItemIds = allHistories.map { it.firstTournamentItemId } +
            allHistories.map { it.secondTournamentItemId }
        val tItemById = tournamentItemRepository.findByIds(allTournamentItemIds).associateBy { it.getId() }
        val snapshotById = snapshotsOf(tItemById.values)

        // play 루프 안에서 allHistories 를 매번 filter 하면 O(plays × histories) 인메모리 스캔이 된다.
        // (tournamentId, tournamentUserId) 로 1회 그룹핑해 각 play 를 O(1) 조회로 낮춘다.
        // 루트 history 는 tournamentUserId 로 참여자를 분리하고, 클론 history 는 tournamentUserId=null 이라
        // 그 클론 tournamentId 의 단일 play 에 귀속된다 — null 버킷을 함께 합쳐 기존 `?: true` 의미를 보존한다.
        val historiesByTidAndTuId = allHistories.groupBy { it.tournamentId to it.tournamentUserId }

        for (play in plays) {
            // 루트 토너먼트는 TU ID로 분리, 클론 토너먼트는 tournamentId로 분리
            val exactHistories = historiesByTidAndTuId[play.tournamentId to play.tuId].orEmpty()
            val nullHistories = historiesByTidAndTuId[play.tournamentId to null].orEmpty()
            // 정상 케이스는 두 버킷 중 한쪽만 차 있다 (루트 play=exact, 클론 play=null).
            // 그때는 리스트 복사 없이 그 버킷을 그대로 재사용하고, 둘 다 있을 때만 합친다 (요청당 allocation·GC 절감).
            val playHistories = when {
                exactHistories.isEmpty() -> nullHistories
                nullHistories.isEmpty() -> exactHistories
                else -> exactHistories + nullHistories
            }
            val ranked = runCatching { computeRanking(playHistories) }.getOrNull() ?: continue
            val user = userById[play.userUUID] ?: continue
            val participant = ParticipantSummary(
                userId = user.id,
                nickname = user.nickname,
                profileImage = user.profileImage,
                isWithdrawn = !user.isActive(),
            )

            for ((tournamentItemId, rank) in ranked) {
                // tItem 누락은 삭제된 출전 아이템이 history 에 남은 정상 경우라 건너뛴다. 그러나 tItem 이 살아있으면
                // snapshot 은 불변식상 반드시 있어야 한다 — 없으면 continue 로 삼키지 않고 fail-fast 로 터뜨려, 부분 집계된
                // 랭킹이 200 으로 새어 나가는 것을 막는다.
                val tItem = tItemById[tournamentItemId] ?: continue
                val snapshot = tItem.requireSnapshot(snapshotById)
                // 우승 아이템(rank==1)을 고른 참여자만 집계 — 참여자마다 같은 아이템의 rank 가 다를 수 있으므로
                // RankKey 로 묶으면 누락이 생긴다. itemId 기준으로 1위 선택자만 모은다.
                if (rank == 1) {
                    winnersByItemId.getOrPut(snapshot.itemId) { mutableListOf() }.add(participant)
                }
                // 모든 play 가 ROOT 의 tournamentItemId 를 공유하므로, 첫 번째로 처리되는 play 의 값으로 고정한다.
                // rank 는 이후 정렬 순위로 재계산되므로 여기서는 0 으로 채운다.
                referenceItemsById.putIfAbsent(
                    snapshot.itemId,
                    RankedItem(
                        rank = 0,
                        tournamentItemId = tournamentItemId,
                        itemId = snapshot.itemId,
                        name = snapshot.name,
                        price = snapshot.price,
                        currency = snapshot.currency,
                        imageUrl = snapshot.imageUrl,
                    ),
                )
            }
        }

        val items = referenceItemsById.values
            .sortedByDescending { winnersByItemId[it.itemId]?.size ?: 0 }
            .mapIndexed { idx, ref ->
                GroupResultItem(
                    rank = idx + 1,
                    itemId = ref.itemId,
                    name = ref.name,
                    price = ref.price,
                    currency = ref.currency,
                    imageUrl = ref.imageUrl,
                    chosenBy = winnersByItemId[ref.itemId] ?: emptyList(),
                )
            }
        return GroupResult(items = items)
    }

    @Transactional
    fun deleteItem(
        userId: UUID,
        tournamentId: Long,
        tournamentItemId: Long,
    ) {
        val tournament =
            tournamentRepository.findTournamentById(tournamentId)
                ?: throw TournamentException.notFoundTournament()
        // 클론은 원본 아이템을 이어받을 뿐 소유 행이 없다 — 삭제 시 원본을 건드리므로 막는다(#977, 추가 금지 032 와 같은 결).
        tournament.sourceTournamentId?.let { throw TournamentException.clonedTournamentCannotModifyItems() }
        if (!tournament.isPending()) throw TournamentException.notPendingTournament()
        val tournamentItem =
            tournamentItemRepository.findById(tournamentItemId)
                ?: throw TournamentException.notFoundTournamentItem()
        if (tournamentItem.tournamentId != tournamentId) throw TournamentException.notFoundTournamentItem()

        val isItemAdder = tournamentItem.userId == userId
        if (!isItemAdder) {
            val isTournamentOwner =
                tournamentUserRepository
                    .findByTournamentIdAndUserId(tournamentId, userId)
                    ?.getId() == tournament.ownerTournamentUserId
            if (!isTournamentOwner) throw TournamentException.forbiddenTournament()
        }

        val deleted = tournamentItemRepository.softDeleteIfPending(tournamentItemId, tournamentId)
        if (deleted == 0) throw TournamentException.notPendingTournament()

        // 삭제로 출전 목록이 바뀌었음을 다른 참가자에게 알린다(폴링 대체) — 추가(TournamentItemAdded)와 대칭.
        // tournamentItemId·snapshotId 를 함께 실어 알림 도메인이 어느 아이템인지·상품명을 끌어내게 한다
        // (tournament_item 은 방금 soft delete 돼 핸들러가 역조회로 못 닿지만, snapshot 은 살아 있다).
        eventPublisher.publishEvent(
            TournamentItemDeleted(
                tournamentId = tournamentId,
                tournamentItemId = tournamentItemId,
                snapshotId = tournamentItem.snapshotId,
                actorId = userId,
            ),
        )
    }

    private fun toItemDetail(
        tournamentItem: TournamentItem,
        snapshotById: Map<Long, ItemSnapshot>,
    ): TournamentDetail.ItemDetail {
        val snapshot = tournamentItem.requireSnapshot(snapshotById)
        return TournamentDetail.ItemDetail(
            tournamentItemId = tournamentItem.getId(),
            itemId = snapshot.itemId,
            userId = tournamentItem.userId,
            name = snapshot.name,
            price = snapshot.price,
            currency = snapshot.currency,
            imageUrl = snapshot.imageUrl,
            status = snapshot.status,
        )
    }

    // 그 라운드의 브래킷(페어 구성 · 진행 순서 · 부전승)을 파생한다(#683).
    //
    // 입력은 반드시 "라운드 시작 시점 집합" 이어야 한다 — 이미 싸운 아이템이 빠진 축소된 집합으로 매번 파생하면
    // 인원 수가 달라져 부전승 대상과 진행 순서가 흔들린다. 따라서 이전 라운드들에서 탈락한 아이템만 제외하고,
    // 현재 라운드에서 진 아이템은(라운드 시작 시점엔 살아 있었으므로) 그대로 남긴다.
    private fun deriveBracket(
        allTournamentItems: List<TournamentItem>,
        snapshotById: Map<Long, ItemSnapshot>,
        histories: List<TournamentHistory>,
        currentRound: Int,
        tournamentUserId: Long,
    ): RoundBracket {
        // 라운드 값은 남은 인원 수(16 -> 8 -> 4 -> 2)라 진행될수록 작아진다. 따라서 "이전 라운드" 는
        // currentRound 보다 "큰" 기록이다. != 로 두면 나중 라운드(더 작은 값)의 패자까지 빼서, 과거 라운드를
        // 재파생할 때(멱등 재시도가 recorded.currentRound 로 부른다) 인원이 줄어든 브래킷이 나온다.
        val eliminatedBeforeRound = histories
            .filter { it.currentRound > currentRound }
            .map { it.loser() }
            .toSet()
        val entries = allTournamentItems
            .filterNot { it.getId() in eliminatedBeforeRound }
            .map { RoundBracket.Entry(it.getId(), it.requireSnapshot(snapshotById).price) }
        return RoundBracket.of(entries, tournamentUserId, currentRound)
    }

    // 그 라운드에서 아직 안 치른 첫 매치. 라운드가 다 끝났으면 null.
    private fun nextMatchOf(
        allTournamentItems: List<TournamentItem>,
        snapshotById: Map<Long, ItemSnapshot>,
        histories: List<TournamentHistory>,
        round: Int,
        tournamentUserId: Long,
    ): TournamentDetail.MatchDetail? =
        deriveBracket(allTournamentItems, snapshotById, histories, round, tournamentUserId)
            .firstUnplayed(playedPairsIn(histories, round))
            ?.let { toMatchDetail(it, allTournamentItems, snapshotById) }

    // 그 라운드에서 이미 치른 매치들. 진행 순서는 검증하지 않으므로 "치렀는지" 만 본다.
    private fun playedPairsIn(
        histories: List<TournamentHistory>,
        round: Int,
    ): List<RoundBracket.MatchPair> = histories
        .filter { it.currentRound == round }
        .map { RoundBracket.MatchPair(it.firstTournamentItemId, it.secondTournamentItemId) }

    private fun toMatchDetail(
        pair: RoundBracket.MatchPair,
        tournamentItems: List<TournamentItem>,
        snapshotById: Map<Long, ItemSnapshot>,
    ): TournamentDetail.MatchDetail {
        val itemById = tournamentItems.associateBy { it.getId() }
        // 페어는 방금 이 아이템 목록에서 파생됐으므로 조회가 빌 수 없다 — 비면 파생 입력이 어긋난 코드 버그다.
        fun detailOf(tournamentItemId: Long) =
            toItemDetail(
                itemById[tournamentItemId] ?: error("브래킷 페어 아이템 없음 — tournamentItemId=$tournamentItemId"),
                snapshotById,
            )
        return TournamentDetail.MatchDetail(first = detailOf(pair.first), second = detailOf(pair.second))
    }

    // CLONE 토너먼트는 DB 에 아이템 행이 없고, ROOT 의 아이템을 sourceTournamentId 로 공유한다.
    private fun getEffectiveTournamentItems(tournament: Tournament): List<TournamentItem> =
        tournamentItemRepository.findAllByTournamentId(tournament.sourceTournamentId ?: tournament.getId())

    // tournament_item 들이 고정한 snapshot 을 한 번에 조회해 id→snapshot 맵으로. 표시값 조회의 메모리 조인 재료다.
    private fun snapshotsOf(tournamentItems: Collection<TournamentItem>): Map<Long, ItemSnapshot> =
        itemSnapshotRepository
            .findByIds(tournamentItems.map { it.snapshotId })
            .associateBy { it.getId() }

    // 대기실(PENDING) 표시용(#857) — 키는 포인터 snapshot id 를 유지하되 값을 파생 표시 버전으로 치환한다.
    // requireSnapshot(포인터 id 조회)을 쓰는 기존 조립 코드가 무수정으로 표시 버전을 읽게 된다.
    private fun displayedSnapshotsOf(tournamentItems: Collection<TournamentItem>): Map<Long, ItemSnapshot> {
        val pointers = snapshotsOf(tournamentItems)
        val displayById = itemDisplayService.resolveDisplay(pointers.values)
        return pointers.mapValues { (pointerId, pointer) -> displayById[pointerId] ?: pointer }
    }

    // 고정 snapshot 은 출전 시점에 반드시 박힌다. 없으면 영속화 경로가 깨진 코드 버그다(전환 후 신규 출전부터 보장).
    private fun TournamentItem.requireSnapshot(snapshotById: Map<Long, ItemSnapshot>): ItemSnapshot =
        snapshotById[snapshotId]
            ?: error("snapshot 없음 — tournamentItemId=${getId()}, snapshotId=$snapshotId")

    private fun computeRanking(histories: List<TournamentHistory>): List<Pair<Long, Int>> {
        val finalMatch = histories.find { it.currentRound == Tournament.FINAL_ROUND_SIZE }
            ?: error("결승 기록 없음 — tournamentId=${histories.firstOrNull()?.tournamentId}, tournamentUserId=${histories.firstOrNull()?.tournamentUserId}")
        val semiRound = histories
            .filter { it.currentRound > Tournament.FINAL_ROUND_SIZE }
            .minByOrNull { it.currentRound }?.currentRound
        val semiLosers = semiRound
            ?.let { round ->
                histories
                    .filter { it.currentRound == round }
                    .map { it.loser() }
                    .sorted()
            }
            ?: emptyList()
        return buildList {
            add(finalMatch.selectedTournamentItemId to 1)
            add(finalMatch.loser() to 2)
            semiLosers.forEachIndexed { i, id -> add(id to 3 + i) }
        }
    }

    private fun TournamentHistory.loser(): Long =
        when (selectedTournamentItemId) {
            firstTournamentItemId -> secondTournamentItemId
            secondTournamentItemId -> firstTournamentItemId
            else -> error(
                "잘못된 tournament history: selectedTournamentItemId=$selectedTournamentItemId, " +
                    "firstTournamentItemId=$firstTournamentItemId, secondTournamentItemId=$secondTournamentItemId, " +
                    "tournamentId=$tournamentId",
            )
        }

    // 완료된 라운드 수를 기반으로 다음 진행해야 할 라운드를 계산한다.
    // currentPlayers = 해당 라운드 시작 시 남은 플레이어 수 = currentRound 값과 동일.
    // 매치 수는 RoundBracket 이 소유한다(2의 거듭제곱 정규화) — 브래킷 파생과 라운드 수학이 같은 공식을 써야
    // "서버가 지정한 currentMatch" 와 "서버가 기대하는 라운드" 가 어긋나지 않는다.
    // 다음 라운드 인원 = currentPlayers - matchesExpected: 승자 수(=매치 수) + 부전승 수와 같다.
    private fun computeExpectedRound(
        startRound: Int,
        histories: List<TournamentHistory>,
    ): Int {
        val countByRound = histories
            .groupingBy { it.currentRound }
            .eachCount()
        var currentPlayers = startRound
        while (currentPlayers >= Tournament.FINAL_ROUND_SIZE) {
            val matchesExpected = RoundBracket.matchCountOf(currentPlayers)
            val played = countByRound[currentPlayers] ?: 0
            if (played < matchesExpected) return currentPlayers
            // 결승(2명)까지 다 치렀으면 더 내려갈 라운드가 없다.
            if (currentPlayers == Tournament.FINAL_ROUND_SIZE) break
            currentPlayers -= matchesExpected
        }
        // 모든 라운드가 완료됐는데 isInProgress() 인 상태 — tournament.complete() 누락 버그
        error("모든 라운드가 완료됐는데 IN_PROGRESS 상태임 tournamentId=${histories.firstOrNull()?.tournamentId}")
    }

    // invite_code 는 랜덤 생성이라 충돌 가능성이 낮지만 0이 아니다. 활성 코드 중복을 사전 확인하고
    // 충돌 시 재시도한다. DB 레벨 unique constraint(uk_tournaments_active_invite_code)가 최후 보루.
    private fun generateUniqueInviteCode(): String {
        repeat(INVITE_CODE_MAX_ATTEMPTS) {
            val code = Tournament.generateInviteCode()
            if (!tournamentRepository.existsTournamentByInviteCode(code)) return code
        }
        error("invite_code $INVITE_CODE_MAX_ATTEMPTS 회 생성 실패 — DB 포화 또는 keyspace 고갈 가능성")
    }
}

// 두 서비스(TournamentService.join, TournamentSocialPersistenceService.createGuestAndJoin)가
// 공유하는 초대 참여 검증. 링크 접근은 inviteCode=null, 코드 입력 경로는 inviteCode 포함.
internal fun Tournament.checkJoinable(inviteCode: String?) {
    if (!isPending()) throw TournamentException.notPendingTournament()
    if (!isInviteValid()) throw TournamentException.inviteExpired()
    inviteCode?.let { if (this.inviteCode != it) throw TournamentException.invalidInviteCode() }
}
