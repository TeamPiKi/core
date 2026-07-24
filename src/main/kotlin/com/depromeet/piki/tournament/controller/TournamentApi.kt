package com.depromeet.piki.tournament.controller

import com.depromeet.piki.auth.web.ClientType
import com.depromeet.piki.common.response.ApiResponseBody
import com.depromeet.piki.tournament.controller.dto.CreateTournamentRequest
import com.depromeet.piki.tournament.controller.dto.CreateTournamentResponse
import com.depromeet.piki.tournament.controller.dto.GroupResultResponse
import com.depromeet.piki.tournament.controller.dto.JoinTournamentAsGuestRequest
import com.depromeet.piki.tournament.controller.dto.JoinTournamentAsGuestResponse
import com.depromeet.piki.tournament.controller.dto.UpdateInviteDurationRequest
import com.depromeet.piki.tournament.controller.dto.JoinTournamentRequest
import com.depromeet.piki.tournament.controller.dto.PlayLinkInfoResponse
import com.depromeet.piki.tournament.controller.dto.RecordMatchRequest
import com.depromeet.piki.tournament.controller.dto.TournamentDetailResponse
import com.depromeet.piki.tournament.controller.dto.TournamentInvitePreviewResponse
import com.depromeet.piki.tournament.controller.dto.TournamentStartResponse
import com.depromeet.piki.tournament.controller.dto.TournamentSummaryResponse
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
            각 항목의 thumbnailUrls 는 카드 대표 썸네일 URL 배열이다 — 최근 등록 아이템 중 이미지가 준비된(READY) 것 최대 2장.
            이미지가 준비된 아이템이 없으면 빈 배열이며, limit 유무와 무관하게 항상 포함된다.
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
                description = "잘못된 요청 (limit 이 1 미만 · limit 이 정수가 아님)",
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
        @Parameter(description = "조회 개수 제한 (최근순 상위 N, 생략 시 전체). 1 이상.", example = "3")
        limit: Int?,
    ): ApiResponseBody<List<TournamentSummaryResponse>>

    @Operation(
        summary = "토너먼트 단건 조회",
        description = """
            토너먼트 ID로 상태에 따른 상세 정보를 조회한다.
            isOwner: 요청자가 해당 토너먼트 인스턴스(ROOT 또는 CLONE)의 소유자이면 true.
            isRoot: 소셜 토너먼트 원본(ROOT)이면 true, 멤버·플레이링크용 복사본(CLONE)이면 false. 솔로 토너먼트는 항상 true.
            sourceTournamentId: CLONE 토너먼트이면 원본 ROOT의 id, ROOT이면 null. 게스트는 이 id로 GET /tournaments/{sourceTournamentId}/group-result를 호출한다.
            플레이 링크 공유는 isRoot && isOwner 일 때만 허용된다. isOwner 단독으로 분기하면 CLONE 소유자에게도 공유 버튼이 노출되어 시안 위반이다.
            응답의 status 필드에 따라 포함되는 데이터가 달라진다.
            - PENDING: pending 필드 (아이템 목록, 참여자 목록)
              - 각 아이템에 status 포함 (READY / PENDING / PROCESSING / FAILED). PENDING·PROCESSING 이면 name·price·imageUrl 은 null 이라 응답에서 제외됨
              - 각 아이템에 userId 포함 (해당 아이템을 담은 참여자의 userId)
              - 각 참여자에 itemCount 포함 (해당 참여자가 이 토너먼트에 담은 아이템 수)
              - pending.ownerStarted = false
            - IN_PROGRESS: 요청자 역할에 따라 두 가지 응답이 있다.
              - 소유자(isOwner=true) 또는 이미 매치를 시작한 멤버: inProgress 필드
                - currentRound: 다음에 진행할 라운드 번호
                - lastHistory: 가장 최근에 기록된 매치 결과. 라운드 전환 직후에는 currentRound와 다른 라운드의 매치일 수 있음. 매치 기록이 없으면 null
                - remainingItems: 현재 라운드에서 아직 대결하지 않은 생존 아이템 목록, 가격 오름차순. 이 순서가 클라이언트의 매치 페어링 순서([0]vs[1], [2]vs[3] …)를 결정함
              - 아직 매치를 시작하지 않은 멤버(isOwner=false): pending 필드 (ROOT 아이템·참여자 목록)
                - pending.ownerStarted = true. 클라이언트는 이 플래그로 "주최자 대기" vs "주최자 시작 완료·지금 시작하세요" UI 를 분기한다
                - pending.inviteCode, pending.inviteExpiresAt 은 null (초대 기간 종료)
            - COMPLETED: completed 필드
              - result: 1위부터 최대 4위까지 순위 아이템 목록
              - hasGroupResult: 참여자 2명 이상이면 true. 클라이언트는 이 값으로 친구 토너먼트 결과 보기 버튼을 제어한다.
              - canAddItem: 결과 화면에서 아이템 담기(위시/링크/이미지)가 가능하면 true. ROOT 소유자·소셜 초대 CLONE 소유자는 true, 플레이링크 CLONE 소유자는 false.
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
        description = "초대 링크 직접 접근 시 tournamentId 만으로 토너먼트 정보(이름·아이템 수·참여자 수)를 반환한다. 인증 불필요.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "조회 성공 (토너먼트 이름·아이템 수·참여자 수 반환)",
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
        @Parameter(description = "토너먼트 ID", example = "1") tournamentId: Long,
    ): ApiResponseBody<TournamentInvitePreviewResponse>

    @Operation(
        summary = "초대 코드로 참여 화면 진입",
        description = """
            홈 다이얼로그에서 6자리 코드만 입력하는 경로 전용 — tournamentId 없이 코드만으로 조회한다.
            코드가 유효하면 tournamentId·이름·아이템 수·참여자 수를 반환한다.
            이후 /join 또는 /join/guest 호출 시 응답의 tournamentId를 사용하면 된다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "조회 성공 (tournamentId·토너먼트 이름·아이템 수·참여자 수 반환)",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
            ApiResponse(
                responseCode = "400",
                description = "잘못된 요청 (코드에 해당하는 토너먼트 없음)",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
            ApiResponse(
                responseCode = "409",
                description = "상태 충돌 (PENDING이 아닌 토너먼트 · 초대 링크 만료)",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
        ],
    )
    fun getInvitePreviewByCode(
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
            원본 토너먼트와 플레이 링크로 복제된 모든 토너먼트의 결과를 비교해,
            각 순위의 아이템마다 동일한 결과를 선택한 참여자 정보를 반환한다.
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
                description = "권한 없음 (토너먼트 참여자가 아님 · 플레이링크로 참여한 복제 토너먼트는 그룹 결과 조회 불가)",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "토너먼트를 찾을 수 없음",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
            ApiResponse(
                responseCode = "409",
                description = "상태 충돌 (COMPLETED가 아닌 토너먼트)",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
        ],
    )
    fun getGroupResult(
        @Parameter(hidden = true) userId: UUID,
        @Parameter(description = "토너먼트 ID", example = "1") tournamentId: Long,
    ): ApiResponseBody<GroupResultResponse>

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
                description = "잘못된 요청 (name 미입력)",
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
    fun create(
        @Parameter(hidden = true) userId: UUID,
        request: CreateTournamentRequest,
    ): ApiResponseBody<CreateTournamentResponse>

    @Operation(
        summary = "토너먼트 시작",
        description = "PENDING 상태의 토너먼트를 IN_PROGRESS 상태로 전환하고, 참여 아이템 목록을 가격 오름차순으로 정렬해 반환한다.",
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
                description = "상태 충돌 (PENDING이 아닌 토너먼트 · PROCESSING/FAILED 상품 포함 · 가격 정보 없는 상품 포함)",
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
            결승(currentRound=2) 결과 기록 시 본인의 순위 결과(1위~최대 4위)가 즉시 반환된다.
            소셜 토너먼트라도 각 인스턴스(ROOT·CLONE)는 해당 인스턴스의 결승이 완료되는 즉시 COMPLETED 로 전환된다.
            다른 참여자의 진행 여부와 무관하게 내 결과는 바로 확인할 수 있으며, 전체 그룹 결과는 2명 이상이 완료한 뒤 hasGroupResult=true 로 활성화된다.
            결승이 아닌 라운드는 data=null 을 반환한다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "매치 결과 기록 성공 (결승이 아닌 라운드: data=null · 결승 라운드: data.result에 순위 아이템 목록)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "400",
                description = "잘못된 요청 (승자가 대결 두 아이템 중 하나가 아님 · 해당 토너먼트에 속하지 않는 아이템 · 현재 진행해야 할 라운드가 아님)",
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
                description = "상태 충돌 (IN_PROGRESS가 아닌 토너먼트 · 이미 탈락한 아이템)",
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
    ): ApiResponseBody<TournamentDetailResponse.CompletedData?>

    @Operation(
        summary = "플레이 링크 생성",
        description = """
            완료된 토너먼트의 플레이 링크를 생성한다. 토너먼트 소유자만 호출 가능.
            플레이 링크를 통해 친구들이 동일한 아이템 구성으로 토너먼트를 진행할 수 있다.
            만료 기간은 생성 시점 + 14일로 고정이며 변경 불가.
            이미 링크가 생성된 경우 409.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "플레이 링크 생성 성공 (playLinkExpiresAt 반환)",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
            ApiResponse(
                responseCode = "401",
                description = "미인증 (JWT 토큰 없음 또는 유효하지 않음)",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
            ApiResponse(
                responseCode = "403",
                description = "권한 없음 (토너먼트 참여자가 아님 · 소유자가 아님 · 플레이 링크로 참여한 토너먼트는 플레이 링크 생성 불가)",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "토너먼트를 찾을 수 없음",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
            ApiResponse(
                responseCode = "409",
                description = "상태 충돌 (COMPLETED가 아닌 토너먼트 · 플레이 링크가 이미 생성됨)",
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
            플레이 링크가 유효한 토너먼트와 동일한 아이템 구성으로 새 토너먼트를 생성한다.
            생성된 토너먼트는 PENDING 상태이며 아이템이 미리 복사되어 있어 바로 시작할 수 있다.
            idempotent: 같은 사용자가 같은 원본의 플레이 링크를 다시 호출하면 새로 만들지 않고
            기존 본인 클론의 tournamentId 를 그대로 반환한다 (원본 링크 만료와 무관하게 이어서 진행 가능).
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "복제 토너먼트 생성 또는 기존 본인 클론 반환 (tournamentId 반환)",
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
                description = "상태 충돌 (플레이 링크 만료)",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
        ],
    )
    fun createFromPlayLink(
        @Parameter(hidden = true) userId: UUID,
        @Parameter(description = "원본 토너먼트 ID", example = "1") sourceTournamentId: Long,
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
        description = "토너먼트를 소유자 목록에서 제거한다. 소유자만 호출 가능하며, IN_PROGRESS 상태에서는 불가. 토너먼트 데이터(아이템·히스토리)는 유지되어 다른 참여자들은 계속 접근할 수 있다.",
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
                description = "상태 충돌 (IN_PROGRESS 토너먼트는 삭제 불가)",
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
