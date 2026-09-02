package com.depromeet.piki.tournament.controller

import com.depromeet.piki.common.exception.CommonErrorCode
import com.depromeet.piki.common.openapi.OpenApiObjectMapper
import com.depromeet.piki.common.openapi.binds
import com.depromeet.piki.common.openapi.examples
import com.depromeet.piki.auth.service.dto.TokenPair
import com.depromeet.piki.common.response.ApiResponseBody
import com.depromeet.piki.item.domain.ItemStatus
import com.depromeet.piki.tournament.controller.dto.CreateTournamentRequest
import com.depromeet.piki.tournament.controller.dto.CreateTournamentResponse
import com.depromeet.piki.tournament.controller.dto.GroupResultResponse
import com.depromeet.piki.tournament.controller.dto.JoinTournamentAsGuestRequest
import com.depromeet.piki.tournament.controller.dto.JoinTournamentAsGuestResponse
import com.depromeet.piki.tournament.controller.dto.UpdateInviteDurationRequest
import com.depromeet.piki.tournament.controller.dto.PlayLinkInfoResponse
import com.depromeet.piki.tournament.controller.dto.RankedItemResponse
import com.depromeet.piki.tournament.controller.dto.RecordMatchResponse
import com.depromeet.piki.tournament.controller.dto.TournamentDetailResponse
import com.depromeet.piki.tournament.controller.dto.TournamentInvitePreviewResponse
import com.depromeet.piki.tournament.controller.dto.TournamentStartResponse
import com.depromeet.piki.tournament.controller.dto.TournamentSummaryResponse
import com.depromeet.piki.tournament.domain.TournamentStatus
import com.depromeet.piki.tournament.service.TournamentException
import com.depromeet.piki.user.domain.UserException
import org.springdoc.core.customizers.OperationCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import java.time.LocalDateTime
import java.util.UUID

