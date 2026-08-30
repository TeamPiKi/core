package com.depromeet.piki.wishlist.controller

import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.common.storage.ImageStorageException
import com.depromeet.piki.item.domain.Item
import com.depromeet.piki.item.service.ItemParsingService
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.service.ProductSnapshot
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.StubImageStorage
import com.depromeet.piki.support.uuidToBytes
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.wishlist.controller.dto.WishlistUpdateRequest
import com.depromeet.piki.wishlist.service.WishPersistenceService
import org.hamcrest.Matchers.nullValue
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import kotlin.test.assertEquals

// 조회·수정·삭제 contract 검증. 이 시나리오들의 본질은 "완성된 위시가 있을 때의 동작"이라
// 등록(비동기) 경로를 거치지 않고 seedReadyWish 로 READY 상태를 시딩한다.
// 등록 PENDING 응답·파싱 전이는 WishlistRegisterAsyncIntegrationTest 가 검증한다.
@Transactional
class WishlistCrudIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var wishPersistenceService: WishPersistenceService

    @Autowired
    private lateinit var itemParsingService: ItemParsingService

    @Autowired
    private lateinit var stubImageStorage: StubImageStorage

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var jwtProvider: JwtProvider

    private fun insertMember(userId: UUID) {
        jdbcTemplate.update(
            "INSERT INTO users (id, nickname, identity_type, created_at, updated_at) VALUES (?, ?, ?, NOW(6), NOW(6))",
            uuidToBytes(userId),
            userId.toString().take(10),
            "MEMBER",
        )
    }

    private fun memberToken(userId: UUID): String = jwtProvider.generateAccessToken(userId, IdentityType.MEMBER)

    // 위시리스트는 회원 전용 — 게스트는 인증(authenticated)은 통과하나 WishlistService.requireMember 가 도메인 계약으로 막는다.
    // 가드가 findById 로 identityType 을 확인하므로 게스트 유저 행이 실제로 존재해야 403(없으면 404)이 나온다.
    private fun insertGuest(userId: UUID) {
        jdbcTemplate.update(
            "INSERT INTO users (id, nickname, identity_type, created_at, updated_at) VALUES (?, ?, ?, NOW(6), NOW(6))",
            uuidToBytes(userId),
            userId.toString().take(10),
            "GUEST",
        )
    }

    private fun guestToken(userId: UUID): String = jwtProvider.generateAccessToken(userId, IdentityType.GUEST)

    private fun buildMockMvc(): MockMvc =
        MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

    // 조회·수정·삭제 시나리오의 데이터 시딩. 등록 API(비동기)를 거치지 않고 영속화 빈으로 READY item+wish 를
    // 바로 만든다 — 이 테스트들의 관심사는 "완성된 위시가 있을 때"이지 등록 흐름이 아니기 때문.
    // item 은 정체성(link)만 들고, 표시값·상태는 활성 snapshot 이 보유한다(4a). 등록은 PENDING(작업 큐 적재)으로 시작하므로
    // 디스패처 claim(claimDuePending)을 재현해 PROCESSING 으로 전이한 뒤 markReady 로 추출값을 채운다 — 등록 후 파싱 성공과 동형이다.
    private fun seedReadyWish(
        userId: UUID,
        url: String,
        name: String,
        price: Int? = 10_000,
        currency: String? = "KRW",
        imageUrl: String? = "https://img.example.com/a.png",
        // 기본 null(출처 미기록) — 표시값 파생 후보(기계 READY)로 만들려면 "STRUCTURED" 를 명시한다.
        extractionMethod: String? = null,
    ): Long {
        val result = wishPersistenceService.persist(userId, Item(ProductLink.parse(url)))
        itemParsingService.claimDuePending(100)
        // 이 시딩은 워커를 태우지 않고 전이만 재현한다 — 실행이 없었으므로 attempt 는 집기 직후 값(0) 그대로이고,
        // 전이의 fencing 토큰도 그 값이다. (실행까지 재현하는 흐름은 WishlistRegisterAsyncIntegrationTest 가 덮는다.)
        itemParsingService.markExtracted(
            result.snapshot.getId(),
            ProductSnapshot(
                link = ProductLink.parse(url),
                name = name,
                price = price,
                currency = currency,
                imageUrl = imageUrl,
                extractionMethod = extractionMethod,
            ),
            expectedAttempt = 0,
        )
        return result.wish.getId()
    }

    // FAILED 상태 item+wish 시딩 — 추출 실패 항목을 사용자가 직접 보정하는 시나리오용.
    // 등록(PENDING)→디스패처 claim(PROCESSING)→markFailed(FAILED) 순으로 전이시켜 영속화한다(등록 후 파싱 실패와 동형).
    private fun seedFailedWish(
        userId: UUID,
        url: String,
    ): Long {
        val result = wishPersistenceService.persist(userId, Item(ProductLink.parse(url)))
        itemParsingService.claimDuePending(100)
        // 이 시딩은 워커를 태우지 않고 전이만 재현한다 — 실행이 없었으므로 attempt 는 집기 직후 값(0) 그대로이고,
        // 전이의 fencing 토큰도 그 값이다. (실행까지 재현하는 흐름은 WishlistRegisterAsyncIntegrationTest 가 덮는다.)
        itemParsingService.markFailed(result.snapshot.getId(), expectedAttempt = 0)
        return result.wish.getId()
    }

    // PROCESSING 상태 item+wish 시딩 — 파싱 중 항목에 클라이언트가 끼어드는(409) 시나리오용.
    // 등록(PENDING) 후 디스패처 claim 만 재현해 PROCESSING 까지 전이하고(워커 미제출) 그 상태에 멈춰 둔다.
    private fun seedProcessingWish(
        userId: UUID,
        url: String,
    ): Long {
        val result = wishPersistenceService.persist(userId, Item(ProductLink.parse(url)))
        itemParsingService.claimDuePending(100)
        return result.wish.getId()
    }

    @Test
    fun `게스트가 URL 로 위시 등록을 시도하면 403 과 회원 전용 안내가 반환된다`() {
        // 게스트 토큰은 Security authenticated() 를 통과하지만, WishlistService 가 회원 전용 계약으로 막아
        // generic 권한없음(detail 없음)이 아니라 "회원만 이용 가능" 이라는 구체 사유를 detail 로 내려준다.
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertGuest(userId)
        val body = objectMapper.writeValueAsString(mapOf("url" to "https://shop.example.com/products/1"))

        mockMvc
            .perform(
                post("/api/v1/wishlists")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${guestToken(userId)}")
                    .content(body),
            ).andExpect(status().isForbidden)
            // wish 도메인 이관(#797)으로 guestCannotUseWishlist 가 도메인 code(WISH-001)를 싣는다.
            .andExpect(jsonPath("$.code").value("WISH-001"))
            .andExpect(jsonPath("$.detail").value("위시리스트는 회원만 이용할 수 있어요."))
            .andExpect(jsonPath("$.data").value(nullValue()))
    }

    @Test
    fun `게스트가 위시리스트를 조회하면 403 과 회원 전용 안내가 반환된다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertGuest(userId)

        mockMvc
            .perform(
                get("/api/v1/wishlists")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${guestToken(userId)}"),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.detail").value("위시리스트는 회원만 이용할 수 있어요."))
    }

    @Test
    fun `탈퇴한 회원의 살아있는 access token 으로 위시리스트를 조회하면 409 가 반환된다`() {
        // 탈퇴 시 access token 은 denylist 로 막히지만, 그 무효화가 부분 실패하면(#689) tombstone 이 서비스까지 닿는다.
        // 회원 가드가 identityType 만 보면 탈퇴 회원이 MEMBER 인 채로 통과해버리므로, 활성 여부까지 함께 끊는다.
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        jdbcTemplate.update("UPDATE users SET deleted_at = NOW(6) WHERE id = ?", uuidToBytes(userId))

        mockMvc
            .perform(
                get("/api/v1/wishlists")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}"),
            ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("USER-003"))
            .andExpect(jsonPath("$.detail").value("탈퇴한 계정이에요."))
    }

    @Test
    fun `게스트는 멱등 삭제 경로보다 회원 가드가 먼저 걸려 403 이 반환된다`() {
        // 회원 가드는 소유권·존재 검증(멱등 no-op)보다 먼저 돈다 — 없는 위시 삭제도 게스트면 200 이 아니라 403.
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertGuest(userId)

        mockMvc
            .perform(
                delete("/api/v1/wishlists/1")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${guestToken(userId)}"),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.detail").value("위시리스트는 회원만 이용할 수 있어요."))
    }

    @Test
    fun `url 이 빈 문자열이면 Bean Validation 이 먼저 걸러 400 COMMON-INVALID-INPUT 이 반환된다`() {
        // 빈 링크에는 LINK code 가 없다 — 요청 DTO 의 @NotBlank 가 컨트롤러 진입 전에 걸러
        // MethodArgumentNotValidException → 공통 4xx code 로 나가고, ProductLink.parse 의 빈 값 분기에는
        // 닿지 않는다. 그 분기는 계약이 아닌 불변식(require)으로만 남는다.
        // detail 은 @NotBlank message 라 사용자에겐 도메인 문구와 동일하게 보인다.
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        val body = objectMapper.writeValueAsString(mapOf("url" to ""))

        mockMvc
            .perform(
                post("/api/v1/wishlists")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}")
                    .content(body),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("COMMON-INVALID-INPUT"))
            .andExpect(jsonPath("$.detail").value("링크를 입력해 주세요."))
    }

    @Test
    fun `https 가 아닌 url 은 400 LINK-002 로 거부된다`() {
        // scheme 분기 망라는 ProductLinkTest(단위)가 맡고, 여기서는 그 거부가 어떤 wire code·detail 로
        // 나가는지만 고정한다 — @NotBlank 를 통과한 뒤 ProductLink.parse 가 던지는 경로라 빈 링크와 갈린다.
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        val body = objectMapper.writeValueAsString(mapOf("url" to "http://shop.example.com/products/42"))

        mockMvc
            .perform(
                post("/api/v1/wishlists")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}")
                    .content(body),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("LINK-002"))
            .andExpect(jsonPath("$.detail").value("https 링크만 등록할 수 있어요."))
    }

    @Test
    fun `잘못된 형식의 url 은 400 BAD_REQUEST 로 응답되며 detail 에 원본 url 이 새지 않는다`() {
        // GlobalExceptionHandler 가 IllegalArgumentException_message 를 응답 detail 에 그대로 박는 구조라
        // ProductLink_parse 가 원본을 메시지에 담으면 쿼리스트링 토큰이 클라이언트 응답으로 새어 나간다.
        // URL 형식 위반은 등록 전(ProductLink.parse) 동기로 거르므로 백그라운드 파싱에 닿지 않는다.
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        val rawWithSecret = "data:text/html,<token=SHOULD_NOT_LEAK>"
        val body = objectMapper.writeValueAsString(mapOf("url" to rawWithSecret))

        mockMvc
            .perform(
                post("/api/v1/wishlists")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}")
                    .content(body),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("LINK-001"))
            .andExpect(jsonPath("$.detail").value("올바른 링크 형식이 아니에요. 다시 확인해 주세요."))
    }

    @Test
    fun `위시리스트가 비어 있으면 빈 배열과 hasNext=false 를 반환한다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)

        mockMvc
            .perform(
                get("/api/v1/wishlists")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(0))
            .andExpect(jsonPath("$.pageResponse.hasNext").value(false))
            .andExpect(jsonPath("$.pageResponse.nextCursor").value(nullValue()))
    }

    @Test
    fun `숫자로 변환할 수 없는 cursor 로 조회하면 400 WISH-003 이 반환된다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)

        mockMvc
            .perform(
                get("/api/v1/wishlists")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}")
                    .param("cursor", "not-a-number"),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("WISH-003"))
            .andExpect(jsonPath("$.detail").value("페이지를 불러오지 못했어요. 새로고침 해주세요."))
    }

    @Test
    fun `본인 위시만 최신 등록순으로 반환하고 다른 유저 wish 는 섞이지 않으며 status 가 함께 내려간다`() {
        val mockMvc = buildMockMvc()
        val ownerId = UUID.randomUUID()
        val otherId = UUID.randomUUID()
        insertMember(ownerId)
        insertMember(otherId)
        seedReadyWish(ownerId, "https://shop.example.com/products/1", "첫 상품")
        val secondWishId = seedReadyWish(ownerId, "https://shop.example.com/products/2", "둘째 상품")
        seedReadyWish(otherId, "https://shop.example.com/products/3", "남의 상품")

        mockMvc
            .perform(
                get("/api/v1/wishlists")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(ownerId)}"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(2))
            // 최신 등록(둘째)이 맨 앞 (id desc)
            .andExpect(jsonPath("$.data[0].wish.id").value(secondWishId))
            .andExpect(jsonPath("$.data[0].item.name").value("둘째 상품"))
            .andExpect(jsonPath("$.data[0].item.status").value("READY"))
            .andExpect(jsonPath("$.data[1].item.name").value("첫 상품"))
            .andExpect(jsonPath("$.pageResponse.hasNext").value(false))
    }

    @Test
    fun `size 보다 많으면 hasNext 와 nextCursor 를 주고 그 cursor 로 다음 페이지를 잇는다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        val firstWishId = seedReadyWish(userId, "https://shop.example.com/products/1", "상품1")
        val secondWishId = seedReadyWish(userId, "https://shop.example.com/products/2", "상품2")
        seedReadyWish(userId, "https://shop.example.com/products/3", "상품3")
        val authHeader = "Bearer ${memberToken(userId)}"

        // 첫 페이지: 최신 2건 + 다음 페이지 존재
        mockMvc
            .perform(
                get("/api/v1/wishlists")
                    .param("size", "2")
                    .header(HttpHeaders.AUTHORIZATION, authHeader),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.pageResponse.hasNext").value(true))
            .andExpect(jsonPath("$.pageResponse.nextCursor").value(secondWishId.toString()))

        // 다음 페이지: cursor 이전(=더 오래된) 1건, 더 이상 없음
        mockMvc
            .perform(
                get("/api/v1/wishlists")
                    .param("size", "2")
                    .param("cursor", secondWishId.toString())
                    .header(HttpHeaders.AUTHORIZATION, authHeader),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].wish.id").value(firstWishId))
            .andExpect(jsonPath("$.pageResponse.hasNext").value(false))
            .andExpect(jsonPath("$.pageResponse.nextCursor").value(nullValue()))
    }

    @Test
    fun `위시를 단건 조회하면 200 과 wish·item 이 함께 반환된다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        val wishId =
            seedReadyWish(
                userId,
                "https://shop.example.com/products/1",
                name = "에어 조던 1 미드",
                price = 119_000,
                currency = "KRW",
                imageUrl = "https://cdn.example.com/p/1.jpg",
            )

        mockMvc
            .perform(
                get("/api/v1/wishlists/$wishId")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.wish.id").value(wishId))
            .andExpect(jsonPath("$.data.item.name").value("에어 조던 1 미드"))
            .andExpect(jsonPath("$.data.item.price").value(119_000))
            .andExpect(jsonPath("$.data.item.currency").value("KRW"))
            .andExpect(jsonPath("$.data.item.imageUrl").value("https://cdn.example.com/p/1.jpg"))
            .andExpect(jsonPath("$.data.item.status").value("READY"))
    }

    @Test
    fun `남의 위시를 단건 조회하면 403 이 반환된다`() {
        val mockMvc = buildMockMvc()
        val ownerId = UUID.randomUUID()
        val otherId = UUID.randomUUID()
        insertMember(ownerId)
        insertMember(otherId)
        val wishId = seedReadyWish(ownerId, "https://shop.example.com/products/1", "내 상품")

        mockMvc
            .perform(
                get("/api/v1/wishlists/$wishId")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(otherId)}"),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `존재하지 않는 위시를 단건 조회하면 404 가 반환된다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)

        mockMvc
            .perform(
                get("/api/v1/wishlists/99999999")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}"),
            ).andExpect(status().isNotFound)
    }

    @Test
    fun `삭제된 위시를 단건 조회하면 404 가 반환된다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        val authHeader = "Bearer ${memberToken(userId)}"
        val wishId = seedReadyWish(userId, "https://shop.example.com/products/1", "지울 상품")

        mockMvc
            .perform(delete("/api/v1/wishlists/$wishId").header(HttpHeaders.AUTHORIZATION, authHeader))
            .andExpect(status().isOk)

        // soft delete 된 위시는 findById(deletedAt IS NULL)에서 제외되어 단건 조회 시 404.
        mockMvc
            .perform(get("/api/v1/wishlists/$wishId").header(HttpHeaders.AUTHORIZATION, authHeader))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `이미 등록 완료(READY)된 위시 item 도 수기 수정하면 200 과 수정된 값으로 표시된다`() {
        // 수기 수정 상시 허용(#825 결정 4) — 기계 버전은 불변이고 수정은 MANUAL 새 버전으로 쌓여 표시가 바뀐다.
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        val authHeader = "Bearer ${memberToken(userId)}"
        val wishId = seedReadyWish(userId, "https://shop.example.com/products/1", "이미 완성된 상품")

        mockMvc
            .perform(
                multipart("/api/v1/wishlists/$wishId")
                    .param("name", "바꾼 이름")
                    .with {
                        it.method = "PATCH"
                        it
                    }.header(HttpHeaders.AUTHORIZATION, authHeader),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.item.name").value("바꾼 이름"))
            .andExpect(jsonPath("$.data.item.status").value("READY"))
    }

    @Test
    fun `남이 같은 링크를 담아 채운 READY 가 있으면 가격 없는 미완성 위시도 이름만 수정이 성공한다`() {
        // 회귀(#1006 과 같은 축): 카드는 표시값(최신 기계 READY)을 그리는데 병합 base 만 포인터면, 화면엔 가격이
        // 떠 있는데 안 보낸 가격이 빈 값에서 병합돼 400 으로 튕긴다. 카드에 보인 가격이 병합되는 것까지 본다.
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        insertMember(userId)
        insertMember(otherUserId)
        val authHeader = "Bearer ${memberToken(userId)}"
        val url = "https://shop.example.com/products/shared-incomplete"

        // 내 등록은 부분 추출(가격 없음)로 미완성 종결. 남의 등록은 같은 링크라 같은 item 에 붙고
        // (미완성은 재사용 대상이 아니라 새 PENDING), 기계 추출(STRUCTURED)이 성공해 표시값 후보가 된다.
        val myWishId = seedReadyWish(userId, url, name = "미완성 이름", price = null, currency = null)
        seedReadyWish(otherUserId, url, name = "남이 채운 이름", price = 89_000, extractionMethod = "STRUCTURED")

        mockMvc
            .perform(
                multipart("/api/v1/wishlists/$myWishId")
                    .param("name", "내가 고친 이름")
                    .with {
                        it.method = "PATCH"
                        it
                    }.header(HttpHeaders.AUTHORIZATION, authHeader),
            ).andExpect(status().isOk)

        mockMvc
            .perform(get("/api/v1/wishlists/$myWishId").header(HttpHeaders.AUTHORIZATION, authHeader))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.item.name").value("내가 고친 이름"))
            .andExpect(jsonPath("$.data.item.price").value(89_000))
    }

    @Test
    fun `남이 채운 READY 가 없으면 가격 없는 미완성 위시의 이름만 수정은 400 으로 거부된다`() {
        // 위 테스트의 대조군 — 표시값이 곧 포인터면 병합해도 가격이 비어 거부돼야 한다(입력 계약 400).
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        val authHeader = "Bearer ${memberToken(userId)}"

        val myWishId =
            seedReadyWish(userId, "https://shop.example.com/products/lone-incomplete", name = "미완성 이름", price = null, currency = null)

        mockMvc
            .perform(
                multipart("/api/v1/wishlists/$myWishId")
                    .param("name", "내가 고친 이름")
                    .with {
                        it.method = "PATCH"
                        it
                    }.header(HttpHeaders.AUTHORIZATION, authHeader),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `파싱 중(PROCESSING)인 위시 item 도 필수값을 채워 수기 수정하면 200 이다 - 병합할 base 가 비면 400`() {
        // 상태 제한이 없다(#825 결정 4). 단 PROCESSING base 는 값이 비어 있어, 일부 필드만 보내면
        // 병합 결과에 필수값이 없어 400(ITEM-003 등)이다 — 상태 충돌(409)이 아니라 입력 계약의 문제다.
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        val authHeader = "Bearer ${memberToken(userId)}"
        val wishId = seedProcessingWish(userId, "https://shop.example.com/products/1")

        // 일부 필드만 — 병합해도 가격·이미지가 없어 400.
        mockMvc
            .perform(
                multipart("/api/v1/wishlists/$wishId")
                    .param("name", "이름만 수정")
                    .with {
                        it.method = "PATCH"
                        it
                    }.header(HttpHeaders.AUTHORIZATION, authHeader),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("ITEM-004"))

        // 필수값을 다 채우면 진행 중이어도 수정된다.
        val image = MockMultipartFile("image", "p.png", "image/png", byteArrayOf(1, 2, 3))
        mockMvc
            .perform(
                multipart("/api/v1/wishlists/$wishId")
                    .file(image)
                    .param("name", "수기 입력")
                    .param("price", "5000")
                    .with {
                        it.method = "PATCH"
                        it
                    }.header(HttpHeaders.AUTHORIZATION, authHeader),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.item.name").value("수기 입력"))
            .andExpect(jsonPath("$.data.item.status").value("READY"))
    }

    @Test
    fun `FAILED 상태인 위시 item 을 직접 수정하면 200 과 함께 status 가 READY 로 복구된다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        val authHeader = "Bearer ${memberToken(userId)}"
        val wishId = seedFailedWish(userId, "https://shop.example.com/products/1")

        val image = MockMultipartFile("image", "p.png", "image/png", byteArrayOf(1, 2, 3))
        mockMvc
            .perform(
                multipart("/api/v1/wishlists/$wishId")
                    .file(image)
                    .param("name", "직접 입력한 이름")
                    .param("price", "50000")
                    .with {
                        it.method = "PATCH"
                        it
                    }.header(HttpHeaders.AUTHORIZATION, authHeader),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.item.name").value("직접 입력한 이름"))
            .andExpect(jsonPath("$.data.item.price").value(50_000))
            // 추출 실패(FAILED) 항목을 직접 보정하면 정상 항목이 된 것이므로 READY 로 복구된다.
            .andExpect(jsonPath("$.data.item.status").value("READY"))
    }

    @Test
    fun `이름 없이 FAILED 위시 item 을 복구하려 하면 400 BAD_REQUEST 가 반환된다`() {
        // 이름 없는 보정은 쓸 수 없는 상품을 READY 로 승격시키므로 막는다 (READY ⟹ name 불변식).
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        val authHeader = "Bearer ${memberToken(userId)}"
        val wishId = seedFailedWish(userId, "https://shop.example.com/products/1")

        mockMvc
            .perform(
                multipart("/api/v1/wishlists/$wishId")
                    .param("price", "50000")
                    .with {
                        it.method = "PATCH"
                        it
                    }.header(HttpHeaders.AUTHORIZATION, authHeader),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("ITEM-003"))
            .andExpect(jsonPath("$.detail").value("상품 이름을 입력해 주세요."))
    }

    @Test
    fun `남의 위시를 수정하면 403 이 반환된다`() {
        val mockMvc = buildMockMvc()
        val ownerId = UUID.randomUUID()
        val otherId = UUID.randomUUID()
        insertMember(ownerId)
        insertMember(otherId)
        val wishId = seedReadyWish(ownerId, "https://shop.example.com/products/1", "내 상품")

        mockMvc
            .perform(
                multipart("/api/v1/wishlists/$wishId")
                    .param("name", "남이 바꾼 이름")
                    .with {
                        it.method = "PATCH"
                        it
                    }.header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(otherId)}"),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `존재하지 않는 위시를 수정하면 404 가 반환된다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)

        mockMvc
            .perform(
                multipart("/api/v1/wishlists/99999999")
                    .param("name", "아무거나")
                    .with {
                        it.method = "PATCH"
                        it
                    }.header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}"),
            ).andExpect(status().isNotFound)
    }

    @Test
    fun `가격을 음수로 수정하면 400 BAD_REQUEST 가 반환된다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        val authHeader = "Bearer ${memberToken(userId)}"
        val wishId = seedReadyWish(userId, "https://shop.example.com/products/1", "상품")

        mockMvc
            .perform(
                multipart("/api/v1/wishlists/$wishId")
                    .param("price", "-1")
                    .with {
                        it.method = "PATCH"
                        it
                    }.header(HttpHeaders.AUTHORIZATION, authHeader),
            ).andExpect(status().isBadRequest)
            // 응답 detail 이 OpenAPI example(WishlistApiExamples 가격 음수)과 같은 형식인지 contract 로 고정.
            .andExpect(jsonPath("$.detail").value(WishlistUpdateRequest.PRICE_MIN_MESSAGE))
    }

    // 전역 카운트는 다른 item 의 비동기 파싱 행에 오염될 수 있어, 대상 wish 가 가리키는 item 으로 한정한다.
    private fun itemIdOf(wishId: Long): Long =
        jdbcTemplate.queryForObject(
            "SELECT s.item_id FROM wishes w JOIN item_snapshots s ON s.id = w.snapshot_id WHERE w.id = ?",
            Long::class.java,
            wishId,
        ) ?: error("wish $wishId 의 item 이 없다")

    private fun countSnapshots(itemId: Long): Long =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM item_snapshots WHERE item_id = ?",
            Long::class.java,
            itemId,
        ) ?: 0L

    @Test
    fun `memo 만 수정하면 200 이고 새 버전을 쌓지 않으며 상세 조회에 메모가 내려간다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        val authHeader = "Bearer ${memberToken(userId)}"
        val wishId = seedReadyWish(userId, "https://shop.example.com/products/1", "상품")
        val itemId = itemIdOf(wishId)
        val snapshotCountBefore = countSnapshots(itemId)

        mockMvc
            .perform(
                multipart("/api/v1/wishlists/$wishId")
                    .param("memo", "  생일 선물 후보  ")
                    .with {
                        it.method = "PATCH"
                        it
                    }.header(HttpHeaders.AUTHORIZATION, authHeader),
            ).andExpect(status().isOk)
            // memo 만 온 요청은 MANUAL 버전을 만들지 않는다 — 표시값(서버 추출)이 그대로다.
            .andExpect(jsonPath("$.data.item.name").value("상품"))
            .andExpect(jsonPath("$.data.item.status").value("READY"))
            // 노출 계약: memo 는 상세 응답 전용 — PATCH 응답에는 없다.
            .andExpect(jsonPath("$.data.memo").doesNotExist())
            .andExpect(jsonPath("$.data.wish.memo").doesNotExist())
        assertEquals(snapshotCountBefore, countSnapshots(itemId))

        // 노출 계약: 목록 응답에도 memo 는 없다.
        mockMvc
            .perform(get("/api/v1/wishlists").header(HttpHeaders.AUTHORIZATION, authHeader))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].memo").doesNotExist())
            .andExpect(jsonPath("$.data[0].wish.memo").doesNotExist())

        // 메모는 상세 응답에만 내려간다. 저장 시 앞뒤 공백은 정리된다.
        mockMvc
            .perform(get("/api/v1/wishlists/$wishId").header(HttpHeaders.AUTHORIZATION, authHeader))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.memo").value("생일 선물 후보"))
    }

    @Test
    fun `빈 문자열 memo 를 보내면 메모가 삭제된다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        val authHeader = "Bearer ${memberToken(userId)}"
        val wishId = seedReadyWish(userId, "https://shop.example.com/products/1", "상품")

        mockMvc
            .perform(
                multipart("/api/v1/wishlists/$wishId")
                    .param("memo", "지울 메모")
                    .with {
                        it.method = "PATCH"
                        it
                    }.header(HttpHeaders.AUTHORIZATION, authHeader),
            ).andExpect(status().isOk)

        mockMvc
            .perform(
                multipart("/api/v1/wishlists/$wishId")
                    .param("memo", "")
                    .with {
                        it.method = "PATCH"
                        it
                    }.header(HttpHeaders.AUTHORIZATION, authHeader),
            ).andExpect(status().isOk)

        mockMvc
            .perform(get("/api/v1/wishlists/$wishId").header(HttpHeaders.AUTHORIZATION, authHeader))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.memo").value(nullValue()))
    }

    @Test
    fun `이름과 memo 를 함께 수정하면 MANUAL 새 버전이 쌓이고 메모도 반영된다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        val authHeader = "Bearer ${memberToken(userId)}"
        val wishId = seedReadyWish(userId, "https://shop.example.com/products/1", "상품")
        val itemId = itemIdOf(wishId)
        val snapshotCountBefore = countSnapshots(itemId)

        mockMvc
            .perform(
                multipart("/api/v1/wishlists/$wishId")
                    .param("name", "바꾼 이름")
                    .param("memo", "같이 저장")
                    .with {
                        it.method = "PATCH"
                        it
                    }.header(HttpHeaders.AUTHORIZATION, authHeader),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.item.name").value("바꾼 이름"))
            .andExpect(jsonPath("$.data.item.source").value("MANUAL"))
        assertEquals(snapshotCountBefore + 1, countSnapshots(itemId))

        mockMvc
            .perform(get("/api/v1/wishlists/$wishId").header(HttpHeaders.AUTHORIZATION, authHeader))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.memo").value("같이 저장"))
    }

    @Test
    fun `memo 없이 name 만 수정하면 기존 메모가 유지된다`() {
        // "들어온 필드만 갱신" 계약의 회귀 방어 — 이름 수정이 개인 메모를 지우면 안 된다.
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        val authHeader = "Bearer ${memberToken(userId)}"
        val wishId = seedReadyWish(userId, "https://shop.example.com/products/1", "상품")

        mockMvc
            .perform(
                multipart("/api/v1/wishlists/$wishId")
                    .param("memo", "지킬 메모")
                    .with {
                        it.method = "PATCH"
                        it
                    }.header(HttpHeaders.AUTHORIZATION, authHeader),
            ).andExpect(status().isOk)

        mockMvc
            .perform(
                multipart("/api/v1/wishlists/$wishId")
                    .param("name", "이름만 수정")
                    .with {
                        it.method = "PATCH"
                        it
                    }.header(HttpHeaders.AUTHORIZATION, authHeader),
            ).andExpect(status().isOk)

        mockMvc
            .perform(get("/api/v1/wishlists/$wishId").header(HttpHeaders.AUTHORIZATION, authHeader))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.item.name").value("이름만 수정"))
            .andExpect(jsonPath("$.data.memo").value("지킬 메모"))
    }

    @Test
    fun `memo 가 100자를 넘으면 400 BAD_REQUEST 가 반환된다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        val authHeader = "Bearer ${memberToken(userId)}"
        val wishId = seedReadyWish(userId, "https://shop.example.com/products/1", "상품")

        mockMvc
            .perform(
                multipart("/api/v1/wishlists/$wishId")
                    .param("memo", "가".repeat(101))
                    .with {
                        it.method = "PATCH"
                        it
                    }.header(HttpHeaders.AUTHORIZATION, authHeader),
            ).andExpect(status().isBadRequest)
            // 응답 detail 이 OpenAPI example(WishlistApiExamples 메모 길이 초과)과 같은 형식인지 contract 로 고정.
            .andExpect(jsonPath("$.detail").value(WishlistUpdateRequest.MEMO_MAX_MESSAGE))
    }

    @Test
    fun `FAILED 위시 item 을 이미지와 함께 보정하면 200 과 갱신된 imageUrl 로 복구된다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        val authHeader = "Bearer ${memberToken(userId)}"
        val wishId = seedFailedWish(userId, "https://shop.example.com/products/1")
        val image = MockMultipartFile("image", "p.png", "image/png", byteArrayOf(1, 2, 3))

        mockMvc
            .perform(
                multipart("/api/v1/wishlists/$wishId")
                    .file(image)
                    .param("name", "직접 입력한 이름")
                    .param("price", "50000")
                    .with {
                        it.method = "PATCH"
                        it
                    }.header(HttpHeaders.AUTHORIZATION, authHeader),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.item.name").value("직접 입력한 이름"))
            // 올린 이미지가 그대로 S3(stub)에 올라가 imageUrl 로 채워지고, FAILED 가 READY 로 복구된다.
            .andExpect(jsonPath("$.data.item.imageUrl").value(startsWith("${StubImageStorage.BASE_URL}/items/")))
            .andExpect(jsonPath("$.data.item.status").value("READY"))
    }

    @Test
    fun `이미지 보정 시 지원하지 않는 형식을 보내면 400 BAD_REQUEST 가 반환된다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        val authHeader = "Bearer ${memberToken(userId)}"
        val wishId = seedFailedWish(userId, "https://shop.example.com/products/1")
        val gif = MockMultipartFile("image", "p.gif", "image/gif", byteArrayOf(1, 2, 3))

        mockMvc
            .perform(
                multipart("/api/v1/wishlists/$wishId")
                    .file(gif)
                    .param("name", "이름")
                    .with {
                        it.method = "PATCH"
                        it
                    }.header(HttpHeaders.AUTHORIZATION, authHeader),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `이미지 보정 시 빈 이미지를 보내면 400 BAD_REQUEST 가 반환된다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        val authHeader = "Bearer ${memberToken(userId)}"
        val wishId = seedFailedWish(userId, "https://shop.example.com/products/1")
        val emptyImage = MockMultipartFile("image", "empty.png", "image/png", ByteArray(0))

        mockMvc
            .perform(
                multipart("/api/v1/wishlists/$wishId")
                    .file(emptyImage)
                    .param("name", "이름")
                    .with {
                        it.method = "PATCH"
                        it
                    }.header(HttpHeaders.AUTHORIZATION, authHeader),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `이미지 보정 중 S3 업로드가 실패하면 502 BAD_GATEWAY 가 반환된다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        val authHeader = "Bearer ${memberToken(userId)}"
        val wishId = seedFailedWish(userId, "https://shop.example.com/products/1")
        val image = MockMultipartFile("image", "p.png", "image/png", byteArrayOf(1, 2, 3))
        // S3 업로드 실패 주입. 공유 stub 이므로 끝에서 직접 기본 동작으로 복원한다.
        stubImageStorage.behavior = { _, _, _ -> throw ImageStorageException.uploadFailed() }

        try {
            mockMvc
                .perform(
                    multipart("/api/v1/wishlists/$wishId")
                        .file(image)
                        .param("name", "이름")
                        // 사전 검증(dry-run)을 통과해야 S3 업로드에 도달한다 — 가격까지 채워 502 경로를 연다.
                        .param("price", "1000")
                        .with {
                            it.method = "PATCH"
                            it
                        }.header(HttpHeaders.AUTHORIZATION, authHeader),
                ).andExpect(status().isBadGateway)
        } finally {
            stubImageStorage.behavior = stubImageStorage.defaultBehavior
        }
    }

    @Test
    fun `위시를 삭제하면 200 이고 이후 조회에서 제외된다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        val authHeader = "Bearer ${memberToken(userId)}"
        val keptWishId = seedReadyWish(userId, "https://shop.example.com/products/1", "남길 상품")
        val deletedWishId = seedReadyWish(userId, "https://shop.example.com/products/2", "지울 상품")

        mockMvc
            .perform(
                delete("/api/v1/wishlists/$deletedWishId")
                    .header(HttpHeaders.AUTHORIZATION, authHeader),
            ).andExpect(status().isOk)

        mockMvc
            .perform(
                get("/api/v1/wishlists")
                    .header(HttpHeaders.AUTHORIZATION, authHeader),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].wish.id").value(keptWishId))
    }

    @Test
    fun `남의 위시를 삭제하면 403 이 반환된다`() {
        val mockMvc = buildMockMvc()
        val ownerId = UUID.randomUUID()
        val otherId = UUID.randomUUID()
        insertMember(ownerId)
        insertMember(otherId)
        val wishId = seedReadyWish(ownerId, "https://shop.example.com/products/1", "내 상품")

        mockMvc
            .perform(
                delete("/api/v1/wishlists/$wishId")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(otherId)}"),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `존재하지 않는 위시를 삭제해도 200 이 반환된다 (멱등)`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)

        // 멱등: 없는 위시는 "이미 삭제된 목표 상태"이므로 no-op 으로 성공한다.
        mockMvc
            .perform(
                delete("/api/v1/wishlists/99999999")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}"),
            ).andExpect(status().isOk)
    }

    @Test
    fun `이미 삭제된 위시를 다시 삭제해도 200 이 반환된다 (멱등)`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        val authHeader = "Bearer ${memberToken(userId)}"
        val wishId = seedReadyWish(userId, "https://shop.example.com/products/1", "지울 상품")

        mockMvc
            .perform(delete("/api/v1/wishlists/$wishId").header(HttpHeaders.AUTHORIZATION, authHeader))
            .andExpect(status().isOk)
        // 같은 위시 재삭제 — 이미 삭제된 상태라 멱등하게 다시 200.
        mockMvc
            .perform(delete("/api/v1/wishlists/$wishId").header(HttpHeaders.AUTHORIZATION, authHeader))
            .andExpect(status().isOk)
    }

    @Test
    fun `여러 위시를 다중 삭제하면 200 이고 모두 조회에서 제외된다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        val authHeader = "Bearer ${memberToken(userId)}"
        val keptWishId = seedReadyWish(userId, "https://shop.example.com/products/1", "남길 상품")
        val deletedWishId1 = seedReadyWish(userId, "https://shop.example.com/products/2", "지울 상품1")
        val deletedWishId2 = seedReadyWish(userId, "https://shop.example.com/products/3", "지울 상품2")

        mockMvc
            .perform(
                delete("/api/v1/wishlists")
                    .param("ids", "$deletedWishId1,$deletedWishId2")
                    .header(HttpHeaders.AUTHORIZATION, authHeader),
            ).andExpect(status().isOk)

        // 다중 삭제된 둘은 빠지고 남긴 하나만 조회된다.
        mockMvc
            .perform(get("/api/v1/wishlists").header(HttpHeaders.AUTHORIZATION, authHeader))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].wish.id").value(keptWishId))
    }

    @Test
    fun `다중 삭제 목록에 남의 위시가 섞이면 403 이고 아무것도 삭제되지 않는다`() {
        val mockMvc = buildMockMvc()
        val ownerId = UUID.randomUUID()
        val otherId = UUID.randomUUID()
        insertMember(ownerId)
        insertMember(otherId)
        val myWishId = seedReadyWish(ownerId, "https://shop.example.com/products/1", "내 상품")
        val othersWishId = seedReadyWish(otherId, "https://shop.example.com/products/2", "남의 상품")

        mockMvc
            .perform(
                delete("/api/v1/wishlists")
                    .param("ids", "$myWishId,$othersWishId")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(ownerId)}"),
            ).andExpect(status().isForbidden)

        // 남의것이 섞이면 403 + @Transactional 롤백 — 내 위시도 지워지지 않고 그대로 남아 있다.
        mockMvc
            .perform(get("/api/v1/wishlists").header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(ownerId)}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].wish.id").value(myWishId))
    }

    @Test
    fun `다중 삭제 목록에 존재하지 않는 위시가 섞여도 본인 것은 삭제되고 200 이 반환된다 (멱등)`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        val authHeader = "Bearer ${memberToken(userId)}"
        val existingWishId = seedReadyWish(userId, "https://shop.example.com/products/1", "존재하는 상품")

        // 없는 id 가 섞여도 멱등 — 존재하는 본인 위시만 삭제하고 없는 id 는 "이미 없는 상태"로 무시한다.
        mockMvc
            .perform(
                delete("/api/v1/wishlists")
                    .param("ids", "$existingWishId,99999999")
                    .header(HttpHeaders.AUTHORIZATION, authHeader),
            ).andExpect(status().isOk)

        // 존재하던 본인 위시는 삭제되어 조회에서 빠진다.
        mockMvc
            .perform(get("/api/v1/wishlists").header(HttpHeaders.AUTHORIZATION, authHeader))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(0))
    }

    @Test
    fun `다중 삭제에 ids 를 보내지 않으면 400 BAD_REQUEST 가 반환된다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)

        // ids 파라미터 자체를 생략 — required=false + orEmpty 로 WishDeleteIds 검증(빈 목록)에 닿아 400.
        mockMvc
            .perform(
                delete("/api/v1/wishlists")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}"),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("WISH-006"))
            .andExpect(jsonPath("$.detail").value("한 번에 최대 100개까지 삭제할 수 있어요."))
    }

    @Test
    fun `다중 삭제 ids 가 100 개를 초과하면 400 BAD_REQUEST 가 반환된다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        // 상한 100 정책을 테스트로 고정 — 101개면 WishDeleteIds 가 거부한다.
        val ids = (1L..101L).joinToString(",")

        mockMvc
            .perform(
                delete("/api/v1/wishlists")
                    .param("ids", ids)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}"),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `다중 삭제에 중복 id 를 보내도 정상 삭제되고 200 이 반환된다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        val authHeader = "Bearer ${memberToken(userId)}"
        val wishId = seedReadyWish(userId, "https://shop.example.com/products/1", "지울 상품")

        // 같은 id 를 중복으로 보내도 distinct 정규화로 1건으로 취급되어 정상 삭제된다.
        mockMvc
            .perform(
                delete("/api/v1/wishlists")
                    .param("ids", "$wishId,$wishId")
                    .header(HttpHeaders.AUTHORIZATION, authHeader),
            ).andExpect(status().isOk)

        mockMvc
            .perform(get("/api/v1/wishlists").header(HttpHeaders.AUTHORIZATION, authHeader))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(0))
    }
}
