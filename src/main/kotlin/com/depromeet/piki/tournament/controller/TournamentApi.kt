package com.depromeet.piki.tournament.controller

import com.depromeet.piki.auth.web.ClientType
import com.depromeet.piki.common.response.ApiResponseBody
import com.depromeet.piki.tournament.controller.dto.CreateFromPlayCodeRequest
import com.depromeet.piki.tournament.controller.dto.CreateTournamentRequest
import com.depromeet.piki.tournament.controller.dto.CreateTournamentResponse
import com.depromeet.piki.tournament.controller.dto.GroupResultResponse
import com.depromeet.piki.tournament.controller.dto.JoinTournamentAsGuestRequest
import com.depromeet.piki.tournament.controller.dto.JoinTournamentAsGuestResponse
import com.depromeet.piki.tournament.controller.dto.UpdateInviteDurationRequest
import com.depromeet.piki.tournament.controller.dto.JoinTournamentRequest
import com.depromeet.piki.tournament.controller.dto.PlayLinkInfoResponse
import com.depromeet.piki.tournament.controller.dto.RecordMatchRequest
import com.depromeet.piki.tournament.controller.dto.RecordMatchResponse
import com.depromeet.piki.tournament.controller.dto.TournamentDetailResponse
import com.depromeet.piki.tournament.controller.dto.TournamentInvitePreviewResponse
import com.depromeet.piki.tournament.controller.dto.TournamentStartResponse
import com.depromeet.piki.tournament.controller.dto.TournamentSummaryResponse
import com.depromeet.piki.tournament.controller.dto.UpdateTournamentNicknameRequest
import com.depromeet.piki.tournament.domain.TournamentPlayType
import com.depromeet.piki.tournament.domain.TournamentStatus
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import java.time.LocalDateTime
import java.util.UUID

@Tag(name = "Tournament", description = "토너먼트 API")
interface TournamentApi {