@Configuration
class TournamentApiExamples(
    private val openApiObjectMapper: OpenApiObjectMapper,
) {
    @Bean
    fun tournamentOpenApiExamples(): OperationCustomizer =
        OperationCustomizer { operation, handlerMethod ->
            when {
                handlerMethod.binds(TournamentController::getTournaments) ->
                    operation.examples(openApiObjectMapper.delegate) {
                        add(
                            status = HttpStatus.OK,
                            name = "목록 조회 성공",
                            payload =
                                ApiResponseBody.ok(
                                    listOf(
                                        TournamentSummaryResponse(
                                            tournamentId = 1,
                                            name = "내 토너먼트",
                                            status = TournamentStatus.PENDING,
                                            createdAt = LocalDateTime.of(2026, 5, 22, 12, 0, 0),
                                            participantProfileImages =
                                                listOf(
                                                    "https://cdn.example.com/profiles/user1.jpg",
                                                    "https://cdn.example.com/profiles/user2.jpg",
                                                ),
                                            thumbnailUrls =
                                                listOf(
                                                    "https://cdn.example.com/items/item1.jpg",
                                                    "https://cdn.example.com/items/item2.jpg",
                                                ),
                                        ),
                                    ),
                                ),
                        )
                        add(TournamentException.invalidLimit(), name = "limit 이 1 미만")
                        unauthorized()
                    }

                handlerMethod.binds(TournamentController::create) ->
                    operation.examples(openApiObjectMapper.delegate) {
                        add(
                            status = HttpStatus.CREATED,
                            name = "생성 성공",
                            payload =
                                ApiResponseBody.created(
                                    CreateTournamentResponse(
                                        tournamentId = 1,
                                        inviteCode = "ABC123",
                                        inviteExpiresAt = LocalDateTime.of(2026, 5, 30, 15, 0, 0),
                                    ),
                                ),
                        )
                        add(
                            status = HttpStatus.BAD_REQUEST,
                            name = "토너먼트 이름 미입력",
                            payload =
                                ApiResponseBody.fail<Unit>(
                                    CommonErrorCode.INVALID_INPUT,
                                    detail = CreateTournamentRequest.NAME_BLANK_MESSAGE,
                                ),
                        )
                        add(
                            status = HttpStatus.BAD_REQUEST,
                            name = "초대 유효 시간이 24시간 초과",
                            payload =
                                ApiResponseBody.fail<Unit>(
                                    CommonErrorCode.INVALID_INPUT,
                                    detail = CreateTournamentRequest.INVITE_DURATION_MAX_MESSAGE,
                                ),
                        )
                        unauthorized()
                        add(UserException.deletedUser(), name = "탈퇴한 유저")
                    }

                handlerMethod.binds(TournamentController::join) ->
                    operation.examples(openApiObjectMapper.delegate) {
                        add(
                            status = HttpStatus.OK,
                            name = "참여 성공",
                            payload = ApiResponseBody.ok<Unit>(),
                        )
                        add(
                            status = HttpStatus.BAD_REQUEST,
                            name = "초대 코드 형식 오류",
                            payload =
                                ApiResponseBody.fail<Unit>(
                                    CommonErrorCode.INVALID_INPUT,
                                    detail = JoinTournamentAsGuestRequest.INVITE_CODE_PATTERN_MESSAGE,
                                ),
                        )
                        add(TournamentException.invalidInviteCode(), name = "초대 코드 불일치")
                        unauthorized()
                        add(TournamentException.notFoundTournament(), name = "토너먼트를 찾을 수 없음")
                        add(TournamentException.notPendingTournament(), name = "PENDING 상태 아님")
                        add(TournamentException.inviteExpired(), name = "초대 링크 만료")
                        add(TournamentException.alreadyParticipant(), name = "이미 참여 중인 토너먼트")
                        add(TournamentException.participantLimitExceeded(), name = "참여 인원 초과 (최대 8명)")
                    }

                handlerMethod.binds(TournamentController::joinAsGuest) ->
                    operation.examples(openApiObjectMapper.delegate) {
                        add(
                            status = HttpStatus.CREATED,
                            name = "게스트 참여 성공 (APP — body 토큰)",
                            payload =
                                ApiResponseBody.created(
                                    JoinTournamentAsGuestResponse(
                                        tokenPair = TokenPair(
                                            accessToken = "eyJhbGciOiJIUzI1NiJ9.example",
                                            refreshToken = "eyJhbGciOiJIUzI1NiJ9.refresh",
                                        ),
                                        userId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
                                        nickname = "멋진친구",
                                        profileImage = "https://piki-assets.s3.ap-northeast-2.amazonaws.com/defaults/user-profile-3.png",
                                        tournamentId = 1,
                                    ),
                                ),
                        )
                        add(
                            status = HttpStatus.BAD_REQUEST,
                            name = "닉네임 미입력",
                            payload =
                                ApiResponseBody.fail<Unit>(
                                    CommonErrorCode.INVALID_INPUT,
                                    // Bean Validation 위반은 GlobalExceptionHandler.detailOf 가 위반 필드의 메시지를 그대로 detail 로 내린다.
                                    detail = JoinTournamentAsGuestRequest.NICKNAME_BLANK_MESSAGE,
                                ),
                        )
                        add(
                            status = HttpStatus.BAD_REQUEST,
                            name = "닉네임 10자 초과",
                            payload =
                                ApiResponseBody.fail<Unit>(
                                    CommonErrorCode.INVALID_INPUT,
                                    detail = JoinTournamentAsGuestRequest.NICKNAME_MAX_MESSAGE,
                                ),
                        )
                        add(
                            status = HttpStatus.BAD_REQUEST,
                            name = "초대 코드 형식 오류",
                            payload =
                                ApiResponseBody.fail<Unit>(
                                    CommonErrorCode.INVALID_INPUT,
                                    detail = JoinTournamentAsGuestRequest.INVITE_CODE_PATTERN_MESSAGE,
                                ),
                        )
                        // Bean Validation(@NotBlank·@Size)을 통과해도 User 생성자의 validateNickname 이 예약 prefix 를 막는다.
                        add(UserException.nicknameReserved(), name = "'탈퇴' 예약 prefix 로 시작하는 닉네임")
                        add(TournamentException.invalidInviteCode(), name = "초대 코드 불일치")
                        add(TournamentException.notFoundTournament(), name = "토너먼트를 찾을 수 없음")
                        add(TournamentException.notPendingTournament(), name = "PENDING 상태 아님")
                        add(TournamentException.inviteExpired(), name = "초대 링크 만료")
                        add(UserException.duplicateNickname(), name = "닉네임 중복")
                        add(TournamentException.participantLimitExceeded(), name = "참여 인원 초과 (최대 8명)")
                    }

                handlerMethod.binds(TournamentController::start) ->
                    operation.examples(openApiObjectMapper.delegate) {
                        add(
                            status = HttpStatus.OK,
                            name = "시작 성공",
                            payload =
                                ApiResponseBody.ok(
                                    TournamentStartResponse(
                                        tournamentId = 1,
                                        items =
                                            listOf(
                                                TournamentStartResponse.ItemResponse(
                                                    tournamentItemId = 1,
                                                    name = "나이키 에어맥스",
                                                    price = 129_000,
                                                    currency = "KRW",
                                                    imageUrl = "https://cdn.example.com/items/1.jpg",
                                                ),
                                                TournamentStartResponse.ItemResponse(
                                                    tournamentItemId = 2,
                                                    name = "아디다스 울트라부스트",
                                                    price = 189_000,
                                                    currency = "KRW",
                                                    imageUrl = "https://cdn.example.com/items/2.jpg",
                                                ),
                                                TournamentStartResponse.ItemResponse(
                                                    tournamentItemId = 3,
                                                    name = "뉴발란스 993",
                                                    price = 259_000,
                                                    currency = "KRW",
                                                    imageUrl = "https://cdn.example.com/items/3.jpg",
                                                ),
                                                TournamentStartResponse.ItemResponse(
                                                    tournamentItemId = 4,
                                                    name = "살로몬 XT-6",
                                                    price = 279_000,
                                                    currency = "USD",
                                                    imageUrl = null,
                                                ),
                                            ),
                                    ),
                                ),
                        )
                        add(TournamentException.invalidItemCount(), name = "아이템 수 미충족 (2~32개)")
                        unauthorized()
                        add(TournamentException.forbiddenTournament(), name = "토너먼트 권한 없음")
                        add(TournamentException.notFoundTournament(), name = "토너먼트를 찾을 수 없음")
                        add(TournamentException.notFoundItems(), name = "존재하지 않는 아이템 포함")
                        add(TournamentException.notPendingTournament(), name = "PENDING 상태 아님")
                        add(TournamentException.itemNotReadyToStart(), name = "아직 준비 중인 상품 포함")
                        add(TournamentException.itemIncompleteToStart(), name = "정보가 일부만 채워진(INCOMPLETE) 상품 포함")
                        add(TournamentException.itemPriceRequired(), name = "가격 정보 없는 상품 포함")
                        // 아래 둘은 멤버 시작 경로(startAsMember) — 주최자가 ROOT 를 시작해야 멤버가 본인 클론을 만들 수 있다.
                        add(TournamentException.notInProgressTournament(), name = "주최자가 아직 시작하지 않음 (멤버 시작)")
                        add(TournamentException.alreadyCloned(), name = "이미 본인 클론이 있음 (멤버 시작)")
                    }

                handlerMethod.binds(TournamentController::recordMatch) ->
                    operation.examples(openApiObjectMapper.delegate) {
                        add(
                            status = HttpStatus.OK,
                            name = "기록 성공 (라운드 진행 중) - nextMatch 에 다음 매치",
                            payload =
                                ApiResponseBody.ok(
                                    RecordMatchResponse(
                                        nextMatch = MATCH_EXAMPLE,
                                        completed = null,
                                    ),
                                ),
                        )
                        add(
                            status = HttpStatus.OK,
                            name = "기록 성공 (라운드 종료) - nextMatch 생략으로 data 가 빈 객체, GET 재조회 필요",
                            payload =
                                ApiResponseBody.ok(
                                    RecordMatchResponse(nextMatch = null, completed = null),
                                ),
                        )
                        add(
                            status = HttpStatus.OK,
                            name = "기록 성공 (결승 라운드) - 순위 결과 포함, 재전송 시 같은 응답",
                            payload =
                                ApiResponseBody.ok(
                                    RecordMatchResponse(
                                        nextMatch = null,
                                        completed =
                                            TournamentDetailResponse.CompletedData(
                                                result =
                                                    listOf(
                                                        RankedItemResponse(
                                                            rank = 1,
                                                            tournamentItemId = 1,
                                                            itemId = 10,
                                                            name = "나이키 에어맥스",
                                                            price = 129_000,
                                                            currency = "KRW",
                                                            imageUrl = "https://cdn.example.com/items/1.jpg",
                                                        ),
                                                        RankedItemResponse(
                                                            rank = 2,
                                                            tournamentItemId = 2,
                                                            itemId = 20,
                                                            name = "아디다스 울트라부스트",
                                                            price = 189_000,
                                                            currency = "KRW",
                                                            imageUrl = "https://cdn.example.com/items/2.jpg",
                                                        ),
                                                        RankedItemResponse(
                                                            rank = 3,
                                                            tournamentItemId = 3,
                                                            itemId = 30,
                                                            name = "뉴발란스 993",
                                                            price = 259_000,
                                                            currency = "KRW",
                                                            imageUrl = "https://cdn.example.com/items/3.jpg",
                                                        ),
                                                        RankedItemResponse(
                                                            rank = 4,
                                                            tournamentItemId = 4,
                                                            itemId = 40,
                                                            name = "살로몬 XT-6",
                                                            price = 279_000,
                                                            currency = "USD",
                                                            imageUrl = null,
                                                        ),
                                                    ),
                                                hasGroupResult = true,
                                                isGroupTournament = true,
                                                canAddItem = true,
                                                playLinkExpiresAt = LocalDateTime.of(2026, 6, 20, 22, 0, 0),
                                            ),
                                    ),
                                ),
                        )
                        add(TournamentException.invalidWinner(), name = "승자가 대결 아이템이 아님")
                        add(TournamentException.invalidMatchPair(), name = "서버 브래킷에 없는 페어 조합")
                        add(TournamentException.invalidTournamentItem(), name = "이 토너먼트에 없는 아이템")
                        add(TournamentException.invalidCurrentRound(), name = "현재 진행 라운드와 불일치")
                        unauthorized()
                        add(TournamentException.forbiddenTournament(), name = "토너먼트 권한 없음")
                        add(TournamentException.notFoundTournament(), name = "토너먼트를 찾을 수 없음")
                        add(TournamentException.notInProgressTournament(), name = "진행 중 토너먼트 아님")
                        add(TournamentException.eliminatedTournamentItem(), name = "이미 탈락한 아이템")
                        add(TournamentException.matchAlreadyRecorded(), name = "이미 기록된 대결에 다른 승자 전송")
                    }

                handlerMethod.binds(TournamentController::getTournamentById) ->
                    operation.examples(openApiObjectMapper.delegate) {
                        add(
                            status = HttpStatus.OK,
                            name = "PENDING - 아이템 담는 중",
                            payload =
                                ApiResponseBody.ok(
                                    TournamentDetailResponse(
                                        tournamentId = 1,
                                        name = "내 토너먼트",
                                        status = TournamentStatus.PENDING,
                                        isOwner = true,
                                        isRoot = true,
                                        sourceTournamentId = null,
                                        pending =
                                            TournamentDetailResponse.PendingData(
                                                inviteCode = "ABC123",
                                                inviteExpiresAt = LocalDateTime.of(2026, 5, 30, 15, 0, 0),
                                                items =
                                                    listOf(
                                                        TournamentDetailResponse.ItemDetailResponse(
                                                            tournamentItemId = 1,
                                                            itemId = 10,
                                                            userId = UUID.fromString("11111111-2222-3333-4444-555555555555"),
                                                            name = "나이키 에어맥스",
                                                            price = 129_000,
                                                            currency = "KRW",
                                                            imageUrl = "https://cdn.example.com/items/1.jpg",
                                                            status = ItemStatus.READY,
                                                        ),
                                                        TournamentDetailResponse.ItemDetailResponse(
                                                            tournamentItemId = 2,
                                                            itemId = 20,
                                                            userId = UUID.fromString("11111111-2222-3333-4444-555555555555"),
                                                            name = "아디다스 울트라부스트",
                                                            price = 189_000,
                                                            currency = "KRW",
                                                            imageUrl = "https://cdn.example.com/items/2.jpg",
                                                            status = ItemStatus.READY,
                                                        ),
                                                    ),
                                                participants =
                                                    listOf(
                                                        TournamentDetailResponse.ParticipantResponse(
                                                            userId =
                                                                UUID.fromString(
                                                                    "11111111-2222-3333-4444-555555555555",
                                                                ),
                                                            nickname = "참여자1",
                                                            profileImage = "https://cdn.example.com/profiles/user1.jpg",
                                                            isWithdrawn = false,
                                                            itemCount = 2,
                                                        ),
                                                    ),
                                            ),
                                        inProgress = null,
                                        completed = null,
                                    ),
                                ),
                        )
                        add(
                            status = HttpStatus.OK,
                            name = "IN_PROGRESS - 멤버 대기 중 (주최자 시작 완료, 본인 미시작)",
                            payload =
                                ApiResponseBody.ok(
                                    TournamentDetailResponse(
                                        tournamentId = 1,
                                        name = "내 토너먼트",
                                        status = TournamentStatus.IN_PROGRESS,
                                        isOwner = false,
                                        isRoot = true,
                                        sourceTournamentId = null,
                                        pending =
                                            TournamentDetailResponse.PendingData(
                                                inviteCode = null,
                                                inviteExpiresAt = null,
                                                items =
                                                    listOf(
                                                        TournamentDetailResponse.ItemDetailResponse(
                                                            tournamentItemId = 1,
                                                            itemId = 10,
                                                            userId = UUID.fromString("11111111-2222-3333-4444-555555555555"),
                                                            name = "나이키 에어맥스",
                                                            price = 129_000,
                                                            currency = "KRW",
                                                            imageUrl = "https://cdn.example.com/items/1.jpg",
                                                            status = ItemStatus.READY,
                                                        ),
                                                        TournamentDetailResponse.ItemDetailResponse(
                                                            tournamentItemId = 2,
                                                            itemId = 20,
                                                            userId = UUID.fromString("22222222-3333-4444-5555-666666666666"),
                                                            name = "아디다스 울트라부스트",
                                                            price = 189_000,
                                                            currency = "KRW",
                                                            imageUrl = "https://cdn.example.com/items/2.jpg",
                                                            status = ItemStatus.READY,
                                                        ),
                                                    ),
                                                participants =
                                                    listOf(
                                                        TournamentDetailResponse.ParticipantResponse(
                                                            userId = UUID.fromString("11111111-2222-3333-4444-555555555555"),
                                                            nickname = "주최자",
                                                            profileImage = "https://cdn.example.com/profiles/user1.jpg",
                                                            isWithdrawn = false,
                                                            itemCount = 2,
                                                        ),
                                                        TournamentDetailResponse.ParticipantResponse(
                                                            userId = UUID.fromString("22222222-3333-4444-5555-666666666666"),
                                                            nickname = "참여자",
                                                            profileImage = "https://cdn.example.com/profiles/user2.jpg",
                                                            isWithdrawn = false,
                                                            itemCount = 0,
                                                        ),
                                                    ),
                                                ownerStarted = true,
                                            ),
                                        inProgress = null,
                                        completed = null,
                                    ),
                                ),
                        )
                        add(
                            status = HttpStatus.OK,
                            name = "IN_PROGRESS - 진행 중 복원 (CLONE)",
                            payload =
                                ApiResponseBody.ok(
                                    TournamentDetailResponse(
                                        tournamentId = 2,
                                        name = "내 토너먼트",
                                        status = TournamentStatus.IN_PROGRESS,
                                        isOwner = false,
                                        isRoot = false,
                                        sourceTournamentId = 1,
                                        pending = null,
                                        inProgress =
                                            TournamentDetailResponse.InProgressData(
                                                currentRound = 4,
                                                lastHistory =
                                                    TournamentDetailResponse.HistoryResponse(
                                                        currentRound = 4,
                                                        firstTournamentItemId = 1,
                                                        secondTournamentItemId = 2,
                                                        selectedTournamentItemId = 1,
                                                    ),
                                                remainingItems =
                                                    listOf(
                                                        TournamentDetailResponse.ItemDetailResponse(
                                                            tournamentItemId = 3,
                                                            itemId = 30,
                                                            userId = UUID.fromString("11111111-2222-3333-4444-555555555555"),
                                                            name = "뉴발란스 993",
                                                            price = 259_000,
                                                            currency = "KRW",
                                                            imageUrl = "https://cdn.example.com/items/3.jpg",
                                                            status = ItemStatus.READY,
                                                        ),
                                                        TournamentDetailResponse.ItemDetailResponse(
                                                            tournamentItemId = 4,
                                                            itemId = 40,
                                                            userId = UUID.fromString("22222222-3333-4444-5555-666666666666"),
                                                            name = "살로몬 XT-6",
                                                            price = 279_000,
                                                            currency = "KRW",
                                                            imageUrl = null,
                                                            status = ItemStatus.READY,
                                                        ),
                                                    ),
                                                currentMatch = MATCH_EXAMPLE,
                                            ),
                                        completed = null,
                                    ),
                                ),
                        )
                        add(
                            status = HttpStatus.OK,
                            name = "COMPLETED - 최종 결과",
                            payload =
                                ApiResponseBody.ok(
                                    TournamentDetailResponse(
                                        tournamentId = 1,
                                        name = "내 토너먼트",
                                        status = TournamentStatus.COMPLETED,
                                        isOwner = true,
                                        isRoot = true,
                                        sourceTournamentId = null,
                                        pending = null,
                                        inProgress = null,
                                        completed =
                                            TournamentDetailResponse.CompletedData(
                                                result =
                                                    listOf(
                                                        RankedItemResponse(
                                                            rank = 1,
                                                            tournamentItemId = 1,
                                                            itemId = 10,
                                                            name = "나이키 에어맥스",
                                                            price = 129_000,
                                                            currency = "KRW",
                                                            imageUrl = "https://cdn.example.com/items/1.jpg",
                                                        ),
                                                        RankedItemResponse(
                                                            rank = 2,
                                                            tournamentItemId = 2,
                                                            itemId = 20,
                                                            name = "아디다스 울트라부스트",
                                                            price = 189_000,
                                                            currency = "KRW",
                                                            imageUrl = "https://cdn.example.com/items/2.jpg",
                                                        ),
                                                        RankedItemResponse(
                                                            rank = 3,
                                                            tournamentItemId = 3,
                                                            itemId = 30,
                                                            name = "뉴발란스 993",
                                                            price = 259_000,
                                                            currency = "KRW",
                                                            imageUrl = "https://cdn.example.com/items/3.jpg",
                                                        ),
                                                        RankedItemResponse(
                                                            rank = 4,
                                                            tournamentItemId = 4,
                                                            itemId = 40,
                                                            name = "살로몬 XT-6",
                                                            price = 279_000,
                                                            currency = "KRW",
                                                            imageUrl = null,
                                                        ),
                                                    ),
                                                hasGroupResult = true,
                                                isGroupTournament = true,
                                                canAddItem = true,
                                                playLinkExpiresAt = LocalDateTime.of(2026, 6, 20, 22, 0, 0),
                                            ),
                                    ),
                                ),
                        )
                        unauthorized()
                        add(TournamentException.forbiddenTournament(), name = "토너먼트 권한 없음")
                        add(TournamentException.notFoundTournament(), name = "토너먼트를 찾을 수 없음")
                    }

                handlerMethod.binds(TournamentController::deleteTournament) ->
                    operation.examples(openApiObjectMapper.delegate) {
                        add(
                            status = HttpStatus.OK,
                            name = "토너먼트 삭제 성공",
                            payload = ApiResponseBody.ok<Unit>(),
                        )
                        unauthorized()
                        add(TournamentException.forbiddenTournament(), name = "토너먼트 권한 없음")
                        add(TournamentException.notFoundTournament(), name = "토너먼트를 찾을 수 없음")
                        add(TournamentException.inProgressTournamentCannotBeDeleted(), name = "진행 중 토너먼트 삭제 불가")
                    }

                handlerMethod.binds(TournamentController::updateInviteExpiry) ->
                    operation.examples(openApiObjectMapper.delegate) {
                        add(
                            status = HttpStatus.OK,
                            name = "초대 마감 시각 수정 성공",
                            payload = ApiResponseBody.ok(LocalDateTime.of(2026, 6, 7, 11, 0, 0)),
                        )
                        add(
                            status = HttpStatus.BAD_REQUEST,
                            name = "과거 시각 입력",
                            payload =
                                ApiResponseBody.fail<Unit>(
                                    CommonErrorCode.INVALID_INPUT,
                                    detail = UpdateInviteDurationRequest.INVITE_EXPIRY_PAST_MESSAGE,
                                ),
                        )
                        unauthorized()
                        add(TournamentException.forbiddenTournament(), name = "권한 없음 (소유자 아님)")
                        add(TournamentException.notFoundTournament(), name = "토너먼트를 찾을 수 없음")
                        add(TournamentException.notPendingTournament(), name = "PENDING이 아닌 토너먼트")
                    }

                handlerMethod.binds(TournamentController::getInvitePreview) ->
                    operation.examples(openApiObjectMapper.delegate) {
                        add(
                            status = HttpStatus.OK,
                            name = "링크 접근 미리보기 성공",
                            payload =
                                ApiResponseBody.ok(
                                    TournamentInvitePreviewResponse(
                                        tournamentId = 1,
                                        tournamentName = "내 토너먼트",
                                        itemCount = 8,
                                        participantCount = 2,
                                        joined = false,
                                    ),
                                ),
                        )
                        add(TournamentException.notFoundTournament(), name = "토너먼트를 찾을 수 없음")
                        add(TournamentException.inviteExpired(), name = "초대 링크 만료")
                    }

                handlerMethod.binds(TournamentController::getInvitePreviewByCode) ->
                    operation.examples(openApiObjectMapper.delegate) {
                        add(
                            status = HttpStatus.OK,
                            name = "코드 조회 성공",
                            payload = ApiResponseBody.ok(
                                TournamentInvitePreviewResponse(
                                    tournamentId = 1,
                                    tournamentName = "내 토너먼트",
                                    itemCount = 8,
                                    participantCount = 2,
                                    joined = false,
                                ),
                            ),
                        )
                        add(TournamentException.invalidInviteCode(), name = "코드에 해당하는 토너먼트 없음")
                        add(TournamentException.inviteExpired(), name = "초대 링크 만료")
                    }

                handlerMethod.binds(TournamentController::createPlayLink) ->
                    operation.examples(openApiObjectMapper.delegate) {
                        // 멱등(#980) — 세 상태(최초 생성 · 유효한 값 그대로 · 만료 후 갱신) 모두 200 이다.
                        add(
                            status = HttpStatus.OK,
                            name = "최초 생성",
                            payload = ApiResponseBody.ok(LocalDateTime.of(2026, 6, 9, 22, 0, 0)),
                        )
                        add(
                            status = HttpStatus.OK,
                            name = "유효한 링크가 이미 있음 (연장 없이 기존 만료시각 그대로)",
                            payload = ApiResponseBody.ok(LocalDateTime.of(2026, 6, 9, 22, 0, 0)),
                        )
                        add(
                            status = HttpStatus.OK,
                            name = "만료된 링크를 다시 호출 (새 14일로 갱신)",
                            payload = ApiResponseBody.ok(LocalDateTime.of(2026, 6, 27, 10, 0, 0)),
                        )
                        unauthorized()
                        add(TournamentException.forbiddenTournament(), name = "권한 없음 (참여자 아님 · 소유자 아님)")
                        add(
                            TournamentException.clonedTournamentCannotSharePlayLink(),
                            name = "플레이 링크로 참여한 토너먼트 (재공유 불가)",
                        )
                        add(TournamentException.notFoundTournament(), name = "토너먼트를 찾을 수 없음")
                        add(TournamentException.notCompletedTournament(), name = "완료되지 않은 토너먼트")
                    }

                handlerMethod.binds(TournamentController::getPlayLinkInfo) ->
                    operation.examples(openApiObjectMapper.delegate) {
                        add(
                            status = HttpStatus.OK,
                            name = "플레이 링크 정보 조회 성공",
                            payload =
                                ApiResponseBody.ok(
                                    PlayLinkInfoResponse(
                                        sourceTournamentId = 1,
                                        tournamentName = "내 토너먼트",
                                        itemCount = 8,
                                        playLinkExpiresAt = LocalDateTime.of(2026, 6, 9, 22, 0, 0),
                                    ),
                                ),
                        )
                        add(TournamentException.notFoundTournament(), name = "토너먼트를 찾을 수 없음")
                        add(TournamentException.playLinkNotCreated(), name = "플레이 링크가 생성되지 않은 토너먼트")
                        add(TournamentException.playLinkExpired(), name = "플레이 링크 만료")
                    }

                handlerMethod.binds(TournamentController::createFromPlayLink) ->
                    operation.examples(openApiObjectMapper.delegate) {
                        add(
                            status = HttpStatus.OK,
                            name = "복제 토너먼트 생성 또는 기존 본인 클론 반환",
                            payload = ApiResponseBody.ok(42L),
                        )
                        unauthorized()
                        add(TournamentException.notFoundTournament(), name = "토너먼트를 찾을 수 없음")
                        add(TournamentException.playLinkNotCreated(), name = "플레이 링크가 생성되지 않은 토너먼트")
                        add(TournamentException.playLinkExpired(), name = "플레이 링크 만료")
                    }

                handlerMethod.binds(TournamentController::getGroupResult) ->
                    operation.examples(openApiObjectMapper.delegate) {
                        add(
                            TournamentException.clonedTournamentCannotViewGroupResult(),
                            name = "플레이링크 복제 토너먼트에서 그룹 결과 조회 불가",
                        )
                        add(
                            status = HttpStatus.OK,
                            name = "그룹 결과 조회 성공",
                            payload =
                                ApiResponseBody.ok(
                                    GroupResultResponse(
                                        items =
                                            listOf(
                                                GroupResultResponse.GroupResultItemResponse(
                                                    rank = 1,
                                                    itemId = 10,
                                                    name = "나이키 에어맥스",
                                                    price = 129_000,
                                                    currency = "KRW",
                                                    imageUrl = "https://cdn.example.com/items/1.jpg",
                                                    chosenBy =
                                                        listOf(
                                                            GroupResultResponse.ParticipantSummaryResponse(
                                                                userId =
                                                                    UUID.fromString(
                                                                        "11111111-2222-3333-4444-555555555555",
                                                                    ),
                                                                nickname = "참여자A",
                                                                profileImage = "https://piki-assets.s3.ap-northeast-2.amazonaws.com/defaults/user-profile-3.png",
                                                                isWithdrawn = false,
                                                            ),
                                                            GroupResultResponse.ParticipantSummaryResponse(
                                                                userId =
                                                                    UUID.fromString(
                                                                        "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                                                                    ),
                                                                // 탈퇴 유저 — 닉네임·프로필은 익명값, isWithdrawn=true 로 FE 가 "유저 알수없음" 렌더.
                                                                nickname = "탈퇴aaaaaaaa",
                                                                profileImage = "https://piki-assets.s3.ap-northeast-2.amazonaws.com/defaults/user-deleted.png",
                                                                isWithdrawn = true,
                                                            ),
                                                        ),
                                                ),
                                                GroupResultResponse.GroupResultItemResponse(
                                                    rank = 2,
                                                    itemId = 20,
                                                    name = "아디다스 울트라부스트",
                                                    price = 189_000,
                                                    currency = "KRW",
                                                    imageUrl = "https://cdn.example.com/items/2.jpg",
                                                    chosenBy =
                                                        listOf(
                                                            GroupResultResponse.ParticipantSummaryResponse(
                                                                userId =
                                                                    UUID.fromString(
                                                                        "11111111-2222-3333-4444-555555555555",
                                                                    ),
                                                                nickname = "참여자A",
                                                                profileImage = "https://piki-assets.s3.ap-northeast-2.amazonaws.com/defaults/user-profile-3.png",
                                                                isWithdrawn = false,
                                                            ),
                                                        ),
                                                ),
                                            ),
                                    ),
                                ),
                        )
                        unauthorized()
                        add(TournamentException.forbiddenTournament(), name = "참여자가 아님")
                        add(TournamentException.notFoundTournament(), name = "토너먼트를 찾을 수 없음")
                        add(
                            TournamentException.groupResultNotAvailable(),
                            name = "본인 플레이 미완료 · 완료 인원 2명 미만",
                        )
                    }
            }
            operation
        }

    companion object {
        // 서버가 브래킷에서 파생한 매치 example(#683). inProgress.currentMatch 와 POST 응답 nextMatch 가
        // 같은 모양이라 한 곳에 두고 양쪽에서 참조한다.
        private val MATCH_EXAMPLE =
            TournamentDetailResponse.MatchResponse(
                first =
                    TournamentDetailResponse.ItemDetailResponse(
                        tournamentItemId = 3,
                        itemId = 30,
                        userId = UUID.fromString("11111111-2222-3333-4444-555555555555"),
                        name = "뉴발란스 993",
                        price = 259_000,
                        currency = "KRW",
                        imageUrl = "https://cdn.example.com/items/3.jpg",
                        status = ItemStatus.READY,
                    ),
                second =
                    TournamentDetailResponse.ItemDetailResponse(
                        tournamentItemId = 4,
                        itemId = 40,
                        userId = UUID.fromString("22222222-3333-4444-5555-666666666666"),
                        name = "살로몬 XT-6",
                        price = 279_000,
                        currency = "KRW",
                        imageUrl = null,
                        status = ItemStatus.READY,
                    ),
            )
    }
}
