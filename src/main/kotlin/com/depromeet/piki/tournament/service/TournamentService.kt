package com.depromeet.piki.tournament.service

import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.domain.ItemStatus
import com.depromeet.piki.item.repository.ItemRepository
import com.depromeet.piki.item.repository.ItemSnapshotRepository
import com.depromeet.piki.item.service.DisplayCard
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
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.user.domain.UserException
import com.depromeet.piki.user.repository.UserRepository
import com.depromeet.piki.user.service.DefaultProfileImages
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
    private val defaultProfileImages: DefaultProfileImages,
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
        val inviteExpiresAt =
            LocalDateTime
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
                // 토너먼트 닉네임은 생성 시점 프로필 닉네임으로 채운다(#1018) — 이후 프로필 수정과 분리.
                TournamentUser(tournament.getId(), userId, nicknameOf(userId)),
            )
        tournament.assignOwner(tournamentUser.getId())
        return CreateTournamentResult(
            tournamentId = tournament.getId(),
            inviteCode = inviteCode,
            inviteExpiresAt = inviteExpiresAt,
        )
    }

    // 토너먼트 닉네임 fill 용 — 참여 시점의 프로필 닉네임을 스냅샷한다(#1018). 유저가 없으면(이례) null → 표시 시 폴백.
    private fun nicknameOf(userId: UUID): String? = userRepository.findById(userId)?.nickname

    // 리다이렉트 shim(#1027 Phase 3) — 클론 id 로 온 요청을 ROOT 로 해소한다. Phase 2 백필로 모든 플레이가 ROOT
    // 참여 행으로 평탄화됐고, 클론 행은 Phase 4 제거 전까지 리다이렉트 껍데기로만 남는다. 클라이언트가 공유·이전
    // 세션 URL 로 클론 id 를 보내도 ROOT 로 이어지게 한다. sourceTournamentId 가 없으면(ROOT) 자기 자신이다.
    private fun rootOf(tournament: Tournament): Tournament =
        tournament.sourceTournamentId
            ?.let { tournamentRepository.findTournamentById(it) ?: throw TournamentException.notFoundTournament() }
            ?: tournament

    // rootOf 의 for-update 판(상태 전이 경로용). ROOT 행에 락을 잡는다.
    private fun rootForUpdate(tournament: Tournament): Tournament =
        tournament.sourceTournamentId
            ?.let {
                tournamentRepository.findTournamentByIdForUpdate(it)
                    ?: throw TournamentException.notFoundTournament()
            }
            ?: tournament

    // 이 토너먼트에서 쓸 참여 닉네임만 바꾼다(#1018) — 유저 프로필(users.nickname)은 건드리지 않는다.
    // 요청자가 참여한 토너먼트여야 한다(그 tournamentId 의 TU 소유자). 아니면 접근 불가(403).
    // (게스트/멤버 공통: 각자 자기 TU — 멤버는 루트 TU, 플레이링크 게스트는 자기 클론 TU — 를 tournamentId 로 가리켜 부른다.)
    @Transactional
    fun updateNickname(
        userId: UUID,
        tournamentId: Long,
        nickname: String,
    ) {
        // 클론 id 로 와도 ROOT 로 해소한다(#1027) — 백필 후 클론 shell 엔 참여 행이 없어, 옛 클론 URL 로 닉네임을
        // 바꾸면 ROOT 참여자여도 못 찾는다. 존재하는 토너먼트의 sourceTournamentId 로만 해소해 미존재는 기존대로
        // forbidden(403) 을 유지한다(신규 404 계약을 만들지 않음).
        val effectiveTournamentId =
            tournamentRepository.findTournamentById(tournamentId)?.sourceTournamentId ?: tournamentId
        val tournamentUser =
            tournamentUserRepository.findByTournamentIdAndUserId(effectiveTournamentId, userId)
                ?: throw TournamentException.forbiddenTournament()
        ensureNicknameAvailable(nickname, userId)
        tournamentUser.rename(nickname)
        tournamentUserRepository.save(tournamentUser)
    }

    // 참여 닉네임은 "모든 표시명 전역 유일"(#1018) — 프로필 닉 풀(users)과 참여 닉 풀(tournament_users) 어느 쪽과도
    // 겹치면 안 된다. 자기 자신(자기 프로필·자기 다른 참여 닉)은 제외해 프리필·재설정이 자연스럽게 통과한다.
    // 교차 테이블 UNIQUE 는 MySQL 로 못 걸어 앱 레이어 검사다(프로필 닉과 같은 방식, 좁은 race 창 감수).
    private fun ensureNicknameAvailable(
        nickname: String,
        requesterId: UUID,
    ) {
        if (userRepository.existsByNicknameAndIdNot(nickname, requesterId)) throw UserException.duplicateNickname()
        if (tournamentUserRepository.existsByNicknameExcludingUser(
                nickname,
                requesterId,
            )
        ) {
            throw UserException.duplicateNickname()
        }
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
        tournamentUserRepository.save(TournamentUser(tournamentId, userId, nicknameOf(userId)))
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
        val foundItemIds =
            foundItems
                .map { it.getId() }
                .toSet()
        if (command.itemIds.any { it !in foundItemIds }) throw TournamentException.notFoundItems()
        // 출전 시점에 위시가 기다리는 행을 tournament_item 에 고정한다 — 이후 위시 갱신과 무관하게 그 버전을 본다.
        // 키는 위시의 상품이다(행의 상품이 아니라) — 두 참조가 어긋난 드문 행이 있어도 키 공간이 하나라 아래 커버리지 검사가 계약(409)으로 거른다.
        val wishes = wishRepository.findByItemIdsAndUserId(command.itemIds, userId)
        val snapshotById = itemSnapshotRepository.findByIds(wishes.map { it.waitingSnapshotId }).associateBy { it.getId() }
        val activeSnapshotByItemId =
            wishes.mapNotNull { wish -> snapshotById[wish.waitingSnapshotId]?.let { wish.itemId to it } }.toMap()
        if (!activeSnapshotByItemId.keys.containsAll(command.itemIds)) throw TournamentException.itemNotReady()
        // 판정은 포인터가 아니라 카드에 뜨는 값으로 한다 — 남이 같은 링크를 담아 추출이 성공하면 내 포인터가
        // 미완성·실패로 남아 있어도 카드엔 그 성공값이 뜬다(displayOf). 포인터로 판정하면 화면엔 값이 다 있는데
        // "채운 뒤 담아 주세요" 가 나가고, 같은 아이템으로 시작은 되는 어긋남이 생긴다.
        // 박제는 포인터 그대로다 — 겨루는 값 확정은 start 의 몫이고(#858), 대기실 표시도 파생으로 움직인다.
        requireEntryEligible(
            itemDisplayService.resolveDisplay(activeSnapshotByItemId.values.map { DisplayCard.waitingOn(it, owner = userId) }).values,
            TournamentException::itemIncomplete,
            TournamentException::itemNotReady,
        )
        val savedItemIds =
            tournamentItemRepository
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
                ).map { it.getId() }
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
        // 다른 상태 전이 메서드(join·recordMatch 등)와 동일한 forUpdate 패턴. 클론 id 로 와도 ROOT 로 해소해 락을 잡는다.
        val requested =
            tournamentRepository.findTournamentByIdForUpdate(tournamentId)
                ?: throw TournamentException.notFoundTournament()
        val tournament = rootForUpdate(requested)
        val callerTU =
            tournamentUserRepository.findByTournamentIdAndUserId(tournament.getId(), userId)
                ?: throw TournamentException.forbiddenTournament()
        return if (callerTU.getId() == tournament.ownerTournamentUserId) {
            startAsOwner(tournament, callerTU, userId, tournament.getId())
        } else {
            startAsMember(tournament, callerTU)
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
        val cardOf = { tournamentItem: TournamentItem -> DisplayCard.waitingOn(tournamentItem.requireSnapshot(snapshotById), owner = tournamentItem.userId) }
        val displayByCard = itemDisplayService.resolveDisplay(tournamentItems.map(cardOf))
        val pinnedByTournamentItemId =
            tournamentItems.associate { tournamentItem ->
                val display = displayByCard.getValue(cardOf(tournamentItem))
                if (display.getId() != tournamentItem.snapshotId) tournamentItem.repinSnapshot(display.getId())
                tournamentItem.getId() to display
            }
        // item 정체성은 snapshot.itemId 단일 출처다 — 고정 snapshot 에서 itemId 를 모아 item 존재를 검증한다.
        val itemById =
            itemRepository
                .findByIds(pinnedByTournamentItemId.values.map { it.itemId })
                .associate { it.getId() to it }
        if (pinnedByTournamentItemId.values.any { it.itemId !in itemById }) throw TournamentException.notFoundItems()
        requireEntryEligible(
            pinnedByTournamentItemId.values,
            TournamentException::itemIncompleteToStart,
            TournamentException::itemNotReadyToStart,
        )
        for (tournamentItem in tournamentItems) {
            pinnedByTournamentItemId.getValue(tournamentItem.getId()).price
                ?: throw TournamentException.itemPriceRequired()
        }
        tournament.start()
        // #1027: 정의 상태(tournament.start)와 함께 주최자 참여 행도 플레이 시작으로 전이한다 — 주최자는 시작 즉시
        // 자기 판을 진행하므로 참여 진행이 곧 IN_PROGRESS 다. 이후 읽기는 이 참여 status 로 화면을 분기한다.
        owner.startPlaying()
        // 시작이 커밋된 뒤에만 참가자에게 전달되도록 트랜잭션 안에서 발행한다 (롤백 시 미발행).
        eventPublisher.publishEvent(TournamentStarted(tournamentId = tournamentId, actorId = userId))
        return StartResult(
            tournamentId = tournamentId,
            items =
                tournamentItems
                    .map { item ->
                        val snapshot = pinnedByTournamentItemId.getValue(item.getId())
                        TournamentStartResult(
                            tournamentItemId = item.getId(),
                            name = snapshot.name,
                            price = snapshot.price,
                            currency = snapshot.currency,
                            imageUrl = snapshot.imageUrl,
                        )
                    }.sortedWith(compareBy({ it.price }, { it.tournamentItemId })),
        )
    }

    // #1027: 멤버·게스트의 "시작" 은 클론 생성이 아니라 자기 ROOT 참여 행을 플레이 시작으로 전이하는 것이다.
    // 클론이 사라져 한 사람의 진행은 참여 행 status 하나가 온전히 표현한다. 반환 id 는 항상 ROOT 다.
    private fun startAsMember(
        root: Tournament,
        callerTU: com.depromeet.piki.tournament.domain.TournamentUser,
    ): StartResult {
        // 주최자가 구성을 잠그고 시작한(PENDING 아님) 뒤에만 멤버가 자기 플레이를 시작할 수 있다.
        if (root.isPending()) throw TournamentException.notInProgressTournament()

        val effectiveItems = getEffectiveTournamentItems(root)
        require(effectiveItems.isNotEmpty()) { "ROOT 토너먼트에 아이템 없음 — tournamentId=${root.getId()}" }

        // 아직 시작 전(PENDING)이면 플레이 시작으로 전이한다. 이미 시작·완료했으면(재호출) 그대로 두고 아이템만
        // 돌려준다 — start 를 멱등하게 만들어 재탭·재진입이 500(check 위반)이 아니라 같은 판을 다시 받게 한다.
        if (callerTU.status == TournamentStatus.PENDING) {
            callerTU.startPlaying()
            tournamentUserRepository.save(callerTU)
        }

        val snapshotById = snapshotsOf(effectiveItems)
        return StartResult(
            tournamentId = root.getId(),
            items =
                effectiveItems
                    .map { item ->
                        val snapshot = item.requireSnapshot(snapshotById)
                        TournamentStartResult(
                            tournamentItemId = item.getId(),
                            name = snapshot.name,
                            price = snapshot.price,
                            currency = snapshot.currency,
                            imageUrl = snapshot.imageUrl,
                        )
                    }.sortedWith(compareBy({ it.price }, { it.tournamentItemId })),
        )
    }

    @Transactional(readOnly = true)
    fun getTournamentById(
        tournamentId: Long,
        userId: UUID,
    ): TournamentDetail {
        val requested =
            tournamentRepository.findTournamentById(tournamentId)
                ?: throw TournamentException.notFoundTournament()
        // 클론 id 로 와도 ROOT 로 해소한다(#1027) — 모든 참여·플레이가 ROOT 참여 행에 있으므로 요청자 조회도 ROOT 기준.
        val tournament = rootOf(requested)
        val currentUser =
            tournamentUserRepository.findByTournamentIdAndUserId(tournament.getId(), userId)
                ?: throw TournamentException.forbiddenTournament()
        val isOwner = currentUser.getId() == tournament.ownerTournamentUserId

        // #1027: 화면 분기는 전역 tournament.status 가 아니라 요청자의 참여 행 status 로 한다.
        //  PENDING     — 아직 시작 전. tournament 가 PENDING(구성중)이면 일반 대기실, IN_PROGRESS(주최자 시작함)면
        //                ownerStarted 로 "지금 시작하세요" 를 분기한다.
        //  IN_PROGRESS — 본인 판 진행 중. 본인 히스토리로 브래킷을 파생한다.
        //  COMPLETED   — 본인 판 완료. 순위·그룹 결과.
        return when (currentUser.status) {
            TournamentStatus.PENDING -> buildPending(tournament, isOwner, ownerStarted = !tournament.isPending(), viewerId = userId)
            TournamentStatus.IN_PROGRESS -> buildInProgress(tournament, currentUser, isOwner)
            TournamentStatus.COMPLETED -> {
                val histories =
                    tournamentRepository.findHistoriesByTournamentIdAndTournamentUserId(
                        tournament.getId(),
                        currentUser.getId(),
                    )
                buildCompleted(
                    tournament,
                    histories,
                    computeGroupFlags(tournament),
                    isOwner,
                    canAddItemForTournament(tournament, userId),
                )
            }
        }
    }

    // 대기실/시작대기 응답(#1027). ownerStarted=false 면 일반 대기실(구성중), true 면 주최자가 이미 시작해
    // 이 참여자만 아직 자기 플레이를 시작하지 않은 상태 — 클라가 "지금 시작하세요" UI 를 분기한다.
    // 대기실은 표시값 파생(#857) — 최신 기계 READY 우선, 수기는 자기 맥락에서만. 시작되면 start 가 파생 결과를 박제한다.
    private fun buildPending(
        tournament: Tournament,
        isOwner: Boolean,
        ownerStarted: Boolean,
        viewerId: UUID,
    ): TournamentDetail.Pending {
        val tournamentItems = getEffectiveTournamentItems(tournament)
        val snapshotById = displayedSnapshotsOf(tournamentItems)
        val tournamentUsers = tournamentUserRepository.findByTournamentId(tournament.getId())
        val itemCountByUserId = tournamentItems.groupingBy { it.userId }.eachCount()
        return TournamentDetail.Pending(
            tournamentId = tournament.getId(),
            name = tournament.name,
            inviteCode = tournament.inviteCode,
            inviteExpiresAt = tournament.inviteExpiresAt,
            items = tournamentItems.map { toItemDetail(it, snapshotById) },
            participants = toParticipantDetails(
                tournamentUsers,
                tournament.ownerTournamentUserId,
                viewerId,
                itemCountByUserId,
            ),
            isOwner = isOwner,
            isRoot = true,
            sourceTournamentId = null,
            ownerStarted = ownerStarted,
        )
    }

    // 참가자 목록 조립 — 주최자 배지(isHost)와 노출 순서를 한 자리에서 책임진다(#1062).
    //
    // 순서는 본인 → 주최자 → 그 외 참여자(입장 순)로 서버가 확정해 내린다. 클라가 정렬하면 화면마다 규칙이 갈리고,
    // "본인" 판정에 필요한 요청자 신원이 응답에는 없어 클라가 userId 를 비교해야 한다.
    // 내가 주최자면 두 조건을 모두 만족해 자연히 맨 앞이다.
    // 입장 순은 TU 의 auto-increment id — 참여 시각 컬럼이 따로 없고, 행 생성 순서가 곧 입장 순서다.
    private fun toParticipantDetails(
        tournamentUsers: List<TournamentUser>,
        ownerTournamentUserId: Long,
        viewerId: UUID,
        itemCountByUserId: Map<UUID, Int>,
    ): List<TournamentDetail.ParticipantDetail> {
        val userById = userRepository
            .findByIds(tournamentUsers.map { it.userId }.toSet())
            .associateBy { it.id }
        return tournamentUsers
            .sortedWith(
                compareBy(
                    { it.userId != viewerId },
                    { it.getId() != ownerTournamentUserId },
                    { it.getId() },
                ),
            ).map { tu ->
                // users 행이 없어도(인증됐으나 users 행 부재 — rejectIfDeleted 가 허용) 참여 행은 응답에 남긴다(#1027).
                // mapNotNull 로 떨구면 참가자 수(DB 카운트, #1062)와 목록이 어긋나고 본인도 대기실에서 사라진다.
                // 행이 없으면 알 수 없는 유저로 간주 — 마스킹 프사 + isWithdrawn=true 로 FE 가 "유저 알수없음" 을 렌더한다.
                val user = userById[tu.userId]
                TournamentDetail.ParticipantDetail(
                    userId = tu.userId,
                    // 토너먼트 닉네임 우선, 레거시(NULL)면 프로필 닉네임, 그것도 없으면 알 수 없음(#1018)
                    nickname = tu.nickname ?: user?.nickname ?: UNKNOWN_PARTICIPANT_NICKNAME,
                    profileImage = user?.profileImage ?: defaultProfileImages.masked(),
                    isWithdrawn = user?.isActive()?.not() ?: true,
                    isHost = tu.getId() == ownerTournamentUserId,
                    itemCount = itemCountByUserId[tu.userId] ?: 0,
                )
            }
    }

    // 진행 중 화면(#1027) — 요청자 본인 히스토리로 현재 라운드·브래킷·남은 아이템을 파생한다.
    private fun buildInProgress(
        tournament: Tournament,
        currentUser: com.depromeet.piki.tournament.domain.TournamentUser,
        isOwner: Boolean,
    ): TournamentDetail.InProgress {
        // 본인 history만 사용 — 다른 참여자의 매치는 본인 진행 상태에 영향을 주지 않는다.
        val histories =
            tournamentRepository.findHistoriesByTournamentIdAndTournamentUserId(
                tournament.getId(),
                currentUser.getId(),
            )
        // 히스토리는 currentRound ASC, id ASC 정렬이라 lastOrNull()은 라운드가 바뀌면 틀림 — ID 최대값이 가장 최근 매치
        val lastHistory =
            histories
                .maxByOrNull { it.getId() }
                ?.let { TournamentDetail.HistoryEntry.from(it) }
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
        val remainingItems =
            allTournamentItems
                .filter { item -> item.getId() !in eliminatedItemIds && item.getId() !in foughtInCurrentRoundIds }
                .map { toItemDetail(it, snapshotById) }
                .sortedWith(compareBy({ it.price }, { it.tournamentItemId }))
        val bracket = deriveBracket(allTournamentItems, snapshotById, histories, currentRound, currentUser.getId())
        return TournamentDetail.InProgress(
            tournamentId = tournament.getId(),
            name = tournament.name,
            currentRound = currentRound,
            lastHistory = lastHistory,
            remainingItems = remainingItems,
            currentMatch =
                bracket
                    .firstUnplayed(playedPairsIn(histories, currentRound))
                    ?.let { toMatchDetail(it, allTournamentItems, snapshotById) },
            isOwner = isOwner,
            isRoot = true,
            sourceTournamentId = null,
        )
    }

    @Transactional(readOnly = true)
    fun getTournamentItem(
        userId: UUID,
        tournamentId: Long,
        tournamentItemId: Long,
    ): TournamentItemDetail {
        val requested =
            tournamentRepository.findTournamentById(tournamentId)
                ?: throw TournamentException.notFoundTournament()
        // 클론 id 로 와도 ROOT 로 해소한다(#1027). 아이템 행은 항상 ROOT 소속이라 ROOT id 로 스코프 검사한다(#977).
        val tournament = rootOf(requested)
        tournamentUserRepository.findByTournamentIdAndUserId(tournament.getId(), userId)
            ?: throw TournamentException.forbiddenTournament()
        val tournamentItem =
            tournamentItemRepository.findById(tournamentItemId)
                ?: throw TournamentException.notFoundTournamentItem()
        if (tournamentItem.tournamentId != tournament.getId()) throw TournamentException.notFoundTournamentItem()
        // 표시값: 대기실(PENDING)은 파생(#857), 시작 후는 start 가 박제한 포인터 그대로(겨룬 값 고정).
        // sourceUrl(상품 링크)은 그 snapshot 의 item(정체성)에서 읽는다.
        val pointer = tournamentItem.requireSnapshot(snapshotsOf(listOf(tournamentItem)))
        val snapshot =
            if (tournament.isPending()) {
                itemDisplayService.resolveDisplay(DisplayCard.waitingOn(pointer, owner = tournamentItem.userId))
            } else {
                pointer
            }
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
        // 참가자·썸네일은 남은 토너먼트에 대해서만 읽는다 (홈 카드 limit=3 이 내 전체 이력을 선로드하지 않게).
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

        // 썸네일도 남은 토너먼트에 대해서만 조회한다 (잘릴 것의 아이템은 안 읽음). #1027: 클론이 사라져 모든 가시
        // 토너먼트가 자기 tournament_item 을 가진 ROOT 이므로 자기 id 로 바로 조회한다.
        val thumbnailsByTournamentId = thumbnailUrlsByTournamentId(limited.map { it.getId() })

        // "함께 담은 N"(#1062) — 그 토너먼트의 참여자 수. 참여자 프로필을 겹쳐 보여주던 자리를 숫자로 바꾼 것이라
        // 모집단도 그대로 참여자다. 클론이 사라져(#1027) 카드가 곧 그 토너먼트라, ROOT 로 되짚을 필요 없이 자기 행만 센다.
        val participantCountByTournamentId = tournamentUsers.groupingBy { it.tournamentId }.eachCount()
        // "플레이한 N"(#1062) — 플레이를 끝까지 마친 사람 수. 시작만 하고 이탈한 사람은 빠진다.
        // 영수증(그룹 결과)과 같은 기준으로 센다: completedAt 기준이고 deletedAt 무관이라, 완주한 뒤 방을 삭제한
        // 주최자도 포함된다. 위 tournamentUsers(활성 행)로 세면 그 주최자가 빠져 카드와 영수증 인원이 어긋난다.
        // 클론이 사라져 한 사람의 완주가 참여 행 하나에 담이므로 dedup 은 방어적으로만 둔다.
        val playedCountByTournamentId = tournamentUserRepository
            .findCompletedByTournamentIds(limited.map { it.getId() })
            .distinctBy { it.tournamentId to it.userId }
            .groupingBy { it.tournamentId }
            .eachCount()

        // per-user effective status(#1027) = 내 참여 행 status. 주최자·멤버·게스트가 같은 방을 각자 진행 상태로 본다 —
        // 방장이 완료해도 아직 안 끝낸 멤버에겐 IN_PROGRESS, 내가 완료했으면 COMPLETED. 쿼리의 tu.status 필터와 동일 기준.
        val myStatusByTournamentId =
            tournamentUsers
                .filter { it.userId == userId }
                .associate { it.tournamentId to it.status }

        return limited.map { tournament ->
            val effectiveStatus = myStatusByTournamentId[tournament.getId()] ?: tournament.status
            TournamentSummary.of(
                tournament = tournament,
                participantProfileImages = profileImagesByTournamentId[tournament.getId()] ?: emptyList(),
                participantCount = participantCountByTournamentId[tournament.getId()] ?: 0,
                playedCount = playedCountByTournamentId[tournament.getId()] ?: 0,
                thumbnailUrls = thumbnailsByTournamentId[tournament.getId()] ?: emptyList(),
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
                .associate { snapshot ->
                    snapshot.getId() to
                        snapshot.imageUrl?.takeIf { snapshot.status == ItemStatus.READY }
                }
        return items
            .groupBy { it.tournamentId }
            .mapValues { (_, tournamentItems) ->
                TournamentThumbnails.select(
                    tournamentItems.map {
                        TournamentThumbnails.Candidate(
                            recency = it.getId(),
                            imageUrl = readyImageUrlBySnapshotId[it.snapshotId],
                        )
                    },
                )
            }
    }

    @Transactional
    fun recordMatch(
        userId: UUID,
        command: RecordMatch,
    ): RecordMatchResult {
        val requested =
            tournamentRepository.findTournamentByIdForUpdate(command.tournamentId)
                ?: throw TournamentException.notFoundTournament()
        // 클론 id 로 와도 ROOT 로 해소한다(#1027) — 모든 참여·플레이·히스토리가 ROOT 기준이다.
        val tournament = rootForUpdate(requested)
        val rootId = tournament.getId()
        // 진행 중 검사는 "새 매치를 기록해도 되나" 를 묻는 것이라 멱등 판정 뒤로 미룬다 —
        // 결승을 기록하면 그 사람의 참여 행이 즉시 COMPLETED 로 바뀌므로, 여기서 먼저 막으면
        // 가장 흔한 재시도(결승 응답을 못 받고 재전송)만 멱등에서 빠진다.
        // #1027: 멤버·게스트도 자기 ROOT 참여 행으로 플레이하므로 "오너만" 가드는 사라졌다.
        val tournamentUser =
            tournamentUserRepository.findByTournamentIdAndUserId(rootId, userId)
                ?: throw TournamentException.forbiddenTournament()
        if (command.selectedTournamentItemId != command.firstTournamentItemId &&
            command.selectedTournamentItemId != command.secondTournamentItemId
        ) {
            throw TournamentException.invalidWinner()
        }

        val allTournamentItems = getEffectiveTournamentItems(tournament)
        val tournamentItemIds = allTournamentItems.map { it.getId() }.toSet()
        if (command.firstTournamentItemId !in tournamentItemIds ||
            command.secondTournamentItemId !in tournamentItemIds
        ) {
            throw TournamentException.invalidTournamentItem()
        }

        // 본인 history만 사용 — 다른 참여자의 매치는 본인 진행에 영향을 주지 않는다.
        val histories =
            tournamentRepository.findHistoriesByTournamentIdAndTournamentUserId(
                rootId,
                tournamentUser.getId(),
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
                // 결승을 재전송한 경우 본인 참여 행은 이미 COMPLETED 다(#1027) — 최초 응답과 같은 순위 결과를
                // 재구성해 돌려준다. 그러지 않으면 클라이언트가 최종 순위를 못 받고, 방금 선택을 마친 사용자에게
                // "토너먼트가 진행 중일 때만 할 수 있어요" 가 뜬다.
                if (tournamentUser.isCompleted()) {
                    return RecordMatchResult(
                        nextMatch = null,
                        completed =
                            buildCompleted(
                                tournament,
                                histories,
                                computeGroupFlags(tournament),
                                tournamentUser.getId() == tournament.ownerTournamentUserId,
                                canAddItemForTournament(tournament, userId),
                            ),
                    )
                }
                // 그 매치가 속한 라운드로 다음 매치를 다시 파생한다. 라운드가 이미 끝났으면 null 이 나오고,
                // 클라이언트는 현행대로 GET 을 다시 불러 다음 라운드를 받는다.
                return RecordMatchResult(
                    nextMatch =
                        nextMatchOf(
                            allTournamentItems,
                            snapshotById,
                            histories,
                            recorded.currentRound,
                            tournamentUser.getId(),
                        ),
                    completed = null,
                )
            }

        // 재시도가 아닌 새 매치 기록이므로 여기서부터는 본인 참여 행이 진행 중이어야 한다(#1027).
        // PENDING(아직 시작 안 함)·COMPLETED(이미 완료) 모두 여기서 걸린다.
        if (!tournamentUser.isPlaying()) throw TournamentException.notInProgressTournament()

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

        val newHistory =
            TournamentHistory(
                tournamentId = rootId,
                tournamentUserId = tournamentUser.getId(),
                currentRound = command.currentRound,
                firstTournamentItemId = command.firstTournamentItemId,
                secondTournamentItemId = command.secondTournamentItemId,
                selectedTournamentItemId = command.selectedTournamentItemId,
            )
        tournamentRepository.saveHistory(newHistory)

        if (!tournament.isFinalRound(command.currentRound)) {
            return RecordMatchResult(
                nextMatch =
                    bracket
                        .firstUnplayed(playedPairsIn(histories + newHistory, command.currentRound))
                        ?.let { toMatchDetail(it, allTournamentItems, snapshotById) },
                completed = null,
            )
        }

        // #1027: 최종 라운드 완료는 참여 행에만 기록한다 — 정의 상태(tournament.status)는 그대로 두어 다른
        // 참여자가 계속 자기 판을 진행할 수 있다(과거엔 클론이 완료되며 tournament.complete 했다).
        tournamentUser.complete()
        tournamentUserRepository.save(tournamentUser)
        val isOwner = tournamentUser.getId() == tournament.ownerTournamentUserId

        // 완료 알림 발행(#473). 주최자 본인 완료 → 참여자에게 "결과 나왔어요"(ResultReady),
        // 멤버·게스트 완료 → ROOT 주최자에게 "완료했어요"(Completed). 이벤트는 항상 ROOT id 를 싣는다.
        if (isOwner) {
            eventPublisher.publishEvent(TournamentResultReady(rootTournamentId = rootId, actorId = userId))
        } else {
            eventPublisher.publishEvent(TournamentCompleted(rootTournamentId = rootId, actorId = userId))
        }

        return RecordMatchResult(
            nextMatch = null,
            completed =
                buildCompleted(
                    tournament,
                    histories + newHistory,
                    computeGroupFlags(tournament),
                    isOwner,
                    canAddItemForTournament(tournament, userId),
                ),
        )
    }

    private data class GroupFlags(
        val hasGroupResult: Boolean,
        val isGroupTournament: Boolean,
    )

    // 그룹 결과 관련 두 플래그를 한 번에 구한다(#1027). 클론이 사라져 참여자·완료자를 ROOT 참여 행에서 바로 센다.
    //   hasGroupResult    : 완료한 고유 사용자 수 >= 2 → 그룹 결과 "조회 가능"(progressive gate, core#456).
    //   isGroupTournament : 참여한 고유 사용자 수 >= 2 → "소셜(그룹) 토너먼트 여부"(완료 무관, core#370 원래 정의).
    // 배너 "노출"은 isGroupTournament 로, "활성/비활성"은 hasGroupResult 로 가른다 — 첫 완주자가 누구든 새로고침 없이
    // 배너를 본다(#975). 완료자는 completedAt 기준(deletedAt 무관)이라, 자기 판을 완료한 뒤 방을 삭제한 주최자도 반영된다.
    // 호출부가 이미 ROOT 로 해소한 tournament 를 넘기므로 getId() 가 곧 rootId 다.
    private fun computeGroupFlags(tournament: Tournament): GroupFlags {
        val rootId = tournament.getId()
        val active = tournamentUserRepository.findByTournamentId(rootId)
        val completed = tournamentUserRepository.findCompletedByTournamentId(rootId)
        val participantUserIds = (active.map { it.userId } + completed.map { it.userId }).toSet()
        val completedUserIds = completed.map { it.userId }.toSet()
        return GroupFlags(
            hasGroupResult = completedUserIds.size >= 2,
            isGroupTournament = participantUserIds.size >= 2,
        )
    }

    // 아이템 담기 허용(#1027) — 클론이 사라져 "ROOT 참여 행 보유" 가 곧 "이 토너먼트의 정식 참여자" 다.
    // 주최자·소셜 멤버·플레이링크 게스트 모두 ROOT 참여 행을 가지므로 참여자면 담기를 허용한다(과거 플레이링크
    // 클론만 false 였던 구분은 클론이 사라지며 소멸 — 관련 클라 계약 정리는 Phase 4). 호출부는 이미 ROOT 로 해소한다.
    private fun canAddItemForTournament(
        tournament: Tournament,
        userId: UUID,
    ): Boolean = tournamentUserRepository.findByTournamentIdAndUserId(tournament.getId(), userId)?.let { true } ?: false

    private fun buildCompleted(
        tournament: Tournament,
        histories: List<TournamentHistory>,
        groupFlags: GroupFlags,
        isOwner: Boolean,
        canAddItem: Boolean,
    ): TournamentDetail.Completed {
        val rankedPairs = computeRanking(histories)
        val tournamentItemById =
            tournamentItemRepository
                .findByIds(rankedPairs.map { it.first })
                .associateBy { it.getId() }
        val snapshotById = snapshotsOf(tournamentItemById.values)
        return TournamentDetail.Completed(
            tournamentId = tournament.getId(),
            name = tournament.name,
            result =
                rankedPairs.map { (tournamentItemId, rank) ->
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
            // #1027: 클론이 사라져 API 관점의 토너먼트는 항상 ROOT 다.
            isRoot = true,
            canAddItem = canAddItem,
            playLinkExpiresAt = tournament.playLinkExpiresAt,
            sourceTournamentId = null,
        )
    }

    @Transactional
    fun deleteTournament(
        userId: UUID,
        tournamentId: Long,
    ) {
        val requested =
            tournamentRepository.findTournamentByIdForUpdate(tournamentId)
                ?: throw TournamentException.notFoundTournament()
        val tournament = rootForUpdate(requested)
        val tournamentUser =
            tournamentUserRepository.findByTournamentIdAndUserId(tournament.getId(), userId)
                ?: throw TournamentException.forbiddenTournament()
        if (tournamentUser.getId() != tournament.ownerTournamentUserId) throw TournamentException.forbiddenTournament()
        // #1027: 삭제 가부는 전역 status 가 아니라 주최자 참여 행 status 로 판정한다(주최자가 자기 판을 어디까지 했나).
        when (tournamentUser.status) {
            TournamentStatus.PENDING -> {
                // 아직 아무도 플레이하지 않은 구성 단계 — 전체 cascade 삭제한다.
                tournamentItemRepository.softDeleteAllByTournamentId(tournament.getId())
                tournamentUserRepository.softDeleteAllByTournamentId(tournament.getId())
                tournamentRepository.softDeleteTournament(tournament.getId())
            }
            TournamentStatus.IN_PROGRESS -> throw TournamentException.inProgressTournamentCannotBeDeleted()
            TournamentStatus.COMPLETED -> {
                // 주최자가 자기 판을 완료한 뒤 삭제: 주최자 TU 만 제거하고 플레이 링크를 무효화한다.
                // 토너먼트·히스토리·다른 참여자 행은 유지되어 그들이 계속 접근 가능하고 그룹 결과에서도 주최자 내역이 보존된다.
                tournamentUserRepository.softDeleteByTournamentIdAndUserId(tournament.getId(), userId)
                tournament.expirePlayLink()
            }
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
        val joined =
            userId?.let { tournamentUserRepository.existsByTournamentIdAndUserId(tournament.getId(), it) } ?: false
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
        val requested =
            tournamentRepository.findTournamentByIdForUpdate(tournamentId)
                ?: throw TournamentException.notFoundTournament()
        val tournament = rootForUpdate(requested)
        val tournamentUser =
            tournamentUserRepository.findByTournamentIdAndUserId(tournament.getId(), userId)
                ?: throw TournamentException.forbiddenTournament()
        if (tournamentUser.getId() != tournament.ownerTournamentUserId) throw TournamentException.forbiddenTournament()
        // #1027: "완료" 는 전역 status 가 아니라 주최자 참여 행으로 판정한다 — 주최자가 자기 판을 완주해야 공유 가능.
        if (!tournamentUser.isCompleted()) throw TournamentException.notCompletedTournament()
        // 멱등(#980) — 유효한 링크가 있으면 그 값을 그대로 돌려준다(연장하지 않는다: 공유 버튼을 다시 누른
        // 것만으로 노출 기간이 늘면 주최자가 의도하지 않은 노출이 생긴다). 없거나(최초) 만료됐으면 새로 발급한다.
        // 종전엔 "값이 있으면 무조건 거부" 라 만료된 뒤에는 영구히 재발급이 안 됐다 — 유효기간이 링크를 죽이는
        // 데만 쓰이고 되살리는 데는 안 쓰였다. 주최자 탈퇴로 무효화된 경우는 이 지점에 닿지 않는다: softDelete
        // 가 주최자 TournamentUser 행을 지우므로 위 findByTournamentIdAndUserId 가 못 찾아 forbiddenTournament
        // 로 먼저 걸린다(findByTournamentIdAndUserIdAndDeletedAtIsNull 위임, 실측 확인).
        if (tournament.isPlayLinkValid()) return requireNotNull(tournament.playLinkExpiresAt)
        val expiresAt =
            LocalDateTime
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

    // create 와 달리 회원 게이트를 두지 않는다(#339) — 여기서 만들어지는 것은 참여 행 하나뿐이고, 참여 행은
    // 추출·LLM 비용을 만들 수 없다. 플레이 링크로 들어와 바로 플레이하는 것은 게스트의 핵심 시나리오라,
    // 비용이 0 인 이 경로까지 회원 전용으로 만들지 않는다.
    // #1027: 클론을 만들지 않고 ROOT 에 참여 행을 붙인다(get-or-create). 반환 id 는 항상 ROOT — 이후 start·플레이는
    // 그 ROOT id 로 진행된다. 이미 참여 중이면(멤버·재진입 게스트) 그 ROOT id 를 그대로 돌려 "이어서 진행하기".
    @Transactional
    fun createFromPlayLink(
        userId: UUID,
        sourceTournamentId: Long,
    ): Long {
        val requested =
            tournamentRepository.findTournamentByIdForUpdate(sourceTournamentId)
                ?: throw TournamentException.notFoundTournament()
        // 방어적: 클론 id 로 와도 ROOT 로 해소한다(플레이링크는 원래 ROOT/source id 를 쓴다).
        val root = rootForUpdate(requested)

        // 이미 참여 행이 있으면(초대 멤버·재진입 게스트) 그 ROOT id 로 이어서 진행한다. 링크 만료와 무관하게 돌려준다.
        tournamentUserRepository.findByTournamentIdAndUserId(root.getId(), userId)?.let { return root.getId() }

        // 신규 참여 생성 경로에서만 플레이링크 유효성을 검증한다.
        root.playLinkExpiresAt ?: throw TournamentException.playLinkNotCreated()
        if (!root.isPlayLinkValid()) throw TournamentException.playLinkExpired()

        // 참여 행은 PENDING 으로 붙인다 — 이후 start(startAsMember)가 플레이 시작으로 전이한다.
        tournamentUserRepository.save(TournamentUser(root.getId(), userId, nicknameOf(userId)))
        // 플레이링크로 새로 참여해 플레이를 시작한 사실을 ROOT 주최자에게 알린다(#473). 신규 생성 분기에서만 발행한다.
        eventPublisher.publishEvent(TournamentPlayedFromLink(rootTournamentId = root.getId(), actorId = userId))
        return root.getId()
    }

    @Transactional(readOnly = true)
    fun getGroupResult(
        userId: UUID,
        tournamentId: Long,
    ): GroupResult {
        val requested =
            tournamentRepository.findTournamentById(tournamentId)
                ?: throw TournamentException.notFoundTournament()
        // 클론 id 로 와도 ROOT 로 해소한다(#1027). 클라이언트는 sourceTournamentId ?? URL_id 로 호출하는데,
        // Phase 3 이후 sourceTournamentId 가 null 이라 URL_id(클론일 수 있음)로 올 수 있어 여기서 흡수한다.
        val tournament = rootOf(requested)
        val rootId = tournament.getId()

        // 참여자만 조회 가능(주최자·멤버·게스트 모두 ROOT 참여 행 보유). 삭제한 주최자는 활성 행이 없어 여기서 막힌다.
        val requesterTU =
            tournamentUserRepository.findByTournamentIdAndUserId(rootId, userId)
                ?: throw TournamentException.forbiddenTournament()

        // Progressive gate: 본인 판이 완료됐고 전체 완료 인원 ≥2 일 때만 조회 가능. 완료자는 completedAt 기준
        // (deletedAt 무관)이라 완료 후 방을 삭제한 주최자도 완료 인원·plays 에 포함된다.
        val completedTUs = tournamentUserRepository.findCompletedByTournamentId(rootId)
        val completedUserIds = completedTUs.map { it.userId }.toSet()
        if (!requesterTU.isCompleted() || completedUserIds.size < 2) {
            throw TournamentException.groupResultNotAvailable()
        }

        // "play" = 한 참여자의 완료된 진행. 히스토리는 (rootId, tuId)에 있다. 같은 사용자 중복 없게 dedup.
        val plays = completedTUs.distinctBy { it.userId }
        val userById =
            userRepository
                .findByIds(plays.map { it.userId }.toSet())
                .associateBy { it.id }
        // 표시명: 참여 TU 닉네임 우선(#1018), NULL(레거시)이면 프로필 폴백. findByTournamentId(활성 TU)는 아직 완료
        // 안 한 참여자를, completedTUs(deletedAt 무관)는 삭제한 완료 주최자를 커버한다 — 둘을 합쳐 스냅샷 닉을 보존한다.
        val rootTUs = tournamentUserRepository.findByTournamentId(rootId) + completedTUs
        val nicknameByUserId = rootTUs.associate { it.userId to it.nickname }
        // 주최자 배지(#1062). TU id 가 아니라 userId 로 풀어 두면 참여 행이 어떻게 잡히든 같은 사람을 가리킨다.
        // 주최자 TU 를 못 찾으면(삭제된 주최자가 완주도 안 한 경우) 아무에게도 배지를 안 단다. 배지는 부가 표시라
        // 500 으로 결과 전체를 막는 것보다 조용히 빠지는 편이 낫다.
        val ownerUserId = rootTUs.firstOrNull { it.getId() == tournament.ownerTournamentUserId }?.userId

        // "선택자" = 해당 아이템을 자신의 1위(우승)로 고른 참여자
        // itemId 단위로 집계하고 정렬 후 그룹 rank 를 부여한다.
        val winnersByItemId = mutableMapOf<Long, MutableList<ParticipantSummary>>()
        val referenceItemsById: MutableMap<Long, RankedItem> = mutableMapOf()

        val allHistories = tournamentRepository.findHistoriesByTournamentIds(listOf(rootId))
        val allTournamentItemIds =
            allHistories.map { it.firstTournamentItemId } +
                allHistories.map { it.secondTournamentItemId }
        val tItemById = tournamentItemRepository.findByIds(allTournamentItemIds).associateBy { it.getId() }
        val snapshotById = snapshotsOf(tItemById.values)
        // 각 참여자의 히스토리는 tournamentUserId 로 분리한다(모두 rootId 소속) — O(1) 조회.
        val historiesByTuId = allHistories.groupBy { it.tournamentUserId }

        for (play in plays) {
            val playHistories = historiesByTuId[play.getId()].orEmpty()
            val ranked = runCatching { computeRanking(playHistories) }.getOrNull() ?: continue
            val user = userById[play.userId] ?: continue
            val participant =
                ParticipantSummary(
                    userId = user.id,
                    nickname = nicknameByUserId[play.userId] ?: user.nickname,
                    profileImage = user.profileImage,
                    isWithdrawn = !user.isActive(),
                    isHost = user.id == ownerUserId,
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

        val items =
            referenceItemsById.values
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
        val result = GroupResult(items = items)
        // 게스트에게는 다른 참여자의 신원을 지워 내린다(#1060) — 클라가 정상 값을 받아 가리는 게 아니라 서버가
        // 애초에 물음표 값을 내려야, 응답을 직접 뜯어봐도 남이 누구인지 알 수 없다.
        // 판정을 `== MEMBER` 로 두어(부정형이 아니라) identity 종류가 늘어도 기본이 "가린다" 쪽에 남게 한다.
        // users 행 없는 인증 유저(rejectIfDeleted 가 허용하는 레거시 창)도 회원임을 증명하지 못하므로 마스킹 대상이다.
        val requesterIsMember = userById[userId]?.identityType == IdentityType.MEMBER
        if (requesterIsMember) return result
        return result.maskedFor(userId, defaultProfileImages.masked())
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
        val eliminatedBeforeRound =
            histories
                .filter { it.currentRound > currentRound }
                .map { it.loser() }
                .toSet()
        val entries =
            allTournamentItems
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
    ): List<RoundBracket.MatchPair> =
        histories
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

    /**
     * 출전 가능 판정의 단일 출처. 담기와 시작이 같은 질문("이 아이템들로 겨룰 수 있나")에 답하므로 한 벌만 둔다 —
     * 두 벌이던 동안 담기만 포인터를, 시작은 표시값을 봐서 같은 아이템이 담기는 거부되고 시작은 되는 어긋남이
     * 있었다. 갈라질 수 있는 구조 자체를 없앤다.
     *
     * 미완성을 먼저 보는 이유: 파싱 중은 기다리면 풀리지만 미완성은 사용자가 빈 값을 채워야 풀린다. 둘이 섞였을
     * 땐 행동이 필요한 쪽을 알려야 하고, 아이템별 루프에 맡기면 앞선 파싱 중이 뒤쪽 미완성을 가린다.
     *
     * @param snapshots 판정 대상. 두 경로 모두 **표시값**을 넘긴다(포인터가 아니다).
     */
    private fun requireEntryEligible(
        snapshots: Collection<ItemSnapshot>,
        incomplete: () -> TournamentException,
        notReady: () -> TournamentException,
    ) {
        if (snapshots.any { it.isIncomplete() }) throw incomplete()
        if (snapshots.any { !it.isReady() }) throw notReady()
    }

    // tournament_item 들이 고정한 snapshot 을 한 번에 조회해 id→snapshot 맵으로. 표시값 조회의 메모리 조인 재료다.
    private fun snapshotsOf(tournamentItems: Collection<TournamentItem>): Map<Long, ItemSnapshot> =
        itemSnapshotRepository
            .findByIds(tournamentItems.map { it.snapshotId })
            .associateBy { it.getId() }

    // 대기실(PENDING) 표시용(#857) — 키는 포인터 snapshot id 를 유지하되 값을 파생 표시 버전으로 치환한다.
    // requireSnapshot(포인터 id 조회)을 쓰는 기존 조립 코드가 무수정으로 표시 버전을 읽게 된다.
    private fun displayedSnapshotsOf(tournamentItems: Collection<TournamentItem>): Map<Long, ItemSnapshot> {
        val pointers = snapshotsOf(tournamentItems)
        // 카드 주인은 출전시킨 사람 — 그 사람의 맥락(수기)과 카드가 기다리는 행(pin)의 진행 중만 그 카드에 보인다.
        val cardOf = { tournamentItem: TournamentItem -> DisplayCard.waitingOn(tournamentItem.requireSnapshot(pointers), owner = tournamentItem.userId) }
        val displayByCard = itemDisplayService.resolveDisplay(tournamentItems.map(cardOf))
        return tournamentItems.associate { it.snapshotId to displayByCard.getValue(cardOf(it)) }
    }

    // 고정 snapshot 은 출전 시점에 반드시 박힌다. 없으면 영속화 경로가 깨진 코드 버그다(전환 후 신규 출전부터 보장).
    private fun TournamentItem.requireSnapshot(snapshotById: Map<Long, ItemSnapshot>): ItemSnapshot =
        snapshotById[snapshotId]
            ?: error("snapshot 없음 — tournamentItemId=${getId()}, snapshotId=$snapshotId")

    private fun computeRanking(histories: List<TournamentHistory>): List<Pair<Long, Int>> {
        val finalMatch =
            histories.find { it.currentRound == Tournament.FINAL_ROUND_SIZE }
                ?: error(
                    "결승 기록 없음 — tournamentId=${histories.firstOrNull()?.tournamentId}, tournamentUserId=${histories.firstOrNull()?.tournamentUserId}",
                )
        val semiRound =
            histories
                .filter { it.currentRound > Tournament.FINAL_ROUND_SIZE }
                .minByOrNull { it.currentRound }
                ?.currentRound
        val semiLosers =
            semiRound
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
            else ->
                error(
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
        val countByRound =
            histories
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

// users 행이 없는 참여자의 표시명 폴백(#1027). 실제로는 isWithdrawn=true 라 FE 가 "유저 알수없음" 을 렌더하므로
// 이 값은 화면에 거의 노출되지 않지만, ParticipantDetail.nickname 이 non-null 이라 안전한 기본값을 둔다.
private const val UNKNOWN_PARTICIPANT_NICKNAME = "알 수 없음"

// 두 서비스(TournamentService.join, TournamentSocialPersistenceService.createGuestAndJoin)가
// 공유하는 초대 참여 검증. 링크 접근은 inviteCode=null, 코드 입력 경로는 inviteCode 포함.
internal fun Tournament.checkJoinable(inviteCode: String?) {
    if (!isPending()) throw TournamentException.notPendingTournament()
    if (!isInviteValid()) throw TournamentException.inviteExpired()
    inviteCode?.let { if (this.inviteCode != it) throw TournamentException.invalidInviteCode() }
}