    @Operation(
        summary = "토너먼트 목록 조회",
        description = """
            내 토너먼트 목록을 최근 생성 순으로 조회한다.
            status 파라미터로 상태 필터링 가능하며 여러 값을 중복 전달할 수 있다(예: ?status=PENDING&status=IN_PROGRESS).
            생략 시 전체 반환. status 값은 대문자(PENDING/IN_PROGRESS/COMPLETED)로 전달해야 한다.
            limit 파라미터로 최근순 상위 N개만 받을 수 있다(예: 홈 카드용 ?limit=3). 생략 시 전체.
            ownedOnly=true 면 내가 만든 토너먼트만 반환한다(참여만 한 것은 제외) — 홈 카드용. 생략 시 false 로 참여한 것까지 포함.
            ownedOnly 는 status 와 독립된 필터라 함께 보내면 AND 로 걸린다. 홈이 상태 무관인 것은 status 를 안 보내기 때문이다.
            playType 파라미터로 솔로/소셜을 나눠 볼 수 있다(SOLO · SOCIAL). 생략 시 전체이며 status 와 AND 로 걸린다
            (예: ?status=PENDING&status=IN_PROGRESS&playType=SOCIAL).
            playType 은 저장된 속성이 아니라 참가 결과로 파생된다 — 혼자면 SOLO, 참여자가 생기면 SOCIAL 이고
            남의 토너먼트에 참여한 항목은 항상 SOCIAL 이다. 참여는 초대 코드(PENDING)와 플레이 링크(주최자가 자기 플레이를 완주한 뒤)로 일어나므로,
            그 사이 같은 토너먼트가 SOLO 목록에서 SOCIAL 목록으로 옮겨갈 수 있다.
            각 항목의 thumbnailUrls 는 카드 대표 썸네일 URL 배열이다 — 최근 등록 아이템 중 이미지가 준비된(READY) 것 최대 2장.
            이미지가 준비된 아이템이 없으면 빈 배열이며, limit 유무와 무관하게 항상 포함된다.

            카드에 뜨는 인원수 두 개는 다음과 같다.
            - participantCount ("함께 담은 N"): 그 토너먼트의 참여자 수. 아이템을 아직 안 담은 참여자도 포함한다.
            - playedCount ("플레이한 N"): 플레이를 끝까지 마친 사람 수. 시작만 하고 이탈한 사람은 빠지며, 아무도 완주 안 했으면 0이다.
              전체 결과 영수증에 나오는 인원과 같은 기준이다.

            플레이 링크로 참여한 토너먼트도 같은 토너먼트의 참여자로 세므로 두 값이 주최자 카드와 같다.

            **participantProfileImages 는 deprecated 다.** 위 두 숫자로 대체됐고, 클라이언트가 전환해 배포되면 서버에서 제거한다.
            그때까지는 옛 값 그대로 함께 내려가니 새로 참조하지 않는다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "목록 조회 성공 (참여 토너먼트 없으면 빈 배열 반환)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "400",
                description =
                    "잘못된 요청 (limit 이 1 미만 · limit 이 정수가 아님 · " +
                        "status 가 정의되지 않은 값 · playType 이 SOLO/SOCIAL 이 아닌 값)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "401",
                description = "미인증 (JWT 토큰 없음 또는 유효하지 않음)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
        ],
    )
    fun getTournaments(
        @Parameter(hidden = true) userId: UUID,
        @Parameter(description = "상태 필터 (복수 전달 가능, 생략 시 전체)", example = "PENDING")
        status: List<TournamentStatus>?,
        @Parameter(
            description = "플레이 유형 필터 (SOLO · SOCIAL, 생략 시 전체). status 와 AND 로 걸린다.",
            example = "SOCIAL",
        )
        playType: TournamentPlayType?,
        @Parameter(
            description = "내가 생성한 것만 볼지 (true 면 내가 만든 토너먼트만). 생략 시 false 로 참여한 것까지 포함. status 와는 AND 로 걸린다. 홈 카드용.",
            example = "true",
        )
        ownedOnly: Boolean,
        @Parameter(description = "조회 개수 제한 (최근순 상위 N, 생략 시 전체). 1 이상.", example = "3")
        limit: Int?,
    ): ApiResponseBody<List<TournamentSummaryResponse>>

    @Operation(
        summary = "토너먼트 단건 조회",
        description = """
            토너먼트 ID로 상태에 따른 상세 정보를 조회한다.
            isOwner: 요청자가 이 토너먼트를 만든 주최자이면 true. 플레이 링크 공유는 isOwner 일 때 허용된다.
            isRoot: **deprecated.** 항상 true 다. 플레이 링크 참여자에게 사본 토너먼트를 만들던 구조(CLONE)가 사라져(#1027)
              모든 참여자가 같은 원본 토너먼트를 본다. 클라이언트가 참조를 끊으면 서버에서 제거한다.
            sourceTournamentId: **deprecated.** 같은 이유로 항상 null 이라 응답에서 키가 생략된다. 그룹 결과는 응답의 tournamentId 로 호출한다.
            응답의 status 필드에 따라 포함되는 데이터가 달라진다.
            - PENDING: pending 필드 (아이템 목록, 참여자 목록)
              - 각 아이템에 status 포함 (READY / PENDING / PROCESSING / FAILED). PENDING·PROCESSING 이면 name·price·imageUrl 은 null 이라 응답에서 제외됨
              - 각 아이템에 userId 포함 (해당 아이템을 담은 참여자의 userId)
              - 각 참여자에 itemCount 포함 (해당 참여자가 이 토너먼트에 담은 아이템 수)
              - 각 참여자에 isHost 포함 (이 토너먼트를 만든 주최자면 true). 클라이언트는 이 값으로 프로필 우측 하단에 HostBadge 를 그린다.
                상위의 isOwner("요청자가 주최자냐")와 다른 값이다 - isHost 는 참여자 각각에 대한 사실이다
              - 참여자 배열의 순서가 계약이다: 본인 → 주최자 → 그 외 참여자(입장 순). 받은 순서대로 그리면 되고 클라이언트가 다시 정렬하지 않는다.
                요청자가 주최자면 두 조건을 모두 만족해 맨 앞 하나로 합쳐진다
              - pending.ownerStarted = false
            - IN_PROGRESS: 요청자 역할에 따라 두 가지 응답이 있다.
              - 소유자(isOwner=true) 또는 이미 매치를 시작한 멤버: inProgress 필드
                - currentRound: 다음에 진행할 라운드 번호
                - lastHistory: 가장 최근에 기록된 매치 결과. 라운드 전환 직후에는 currentRound와 다른 라운드의 매치일 수 있음. 매치 기록이 없으면 null
                - remainingItems: 현재 라운드에서 아직 대결하지 않은 생존 아이템 목록, 가격 오름차순. 진행률 표시용이며 페어링에는 쓰지 않는다
                - currentMatch: 서버가 브래킷에서 파생한 "지금 치를 매치"(first · second 아이템). 클라이언트는 이걸 그대로 그리고 페어링·셔플을 하지 않는다.
                  POST /tournaments/{id}/matches 에 이 조합을 그대로 되돌려주면 된다.
                  정상 흐름에서는 항상 존재한다. 이 이관 배포 시점에 이미 IN_PROGRESS 였던 토너먼트처럼 기록과 서버 브래킷이 어긋난 경우에만 빠지는데,
                  그때는 `currentMatch: null` 이 아니라 **키 자체가 응답에서 생략**된다 (이 API 의 모든 nullable 필드가 같은 규약)
                - 첫 라운드는 인원이 2의 거듭제곱이 되도록 정규화된다 (예: 25명 → 9매치·부전승 7 → 16강 → 8강 → 4강 → 결승).
                  따라서 currentRound 는 항상 16 · 8 · 4 · 2 처럼 2의 거듭제곱으로 흐르며(첫 라운드만 예외), 부전승은 첫 라운드에서만 발생한다
              - 아직 매치를 시작하지 않은 멤버(isOwner=false): pending 필드 (ROOT 아이템·참여자 목록)
                - pending.ownerStarted = true. 클라이언트는 이 플래그로 "주최자 대기" vs "주최자 시작 완료·지금 시작하세요" UI 를 분기한다
                - pending.inviteCode, pending.inviteExpiresAt 은 null (초대 기간 종료)
            - COMPLETED: completed 필드
              - result: 1위부터 최대 4위까지 순위 아이템 목록
              - isGroupTournament: 소셜(그룹) 토너먼트 여부. 참여자 2명 이상이면 true(완료 무관). 클라이언트는 이 값으로 "전체 결과 보기" 배너를 노출한다 - 첫 완주자가 누구든 새로고침 없이 배너가 보인다.
              - hasGroupResult: 그룹 결과 조회 가능 여부. 완료한 플레이어가 2명 이상이면 true. 클라이언트는 이 값으로 배너 활성/비활성(다른 사람 결과가 아직 없으면 empty state)을 가른다.
              - canAddItem: 결과 화면에서 아이템 담기(위시/링크/이미지)가 가능하면 true. 이 토너먼트의 참여자(주최자·초대 멤버·플레이 링크 참여자)면 true.
            나머지 필드는 응답에 포함되지 않는다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "토너먼트 조회 성공",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "401",
                description = "미인증 (JWT 토큰 없음 또는 유효하지 않음)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "403",
                description = "권한 없음 (토너먼트 참여자가 아님)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "404",
                description = "토너먼트를 찾을 수 없음",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
        ],
    )
    fun getTournamentById(
        @Parameter(hidden = true) userId: UUID,
        @Parameter(description = "토너먼트 ID", example = "1") tournamentId: Long,
    ): ApiResponseBody<TournamentDetailResponse>

