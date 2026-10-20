package com.depromeet.piki.user.controller

import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.common.storage.ImageStorageException
import com.depromeet.piki.image.domain.UploadSize
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.StubImageStorage
import com.depromeet.piki.support.uuidToBytes
import com.depromeet.piki.user.controller.dto.NicknameCheckRequest
import com.depromeet.piki.user.controller.dto.ProfileImagePresignRequest
import com.depromeet.piki.user.controller.dto.UserUpdateRequest
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.user.domain.User
import org.hamcrest.Matchers.endsWith
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@Transactional
class UserControllerIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var jwtProvider: JwtProvider

    @Autowired
    private lateinit var stubImageStorage: StubImageStorage

    private fun insertUser(
        userId: UUID,
        nickname: String = userId.toString().take(10),
        identityType: IdentityType = IdentityType.GUEST,
    ) {
        jdbcTemplate.update(
            "INSERT INTO users (id, nickname, profile_image, identity_type, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, NOW(6), NOW(6))",
            uuidToBytes(userId),
            nickname,
            "https://example.com/img.png",
            identityType.name,
        )
    }

    // 탈퇴(tombstone) 유저 — 행은 남고 deleted_at 이 채워진 상태. 닉네임 익명화까지 재현할 필요는 없어
    // "활성이 아니다"를 결정하는 deleted_at 만 세운다.
    private fun insertWithdrawnUser(userId: UUID) {
        insertUser(userId, identityType = IdentityType.MEMBER)
        jdbcTemplate.update("UPDATE users SET deleted_at = NOW(6) WHERE id = ?", uuidToBytes(userId))
    }

    private fun insertUserDetail(
        userId: UUID,
        provider: String = "GOOGLE",
        socialId: String = userId.toString(),
        email: String? = null,
    ) {
        jdbcTemplate.update(
            "INSERT INTO user_details (user_id, provider, social_id, email, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, NOW(6), NOW(6))",
            uuidToBytes(userId),
            provider,
            socialId,
            email,
        )
    }

    private fun token(
        userId: UUID,
        identityType: IdentityType = IdentityType.GUEST,
    ): String = jwtProvider.generateAccessToken(userId, identityType)

    // 매직바이트 교차검증을 통과하는 최소 유효 시그니처 (실제 픽셀 데이터는 검증과 무관).
    private fun jpegBytes(): ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0, 0, 0)

    // 클라가 presigned 로 이미 올렸다고 가정하는 key. 발급 형식이어야 확정 단계의 형식 검증을 통과한다
    private fun profileImageKey(
        owner: UUID,
        extension: String,
    ): String = "profiles/$owner/${UUID.randomUUID()}.$extension"

    // PATCH /users/me 는 JSON 계약이다(이미지 바이트는 presigned 로 S3 에 직접 올라가고 여기엔 key 만 실린다).
    private fun patchMe(
        body: String,
        bearer: String,
    ) = patch("/api/v1/users/me")
        .contentType(MediaType.APPLICATION_JSON)
        .content(body)
        .header(HttpHeaders.AUTHORIZATION, "Bearer $bearer")

    @Test
    fun `GET users me - 게스트가 자기 정보를 200 으로 조회한다`() {
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
        val userId = UUID.randomUUID()
        insertUser(userId, nickname = "테스트닉네임", identityType = IdentityType.GUEST)

        mockMvc
            .perform(
                get("/api/v1/users/me")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(userId, IdentityType.GUEST)}"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.id").value(userId.toString()))
            .andExpect(jsonPath("$.data.nickname").value("테스트닉네임"))
            .andExpect(jsonPath("$.data.identityType").value("GUEST"))
            .andExpect(jsonPath("$.data.profileImage").isString)
            // 게스트는 소셜 계정(user_details)이 없으므로 email 은 null 로 내려온다.
            .andExpect(jsonPath("$.data.email").value(null))
    }

    @Test
    fun `GET users me - 소셜 회원은 수집된 email 을 함께 받는다`() {
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
        val userId = UUID.randomUUID()
        insertUser(userId, nickname = "회원닉네임", identityType = IdentityType.MEMBER)
        insertUserDetail(userId, provider = "GOOGLE", email = "member@gmail.com")

        mockMvc
            .perform(
                get("/api/v1/users/me")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(userId, IdentityType.MEMBER)}"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.identityType").value("MEMBER"))
            // user_details 의 email 이 마이페이지 응답으로 내려오는지 contract 고정.
            .andExpect(jsonPath("$.data.email").value("member@gmail.com"))
    }

    @Test
    fun `GET users me - email 미동의 회원은 email 이 null 로 내려온다`() {
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
        val userId = UUID.randomUUID()
        insertUser(userId, identityType = IdentityType.MEMBER)
        // 소셜 계정은 있지만 email 미수집(애플 Private Relay 거부 등) — user_details.email 이 null.
        insertUserDetail(userId, provider = "APPLE", email = null)

        mockMvc
            .perform(
                get("/api/v1/users/me")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(userId, IdentityType.MEMBER)}"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.email").value(null))
    }

    @Test
    fun `GET users me - 탈퇴한 유저의 살아있는 access token 으로 조회하면 409 가 반환된다`() {
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
        val userId = UUID.randomUUID()
        insertWithdrawnUser(userId)

        // 탈퇴 시 access token 은 denylist 로 막히지만, 그 무효화가 부분 실패하면(#689) tombstone 이 서비스까지 닿는다.
        // 그 창에서도 tombstone 은 활성 유저로 살아나면 안 되므로 409 로 끊는다.
        mockMvc
            .perform(
                get("/api/v1/users/me")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(userId, IdentityType.MEMBER)}"),
            ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("USER-003"))
            .andExpect(jsonPath("$.detail").value("탈퇴한 계정이에요."))
            .andExpect(jsonPath("$.data").value(null))
    }

    @Test
    fun `GET users me - 인증 헤더 없으면 401 이 반환된다`() {
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()

        mockMvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `POST users me profile-image - 회원이 발급을 요청하면 200 과 업로드 URL·key 가 반환된다`() {
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
        val userId = UUID.randomUUID()
        insertUser(userId, identityType = IdentityType.MEMBER)

        mockMvc
            .perform(
                post("/api/v1/users/me/profile-image")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"contentType":"image/png","contentLength":1024}""")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(userId, IdentityType.MEMBER)}"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.imageKey").value(startsWith("profiles/$userId/")))
            .andExpect(jsonPath("$.data.imageKey").value(endsWith(".png")))
            .andExpect(jsonPath("$.data.uploadUrl").value(startsWith(StubImageStorage.BASE_URL)))
            // 서명에 박힌 값이라 클라가 PUT 할 때 그대로 써야 한다.
            .andExpect(jsonPath("$.data.contentType").value("image/png"))
    }

    @Test
    fun `POST users me profile-image - 게스트는 발급 단계에서 403 으로 거부된다`() {
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
        val userId = UUID.randomUUID()
        insertUser(userId, identityType = IdentityType.GUEST)
        val presignedBefore = stubImageStorage.presignedKeys.size

        // 확정이 아니라 발급에서 막는 게 계약이다 — 게스트에게 URL 을 주면 S3 에 올릴 기회 자체가 생긴다.
        mockMvc
            .perform(
                post("/api/v1/users/me/profile-image")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"contentType":"image/png","contentLength":1024}""")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(userId, IdentityType.GUEST)}"),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("USER-008"))

        // 막혔으니 서명 자체가 발급되지 않아야 한다 (권한 확인이 발급보다 앞이라는 계약).
        assertEquals(presignedBefore, stubImageStorage.presignedKeys.size)
    }

    @Test
    fun `POST users me profile-image - 미지원 형식은 USER-010 으로 400 이 반환된다`() {
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
        val userId = UUID.randomUUID()
        insertUser(userId, identityType = IdentityType.MEMBER)

        // 형식 분기 망라는 ProfileImageFileTest 가 단위로 담당한다. 여기선 발급 단계가 그 정책을 400 계약으로
        // 잇는지만 대표로 고정한다 — 확정이 아니라 발급에서 걸러야 미지원 파일이 S3 에 올라가지 않는다.
        mockMvc
            .perform(
                post("/api/v1/users/me/profile-image")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"contentType":"image/gif","contentLength":1024}""")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(userId, IdentityType.MEMBER)}"),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("USER-010"))
    }

    @Test
    fun `POST users me profile-image - contentLength 가 5MB 를 넘으면 UPLOAD-003 으로 400 이 반환된다`() {
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
        val userId = UUID.randomUUID()
        insertUser(userId, identityType = IdentityType.MEMBER)
        val presignedBefore = stubImageStorage.presignedKeys.size

        mockMvc
            .perform(
                post("/api/v1/users/me/profile-image")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"contentType":"image/png","contentLength":${UploadSize.MAX_BYTES + 1}}""")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(userId, IdentityType.MEMBER)}"),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("UPLOAD-003"))
        assertEquals(presignedBefore, stubImageStorage.presignedKeys.size)
    }

    @Test
    fun `POST users me profile-image - contentLength 를 생략하면 UPLOAD-004 로 400 이 반환된다`() {
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
        val userId = UUID.randomUUID()
        insertUser(userId, identityType = IdentityType.MEMBER)
        val presignedBefore = stubImageStorage.presignedKeys.size

        mockMvc
            .perform(
                post("/api/v1/users/me/profile-image")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"contentType":"image/png"}""")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(userId, IdentityType.MEMBER)}"),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("UPLOAD-004"))
        assertEquals(presignedBefore, stubImageStorage.presignedKeys.size)
    }

    @Test
    fun `POST users me profile-image - contentType 이 비면 400 이 반환된다`() {
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
        val userId = UUID.randomUUID()
        insertUser(userId, identityType = IdentityType.MEMBER)

        // 빈 값은 "형식 미지원" 이 아니라 "요청이 덜 채워짐" 이라 Bean Validation 이 잡는다.
        mockMvc
            .perform(
                post("/api/v1/users/me/profile-image")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"contentType":" ","contentLength":1024}""")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(userId, IdentityType.MEMBER)}"),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.detail").value(ProfileImagePresignRequest.CONTENT_TYPE_REQUIRED_MESSAGE))
    }

    @Test
    fun `PATCH users me - 게스트가 닉네임만 수정하면 200 과 변경된 닉네임이 반환된다`() {
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
        val userId = UUID.randomUUID()
        insertUser(userId, nickname = "초기닉네임", identityType = IdentityType.GUEST)

        mockMvc
            .perform(
                patchMe("""{"nickname":"새닉네임"}""", token(userId, IdentityType.GUEST)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.nickname").value("새닉네임"))
    }

    @Test
    fun `PATCH users me - 회원이 imageKey 만 보내면 200 과 갱신된 프로필 URL 을 반환한다`() {
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
        val userId = UUID.randomUUID()
        insertUser(userId, nickname = "초기닉네임", identityType = IdentityType.MEMBER)
        val imageKey = profileImageKey(userId, "jpg")
        stubImageStorage.downloadBehavior = { jpegBytes() }

        mockMvc
            .perform(
                patchMe("""{"imageKey":"$imageKey"}""", token(userId, IdentityType.MEMBER)),
            ).andExpect(status().isOk)
            // 닉네임은 안 보냈으니 그대로, 프로필만 갱신된다.
            .andExpect(jsonPath("$.data.nickname").value("초기닉네임"))
            .andExpect(jsonPath("$.data.profileImage").value("${StubImageStorage.BASE_URL}/$imageKey"))

        // 검증 없이 URL 만 갈아끼우면 위장 업로드가 통과한다
        assertContains(stubImageStorage.downloadedKeys, imageKey)
        assertFalse(stubImageStorage.uploadedKeys.contains(imageKey))
        assertFalse(stubImageStorage.deletedKeys.contains(imageKey))
    }

    @Test
    fun `PATCH users me - 회원이 닉네임과 imageKey 를 함께 보내면 둘 다 갱신된 200 을 반환한다`() {
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
        val userId = UUID.randomUUID()
        insertUser(userId, nickname = "초기닉네임", identityType = IdentityType.MEMBER)
        stubImageStorage.downloadBehavior = { jpegBytes() }

        mockMvc
            .perform(
                patchMe(
                    """{"nickname":"새닉네임","imageKey":"${profileImageKey(userId, "jpg")}"}""",
                    token(userId, IdentityType.MEMBER),
                ),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.nickname").value("새닉네임"))
            .andExpect(
                jsonPath("$.data.profileImage")
                    .value(startsWith("${StubImageStorage.BASE_URL}/profiles/$userId/")),
            )
    }

    @Test
    fun `PATCH users me - 게스트가 imageKey 를 보내면 403 이 반환된다`() {
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
        val userId = UUID.randomUUID()
        insertUser(userId, nickname = "게스트닉네임", identityType = IdentityType.GUEST)

        // 이미지 수정은 MEMBER 전용 — 게스트는 닉네임 동반 여부와 무관하게 요청 전체가 403.
        mockMvc
            .perform(
                patchMe(
                    """{"nickname":"새닉네임","imageKey":"${profileImageKey(userId, "jpg")}"}""",
                    token(userId, IdentityType.GUEST),
                ),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.detail").value("프로필 이미지는 회원만 바꿀 수 있어요."))
            .andExpect(jsonPath("$.code").value("USER-008"))

        // 403 으로 요청 전체가 거부됐으니 닉네임도 그대로여야 한다 (부분 적용 없음).
        mockMvc
            .perform(
                get("/api/v1/users/me")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(userId, IdentityType.GUEST)}"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.nickname").value("게스트닉네임"))
    }

    @Test
    fun `PATCH users me - 게스트의 요청은 원본을 읽기도 전에 403 으로 끊긴다`() {
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
        val userId = UUID.randomUUID()
        insertUser(userId, nickname = "게스트닉네임", identityType = IdentityType.GUEST)
        val imageKey = profileImageKey(userId, "jpg")

        // 권한 확인이 payload 처리보다 먼저라는 계약을 고정한다(authorization before payload processing).
        // downloadBehavior 를 세팅하지 않았으므로, 순서가 뒤집혀 다운로드가 먼저 실행되면 stub 이 터져 500 이 난다.
        mockMvc
            .perform(patchMe("""{"imageKey":"$imageKey"}""", token(userId, IdentityType.GUEST)))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("USER-008"))

        assertFalse(stubImageStorage.downloadedKeys.contains(imageKey))
    }

    @Test
    fun `PATCH users me - 발급하지 않은 형식의 imageKey 는 UPLOAD-001 로 400 이 반환된다`() {
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
        val userId = UUID.randomUUID()
        insertUser(userId, identityType = IdentityType.MEMBER)

        // 클라가 임의 경로를 준 경우 — 우리 발급 형식이 아니면 S3 를 보기도 전에 400.
        mockMvc
            .perform(patchMe("""{"imageKey":"profiles/someone-else/steal.jpg"}""", token(userId, IdentityType.MEMBER)))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("UPLOAD-001"))
    }

    @Test
    fun `PATCH users me - 다른 회원이 올린 imageKey 는 UPLOAD-001 로 400 이 반환되고 읽지도 않는다`() {
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
        val userId = UUID.randomUUID()
        insertUser(userId, identityType = IdentityType.MEMBER)
        val othersKey = profileImageKey(UUID.randomUUID(), "jpg")

        mockMvc
            .perform(patchMe("""{"imageKey":"$othersKey"}""", token(userId, IdentityType.MEMBER)))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("UPLOAD-001"))

        assertFalse(stubImageStorage.downloadedKeys.contains(othersKey))
    }

    @Test
    fun `PATCH users me - 아직 올라오지 않은 imageKey 는 UPLOAD-002 로 400 이 반환된다`() {
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
        val userId = UUID.randomUUID()
        insertUser(userId, identityType = IdentityType.MEMBER)
        stubImageStorage.existsBehavior = { false }

        try {
            // 발급만 받고 PUT 을 건너뛴 채 확정을 부른 경우.
            mockMvc
                .perform(patchMe("""{"imageKey":"${profileImageKey(userId, "jpg")}"}""", token(userId, IdentityType.MEMBER)))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("UPLOAD-002"))
        } finally {
            // 공유 컨텍스트의 stub mutable state 를 복원해 다른 테스트로 누수되지 않게 한다.
            stubImageStorage.existsBehavior = stubImageStorage.defaultExistsBehavior
        }
    }

    @Test
    fun `PATCH users me - 발급 때 선언한 형식과 실제 내용이 다르면 USER-011 로 400 이 반환된다`() {
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
        val userId = UUID.randomUUID()
        insertUser(userId, identityType = IdentityType.MEMBER)
        // png 로 발급받아 놓고 실제로는 jpeg 바이트를 올린 경우 — presigned 서명은 헤더만 강제하고 내용은 못 본다.
        // 서버가 원본을 읽어 교차검증하는 이유가 이 케이스다.
        stubImageStorage.downloadBehavior = { jpegBytes() }

        val imageKey = profileImageKey(userId, "png")

        mockMvc
            .perform(patchMe("""{"imageKey":"$imageKey"}""", token(userId, IdentityType.MEMBER)))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("USER-011"))

        // 이미지가 아닌 파일이 공개 버킷에 남지 않는다
        assertContains(stubImageStorage.deletedKeys, imageKey)
    }

    @Test
    fun `PATCH users me - 아무 필드도 안 보내면 200 으로 통과하고 기존 값이 유지된다`() {
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
        val userId = UUID.randomUUID()
        insertUser(userId, nickname = "그대로닉네임")

        // 닉네임도 이미지도 없는 빈 PATCH — 무동작 200.
        mockMvc
            .perform(patchMe("{}", token(userId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.nickname").value("그대로닉네임"))
    }

    @Test
    fun `PATCH users me - 닉네임 11자는 400 이 반환된다`() {
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
        val userId = UUID.randomUUID()
        insertUser(userId)

        mockMvc
            .perform(patchMe("""{"nickname":"12345678901"}""", token(userId)))
            .andExpect(status().isBadRequest)
            // Bean Validation 실패 응답 detail 이 OpenAPI example(UserApiExamples updateMe 400)과 같은 형식인지 고정.
            .andExpect(jsonPath("$.detail").value(UserUpdateRequest.NICKNAME_SIZE_MESSAGE))
    }

    @Test
    fun `PATCH users me - 공백만 있는 닉네임은 USER-006 으로 400 이 반환된다`() {
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
        val userId = UUID.randomUUID()
        insertUser(userId)

        // @Size(min = 1) 은 길이만 보므로 공백 1자를 통과시킨다. 걸러내는 건 User.validateNickname 이고,
        // 그래서 Bean Validation 의 COMMON-INVALID-INPUT 이 아니라 도메인 code 가 나간다(UserApiExamples 와 lockstep).
        mockMvc
            .perform(patchMe("""{"nickname":" "}""", token(userId)))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("USER-006"))
    }

    @Test
    fun `PATCH users me - 탈퇴 예약 prefix 로 시작하는 닉네임은 USER-013 으로 400 이 반환된다`() {
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
        val userId = UUID.randomUUID()
        insertUser(userId)

        // 예약 prefix 선점은 형식이 아니라 정책이라 Bean Validation 이 못 잡는다 — 도메인이 막고 400 으로 나간다.
        mockMvc
            .perform(patchMe("""{"nickname":"${User.WITHDRAWN_NICKNAME_PREFIX}닉네임"}""", token(userId)))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("USER-013"))
    }

    @Test
    fun `PATCH users me - 이미 사용 중인 닉네임으로 변경하면 409 가 반환된다`() {
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
        val otherUserId = UUID.randomUUID()
        insertUser(otherUserId, nickname = "점유닉네임")
        val myUserId = UUID.randomUUID()
        insertUser(myUserId, nickname = "내닉네임")

        mockMvc
            .perform(patchMe("""{"nickname":"점유닉네임"}""", token(myUserId)))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("USER-004"))
    }

    @Test
    fun `PATCH users me - 영속화가 409 로 떨어져도 올린 이미지는 남아 같은 imageKey 로 다시 확정된다`() {
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
        val otherUserId = UUID.randomUUID()
        insertUser(otherUserId, nickname = "점유닉네임", identityType = IdentityType.MEMBER)
        val myUserId = UUID.randomUUID()
        insertUser(myUserId, nickname = "내닉네임", identityType = IdentityType.MEMBER)
        stubImageStorage.downloadBehavior = { jpegBytes() }
        val imageKey = profileImageKey(myUserId, "jpg")

        mockMvc
            .perform(patchMe("""{"nickname":"점유닉네임","imageKey":"$imageKey"}""", token(myUserId, IdentityType.MEMBER)))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("USER-004"))
        assertFalse(stubImageStorage.deletedKeys.contains(imageKey))

        mockMvc
            .perform(patchMe("""{"nickname":"새닉네임","imageKey":"$imageKey"}""", token(myUserId, IdentityType.MEMBER)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.profileImage").value("${StubImageStorage.BASE_URL}/$imageKey"))
    }

    @Test
    fun `PATCH users me - 본인이 자기 닉네임 그대로 보내면 200 으로 통과한다`() {
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
        val userId = UUID.randomUUID()
        insertUser(userId, nickname = "내닉네임")

        mockMvc
            .perform(patchMe("""{"nickname":"내닉네임"}""", token(userId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.nickname").value("내닉네임"))
    }

    @Test
    fun `PATCH users me - S3 에서 읽지 못하면 502 가 반환되고 닉네임도 반영되지 않는다`() {
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
        val userId = UUID.randomUUID()
        insertUser(userId, nickname = "원래닉네임", identityType = IdentityType.MEMBER)
        stubImageStorage.downloadBehavior = { throw ImageStorageException.downloadFailed() }

        mockMvc
            .perform(
                patchMe(
                    """{"nickname":"새닉네임","imageKey":"${profileImageKey(userId, "jpg")}"}""",
                    token(userId, IdentityType.MEMBER),
                ),
            ).andExpect(status().isBadGateway)

        mockMvc
            .perform(
                get("/api/v1/users/me")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(userId, IdentityType.MEMBER)}"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.nickname").value("원래닉네임"))
    }

    @Test
    fun `PATCH users me - 토큰이 없으면 401 이 반환된다`() {
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()

        mockMvc
            .perform(
                patch("/api/v1/users/me")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"nickname":"새닉네임"}"""),
            ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `GET users nickname check - 사용 가능한 닉네임이면 available true 가 반환된다`() {
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
        val userId = UUID.randomUUID()
        insertUser(userId)

        mockMvc
            .perform(
                get("/api/v1/users/nickname/check")
                    .param("nickname", "안쓰는닉네임")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(userId)}"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.available").value(true))
    }

    @Test
    fun `GET users nickname check - 다른 user 가 이미 점유한 닉네임이면 available false 가 반환된다`() {
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
        val otherUserId = UUID.randomUUID()
        insertUser(otherUserId, nickname = "점유닉네임")
        val myUserId = UUID.randomUUID()
        insertUser(myUserId, nickname = "내닉네임")

        mockMvc
            .perform(
                get("/api/v1/users/nickname/check")
                    .param("nickname", "점유닉네임")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(myUserId)}"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.available").value(false))
    }

    @Test
    fun `GET users nickname check - 본인이 자기 닉네임으로 호출하면 본인 제외로 available true 가 반환된다`() {
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
        val userId = UUID.randomUUID()
        insertUser(userId, nickname = "내닉네임")

        mockMvc
            .perform(
                get("/api/v1/users/nickname/check")
                    .param("nickname", "내닉네임")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(userId)}"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.available").value(true))
    }

    @Test
    fun `GET users nickname check - JWT 없이도 200 을 반환한다`() {
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()

        mockMvc
            .perform(
                get("/api/v1/users/nickname/check")
                    .param("nickname", "안쓰는닉네임"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.available").value(true))
    }

    @Test
    fun `GET users nickname check - 11자 닉네임은 400 이 반환된다`() {
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
        val userId = UUID.randomUUID()
        insertUser(userId)

        mockMvc
            .perform(
                get("/api/v1/users/nickname/check")
                    .param("nickname", "12345678901")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(userId)}"),
            ).andExpect(status().isBadRequest)
            // 쿼리 파라미터 POJO(암묵 @ModelAttribute) 검증 실패도 같은 "필드명: 메시지" 형식인지 실측·고정.
            .andExpect(jsonPath("$.detail").value(NicknameCheckRequest.NICKNAME_SIZE_MESSAGE))
    }
}