    @Operation(
        summary = "초대 링크로 참여 화면 진입",
        description = """
            초대 링크 직접 접근 시 tournamentId 만으로 토너먼트 정보(이름·아이템 수·참여자 수)와 현재 유저의 참여 여부(joined)를 반환한다.
            인증은 선택 — 토큰이 있으면 그 유저 기준 joined 를 계산하고, 없으면 joined=false 로 내린다.
            joined=true 면 이미 참여한 토너먼트이므로 클라이언트는 참여 절차 없이 토너먼트 화면으로 바로 진입할 수 있다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "조회 성공 (토너먼트 이름·아이템 수·참여자 수·현재 유저 참여 여부(joined) 반환)",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "토너먼트를 찾을 수 없음",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
            ApiResponse(
                responseCode = "409",
                description = "상태 충돌 (PENDING이 아닌 토너먼트 · 초대 링크 만료)",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
        ],
    )
    fun getInvitePreview(
        @Parameter(hidden = true) userId: UUID?,
        @Parameter(description = "토너먼트 ID", example = "1") tournamentId: Long,
    ): ApiResponseBody<TournamentInvitePreviewResponse>

    @Operation(
        summary = "초대 코드로 참여 화면 진입",
        description = """
            홈 다이얼로그에서 6자리 코드만 입력하는 경로 전용 — tournamentId 없이 코드만으로 조회한다.
            코드가 유효하면 tournamentId·이름·아이템 수·참여자 수와 현재 유저의 참여 여부(joined)를 반환한다.
            인증은 선택 — 토큰이 있으면 그 유저 기준 joined 를 계산하고, 없으면 joined=false 로 내린다.
            이후 /join 또는 /join/guest 호출 시 응답의 tournamentId를 사용하면 된다.
            코드는 토너먼트 상태에 따라 다른 문을 연다. 대기실(PENDING)이면 합류 미리보기이고, 주최자가 자기 플레이를
            완주했으면 플레이 생성(`POST /from-play-code`) 미리보기다. 완주한 토너먼트는 공유 링크가 유효해야 보인다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "조회 성공 (tournamentId·토너먼트 이름·아이템 수·참여자 수·현재 유저 참여 여부(joined) 반환)",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
            ApiResponse(
                responseCode = "400",
                description = "잘못된 요청 (코드에 해당하는 토너먼트 없음)",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "주최자가 완주했지만 공유 링크를 만들지 않음 — code: TOURNAMENT-026",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
            ApiResponse(
                responseCode = "409",
                description = "상태 충돌 (시작했지만 주최자가 아직 완주하지 않음 · 초대 링크 만료 · 완주한 토너먼트의 공유 링크 만료)",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
        ],
    )
    fun getInvitePreviewByCode(
        @Parameter(hidden = true) userId: UUID?,
        @Parameter(description = "초대 코드 (영어 대문자 3자리 + 숫자 3자리)", example = "ABC123") code: String,
    ): ApiResponseBody<TournamentInvitePreviewResponse>

    @Operation(
        summary = "플레이 링크로 참여 화면 진입",
        description = "플레이 링크가 유효한 토너먼트의 정보(이름, 아이템 수, 만료 시간)를 반환한다. 인증 불필요.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "조회 성공",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "토너먼트를 찾을 수 없음 · 플레이 링크가 생성되지 않은 토너먼트",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
            ApiResponse(
                responseCode = "409",
                description = "플레이 링크 만료",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
        ],
    )
    fun getPlayLinkInfo(
        @Parameter(description = "원본 토너먼트 ID", example = "1") tournamentId: Long,
    ): ApiResponseBody<PlayLinkInfoResponse>

    @Operation(
        summary = "그룹 결과 조회",
        description = """
            완료된 토너먼트의 그룹 결과를 조회한다.
            이 토너먼트를 완주한 모든 참여자(주최자·초대 멤버·플레이 링크 참여자)의 결과를 비교해,
            각 순위의 아이템마다 동일한 결과를 선택한 참여자 정보를 반환한다.

            각 참여자에 isHost 가 포함된다 (이 토너먼트를 만든 주최자면 true). 클라이언트는 프로필 우측 하단에 HostBadge 를 그린다.
            참여자 노출 순서는 바꾸지 않는다 - 선택자 집계 순서 그대로다 (게스트 응답에서 본인만 맨 앞으로 오는 것은 아래 참조).

            **게스트(비회원)가 조회하면 본인을 제외한 참여자의 신원이 서버에서 가려져 내려온다.**
            - 가려진 참여자: `isMasked=true` · `userId=null` · `nickname="?"` · `profileImage`=물음표 아바타 · `isWithdrawn=false` · `isHost=false`
              (`isHost` 도 가린다 - 게스트는 자기를 초대한 사람이 주최자임을 알기 때문에 배지 하나가 그 사람의 신원을 그대로 지목한다)
            - 본인: 가리지 않고 각 아이템 `chosenBy` 의 **맨 앞**에 온다 (`isMasked=false`)
            - 아이템의 이름·가격·이미지·순위는 게스트에게도 그대로 내려간다 (사람만 가린다)

            클라이언트는 `isMasked=true` 인 참여자를 탭했을 때 로그인/회원가입 유도를 띄운다.
            로그인해 회원이 되면 같은 응답이 가려지지 않은 값으로 내려온다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "그룹 결과 조회 성공",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
            ApiResponse(
                responseCode = "401",
                description = "미인증 (JWT 토큰 없음 또는 유효하지 않음)",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
            ApiResponse(
                responseCode = "403",
                description = "권한 없음 (토너먼트 참여자가 아님)",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "토너먼트를 찾을 수 없음",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
            ApiResponse(
                responseCode = "409",
                description = "상태 충돌 (본인 플레이가 아직 완료되지 않음 · 완료한 참여자가 2명 미만)",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
        ],
    )
    fun getGroupResult(
        @Parameter(hidden = true) userId: UUID,
        @Parameter(description = "토너먼트 ID", example = "1") tournamentId: Long,
    ): ApiResponseBody<GroupResultResponse>

    @Operation(
        summary = "토너먼트 참여 닉네임 수정",
        description = """
            이 토너먼트에서만 보이는 참여 닉네임(표시명)을 바꾼다. 유저 프로필 닉네임은 바뀌지 않는다.
            결과·진행 화면의 참여자 이름과 알림(SSE·FCM) 문구에 이 닉네임이 쓰인다.
            요청자가 참여한 토너먼트여야 한다(주최자·멤버·플레이 링크 참여자 모두 그 토너먼트 id 를 넘긴다).
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "닉네임 수정 성공",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
            ApiResponse(
                responseCode = "400",
                description = "잘못된 요청 (닉네임이 1~10자 범위를 벗어남)",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
            ApiResponse(
                responseCode = "401",
                description = "미인증 (JWT 토큰 없음 또는 유효하지 않음)",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
            ApiResponse(
                responseCode = "403",
                description = "권한 없음 (요청자가 이 토너먼트 참여자가 아님)",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
            ApiResponse(
                responseCode = "409",
                description = "닉네임 중복 USER-004 (다른 참여자 · 다른 유저 프로필 닉과 겹침 — 모든 표시명은 전역에서 유일)",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
        ],
    )
    fun updateNickname(
        @Parameter(hidden = true) userId: UUID,
        @Parameter(description = "토너먼트 ID", example = "1") tournamentId: Long,
        request: UpdateTournamentNicknameRequest,
    ): ApiResponseBody<Unit>

    @Operation(
        summary = "토너먼트 생성",
        description = """
            이름으로 PENDING 상태의 토너먼트를 생성한다.
            응답의 inviteCode 와 inviteExpiresAt 을 친구에게 공유하면 초대 링크가 만료되기 전까지 친구가 참여할 수 있다.
            inviteDurationMinutes 를 생략하면 기본 30분, 최대 1440분(24시간)까지 1분 단위로 설정 가능하다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "201",
                description = "토너먼트 생성 성공",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "400",
                description = "잘못된 요청 (name 미입력 · inviteDurationMinutes 가 1분 미만이거나 24시간 초과)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "401",
                description = "미인증 (JWT 토큰 없음 또는 유효하지 않음)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "403",
                description = "권한 없음 (GUEST 권한으로 접근 불가 · MEMBER 필요 — code: TOURNAMENT-036)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "409",
                description = "탈퇴한 계정 (JWT 는 아직 유효하나 계정이 탈퇴 상태) — code: USER-003",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
        ],
    )
    fun create(
        @Parameter(hidden = true) userId: UUID,
        request: CreateTournamentRequest,
    ): ApiResponseBody<CreateTournamentResponse>

    @Operation(
        summary = "토너먼트 시작",
        description = """
            참여 아이템 목록을 가격 오름차순으로 정렬해 반환한다. 부르는 사람에 따라 두 경로로 갈린다.
            - 주최자: PENDING 토너먼트를 IN_PROGRESS 로 전환하고 자기 플레이를 시작한다.
            - 멤버·게스트: 주최자가 시작한 뒤에만 자기 참여 행을 플레이 시작으로 전환한다. 다시 불러도 같은 결과를 돌려준다(멱등).
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "토너먼트 시작 성공 (가격 오름차순 정렬 아이템 목록 반환)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "400",
                description = "아이템 수 미충족 (최소 2개, 최대 32개)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "401",
                description = "미인증 (JWT 토큰 없음 또는 유효하지 않음)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "403",
                description = "권한 없음 (토너먼트 참여자가 아님)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "404",
                description = "토너먼트를 찾을 수 없음 · 존재하지 않는 아이템 포함",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "409",
                description =
                    "상태 충돌 (PENDING이 아닌 토너먼트 · PROCESSING/FAILED 상품 포함 · " +
                        "값이 비어 있는 미완성 상품 포함 · 가격 정보 없는 상품 포함 · " +
                        "주최자가 아직 시작하지 않음 — 마지막은 멤버 시작 경로)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
        ],
    )
    fun start(
        @Parameter(hidden = true) userId: UUID,
        @Parameter(description = "토너먼트 ID", example = "1") tournamentId: Long,
    ): ApiResponseBody<TournamentStartResponse>

    @Operation(
        summary = "소셜 토너먼트 참여 (인증된 사용자)",
        description = """
            PENDING 상태의 토너먼트에 참여한다. JWT 인증이 필요하다 (GUEST·MEMBER 모두 허용).
            - 링크 직접 접근: inviteCode 생략 가능. 만료 여부만 확인 후 바로 참여.
            - 코드 입력 경로: inviteCode 전달 시 코드 일치 여부도 검증.
            초대 링크가 만료됐거나 이미 참여 중이면 실패한다.
            계정이 없는 비회원은 /join/guest 를 사용한다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "참여 성공",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "400",
                description = "잘못된 요청 (초대 코드 형식 오류 · 코드 불일치 — inviteCode 전달 시에만 발생)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "401",
                description = "미인증 (JWT 토큰 없음 또는 유효하지 않음)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "404",
                description = "토너먼트를 찾을 수 없음",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "409",
                description = "상태 충돌 (PENDING이 아닌 토너먼트 · 초대 링크 만료 · 이미 참여 중 · 참여 인원 초과(최대 8명))",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
        ],
    )
    fun join(
        @Parameter(hidden = true) userId: UUID,
        @Parameter(description = "토너먼트 ID", example = "1") tournamentId: Long,
        request: JoinTournamentRequest,
    ): ApiResponseBody<Unit>

    @Operation(
        summary = "소셜 토너먼트 참여 (비회원 게스트)",
        description = """
            닉네임을 입력해 게스트 계정을 생성하고 토너먼트에 참여한다. 인증 불필요.
            - 링크 직접 접근: inviteCode 생략 가능. 만료 여부만 확인.
            - 코드 입력 경로: inviteCode 전달 시 코드 일치 여부도 검증.
            성공 시 JWT 토큰 쌍과 생성된 사용자 정보가 반환된다.
            토큰 전달 방식은 클라이언트 타입에 따라 다르다 (X-Client-Type 헤더):
            - WEB(기본·미설정): accessToken·refreshToken 은 HttpOnly 쿠키로 전달, body 값은 null.
            - APP(app 명시): accessToken·refreshToken 을 body 로 전달, 쿠키 없음.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "201",
                description = "게스트 계정 생성 및 참여 성공 (WEB=쿠키 전달·body null / APP=body 전달)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "400",
                description =
                    "잘못된 요청 (초대 코드 형식 오류 · 코드 불일치 — inviteCode 전달 시에만 발생 · " +
                        "닉네임 미입력 · 닉네임 10자 초과 · 닉네임이 '탈퇴' 예약 prefix 로 시작)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "404",
                description = "토너먼트를 찾을 수 없음",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "409",
                description = "상태 충돌 (PENDING이 아닌 토너먼트 · 초대 링크 만료 · 닉네임 중복 · 참여 인원 초과(최대 8명))",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
        ],
    )
    @Parameter(
        name = ClientType.HEADER,
        `in` = ParameterIn.HEADER,
        required = false,
        description = "클라이언트 종류. app 이면 body 로 토큰을 받는다(네이티브 secure storage). 그 외·미설정은 HttpOnly 쿠키로 받고 body 토큰은 null(기본, secure by default).",
        schema = Schema(allowableValues = ["web", "app"]),
    )
    fun joinAsGuest(
        @Parameter(description = "토너먼트 ID", example = "1") tournamentId: Long,
        request: JoinTournamentAsGuestRequest,
    ): ApiResponseBody<JoinTournamentAsGuestResponse>

    @Operation(
        summary = "매치 결과 기록",
        description = """
            IN_PROGRESS 상태의 토너먼트에서 한 매치의 결과(승자)를 기록한다.
            currentRound 는 해당 시점에 서버가 기대하는 라운드와 일치해야 한다.

            firstTournamentItemId · secondTournamentItemId 는 서버가 브래킷에서 파생한 조합이어야 한다 —
            GET /tournaments/{id} 의 inProgress.currentMatch 또는 이 API 의 이전 응답 nextMatch 를 그대로 되돌려주면 된다.
            클라이언트가 임의로 구성한 조합은 400 (TOURNAMENT-034) 으로 거부된다. 다만 좌/우 순서와 라운드 내 매치 진행 순서는
            검증하지 않으므로, 뒤집혀 오거나 순서가 달라도 통과한다.

            같은 조합을 같은 승자로 다시 보내면 멱등 성공(중복 기록 없이 200)이고, 다른 승자로 보내면 409 (TOURNAMENT-035) 다.
            결승 매치도 마찬가지다 - 결승을 기록하면 내 참여가 COMPLETED 로 바뀌지만, 같은 결승 매치를 재전송하면
            409 가 아니라 최초 응답과 같은 completed(순위 결과)를 다시 받는다. 응답을 못 받고 재전송하는 경우가 결승에서 가장 흔하다.

            응답의 nullable 필드는 `null` 로 내려가지 않고 **키 자체가 생략**된다 (이 API 공통 규약).
            nextMatch 는 같은 라운드에 남은 다음 매치이며, 라운드가 끝났으면 생략된다 (두 필드가 다 비면 data 는 빈 객체다).
            이때 클라이언트는 GET /tournaments/{id} 를 다시 호출해 다음 라운드를 받는다.
            결승(currentRound=2) 결과 기록 시 completed 에 본인의 순위 결과(1위~최대 4위)가 즉시 담긴다.
            소셜 토너먼트라도 진행 상태는 참여자마다 따로라, 내 결승을 기록하는 즉시 나에게는 COMPLETED 로 보인다.
            다른 참여자의 진행 여부와 무관하게 내 결과는 바로 확인할 수 있다. 소셜 토너먼트면 완주 즉시 isGroupTournament=true 로 "전체 결과 보기" 배너가 노출되고(새로고침 불필요), 전체 그룹 결과는 2명 이상이 완료한 뒤 hasGroupResult=true 로 활성화된다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "매치 결과 기록 성공 (라운드 진행 중: nextMatch 에 다음 매치 · 라운드 종료: 두 필드 생략으로 data 가 빈 객체 · 결승: completed.result 에 순위 아이템 목록)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "400",
                description = "잘못된 요청 (승자가 대결 두 아이템 중 하나가 아님 · 해당 토너먼트에 속하지 않는 아이템 · 현재 진행해야 할 라운드가 아님 · 서버 브래킷에 없는 페어 조합)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "401",
                description = "미인증 (JWT 토큰 없음 또는 유효하지 않음)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "403",
                description = "권한 없음 (토너먼트 참여자가 아님)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "404",
                description = "토너먼트를 찾을 수 없음",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "409",
                description = "상태 충돌 (IN_PROGRESS가 아닌 토너먼트에 기록되지 않은 새 매치 전송 · 이미 탈락한 아이템 · 이미 결과가 기록된 대결에 다른 승자 전송)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
        ],
    )
    fun recordMatch(
        @Parameter(hidden = true) userId: UUID,
        @Parameter(description = "토너먼트 ID", example = "1") tournamentId: Long,
        request: RecordMatchRequest,
    ): ApiResponseBody<RecordMatchResponse>

    @Operation(
        summary = "플레이 링크 생성 (멱등)",
        description = """
            완료된 토너먼트의 플레이 링크를 만들거나 갱신한다. 토너먼트 소유자만 호출 가능.
            플레이 링크를 통해 친구들이 같은 토너먼트에 참여해 각자 자기 판을 진행할 수 있다
            (친구마다 이 토너먼트의 참여자가 하나씩 늘어난다 - `POST /{sourceTournamentId}/from-play-link`).

            **호출 시점의 링크 상태에 따라 동작이 갈리고, 항상 200을 반환한다.**
            - 아직 만든 적 없으면: 새로 만든다 (생성 시점 + 14일).
            - 유효한 링크가 있으면: **연장하지 않고** 기존 만료시각을 그대로 돌려준다. 공유 버튼을 다시 누른 것만으로
              노출 기간이 늘어나는 것을 막기 위함이다.
            - 만료된 링크가 있으면: 새 14일로 갱신한다.

            플레이 링크는 **토너먼트당 하나**이고 주소가 `tournamentId` 로 결정된다(별도 토큰 없음) - 같은
            토너먼트에 여러 개를 만들 수 없다. 링크로 들어오는 인원도 초대·코드와 같은 정원 제한을 받는다.

            초대 코드(시작 전, 참여자로 합류)와는 다른 기능이며 동시에 열리는 구간이 없다 - 초대 코드는
            PENDING 상태에서만, 플레이 링크는 주최자가 자기 플레이를 완주한 뒤에만 쓸 수 있다. 토너먼트 상태(status)는 주최자 완주 뒤에도 IN_PROGRESS 일 수 있어 상태가 아니라 주최자 참여 행으로 판정한다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "플레이 링크 생성 또는 갱신 성공 (playLinkExpiresAt 반환) - 매 호출이 이 응답이다",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
            ApiResponse(
                responseCode = "401",
                description = "미인증 (JWT 토큰 없음 또는 유효하지 않음)",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
            ApiResponse(
                responseCode = "403",
                description = "권한 없음 (토너먼트 참여자가 아님 · 소유자가 아님)",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "토너먼트를 찾을 수 없음",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
            ApiResponse(
                responseCode = "409",
                description = "상태 충돌 (주최자가 아직 자기 플레이를 완주하지 않음)",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
        ],
    )
    fun createPlayLink(
        @Parameter(hidden = true) userId: UUID,
        @Parameter(description = "토너먼트 ID", example = "1") tournamentId: Long,
    ): ApiResponseBody<LocalDateTime>

    @Operation(
        summary = "플레이 링크로 토너먼트 진행",
        description = """
            플레이 링크가 유효한 토너먼트에 요청자를 참여자로 추가하고, 그 토너먼트 id 를 반환한다.
            새 토너먼트를 만들지 않는다 - 반환 id 는 항상 경로의 원본 토너먼트 id 다.
            참여는 아직 시작 전 상태라, 이어서 POST /tournaments/{id}/start 로 자기 판을 시작한다.
            idempotent: 이미 참여 중이면(초대 멤버·재진입) 새로 추가하지 않고 같은 id 를 반환한다
            (링크 만료와 무관하게 이어서 진행 가능).
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "참여 시작 또는 기존 참여 이어하기 (원본 tournamentId 반환)",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
            ApiResponse(
                responseCode = "401",
                description = "미인증 (JWT 토큰 없음 또는 유효하지 않음)",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "토너먼트를 찾을 수 없음 · 플레이 링크가 생성되지 않은 토너먼트",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
            ApiResponse(
                responseCode = "409",
                description = "상태 충돌 (플레이 링크 만료 `TOURNAMENT-027` · 참여 인원 초과 `TOURNAMENT-030`)",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
        ],
    )
    fun createFromPlayLink(
        @Parameter(hidden = true) userId: UUID,
        @Parameter(description = "원본 토너먼트 ID", example = "1") sourceTournamentId: Long,
    ): ApiResponseBody<Long>

    @Operation(
        summary = "완료된 토너먼트에 코드로 입장",
        description =
            "완료된 토너먼트의 6자리 코드로 입장해 내 플레이를 만든다. `from-play-link` 와 같은 일을 하고 **주소만 다르다** " +
                "- 링크를 열 수 없는 자리(오프라인에서 코드를 불러주는 등)를 위한 경로다.\n\n" +
                "**코드는 초대 코드와 같은 값이다.** 토너먼트 생성 시점에 발급돼 바뀌지 않으며, 상태에 따라 다른 문을 연다.\n\n" +
                "| 대상 | 이 API | 게이트 |\n" +
                "|---|---|---|\n" +
                "| PENDING | **409** (`TOURNAMENT-023`) — `POST /{id}/join` 을 쓴다 | - |\n" +
                "| 시작했지만 주최자가 아직 완주하지 않음 | **409** (`TOURNAMENT-023`) | - |\n" +
                "| 주최자가 완주함 | 플레이 생성 | 공유 링크가 유효해야 한다 (14일) |\n\n" +
                "**공유를 켜지 않은 토너먼트는 코드로도 열리지 않는다** - 주최자가 공유 링크를 만들지 않았으면 404(`TOURNAMENT-026`). " +
                "주최자가 나가 링크가 무효화되면 만료와 같이 409(`TOURNAMENT-027`)다. 만료 후 재발급하면 코드도 함께 살아난다.\n\n" +
                "**멱등하다.** 이미 참여 중이면 기존 토너먼트 id 를 그대로 200 으로 돌려준다(링크 만료와 무관). " +
                "그래서 201 이 아니라 200 이다.\n\n" +
                "**정원은 8명이다**(주최자 포함, 참여 행 기준). 초대·링크·코드가 같은 제한을 공유한다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "입장 성공 또는 기존 참여 반환 (플레이할 토너먼트 id)",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
            ApiResponse(
                responseCode = "400",
                description = "코드 형식이 아님 (영문 대문자 3 + 숫자 3) · 해당 코드의 토너먼트 없음(`TOURNAMENT-020`)",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
            ApiResponse(
                responseCode = "401",
                description = "미인증 (JWT 토큰 없음 또는 유효하지 않음)",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "공유 링크가 만들어지지 않은 토너먼트 (`TOURNAMENT-026`)",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
            ApiResponse(
                responseCode = "409",
                description =
                    "완료되지 않은 토너먼트(`TOURNAMENT-023`) · 공유 링크 만료(`TOURNAMENT-027`) · " +
                        "참여 인원 초과(`TOURNAMENT-030`)",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
        ],
    )
    fun createFromPlayCode(
        @Parameter(hidden = true) userId: UUID,
        request: CreateFromPlayCodeRequest,
    ): ApiResponseBody<Long>

    @Operation(
        summary = "친구 초대 마감 시각 수정",
        description = "PENDING 상태 토너먼트의 초대 마감 시각을 재설정한다. 주최자만 가능. 현재 시각 이후, 최대 24시간 이내의 절대 시각을 입력한다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "수정 성공 (새 inviteExpiresAt 반환)",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
            ApiResponse(
                responseCode = "400",
                description = "잘못된 요청 (newExpiresAt 가 현재 시각 이전 · 24시간 초과)",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
            ApiResponse(
                responseCode = "401",
                description = "미인증 (JWT 토큰 없음 또는 유효하지 않음)",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
            ApiResponse(
                responseCode = "403",
                description = "권한 없음 (토너먼트 참여자가 아님 · 소유자가 아님)",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "토너먼트를 찾을 수 없음",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
            ApiResponse(
                responseCode = "409",
                description = "상태 충돌 (PENDING이 아닌 토너먼트)",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
        ],
    )
    fun updateInviteExpiry(
        @Parameter(hidden = true) userId: UUID,
        @Parameter(description = "토너먼트 ID", example = "1") tournamentId: Long,
        request: UpdateInviteDurationRequest,
    ): ApiResponseBody<LocalDateTime>

    @Operation(
        summary = "토너먼트 삭제",
        description = """
            소유자만 호출 가능하다. 주최자 본인의 플레이 진행에 따라 갈린다.
            - 아직 플레이 전: 토너먼트·아이템·참여 행을 모두 지운다.
            - 플레이 중: 삭제할 수 없다(409).
            - 플레이 완주 뒤: 주최자만 목록에서 빠지고 플레이 링크가 무효화된다. 토너먼트 데이터(아이템·히스토리)는 유지되어 다른 참여자들은 계속 접근할 수 있다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "토너먼트 삭제 성공",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "401",
                description = "미인증 (JWT 토큰 없음 또는 유효하지 않음)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "403",
                description = "권한 없음 (토너먼트 참여자가 아님 · 토너먼트 소유자가 아님)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "404",
                description = "토너먼트를 찾을 수 없음",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "409",
                description = "상태 충돌 (주최자 본인 플레이가 진행 중)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
        ],
    )
    fun deleteTournament(
        @Parameter(hidden = true) userId: UUID,
        @Parameter(description = "토너먼트 ID", example = "1") tournamentId: Long,
    ): ApiResponseBody<Unit>
}
