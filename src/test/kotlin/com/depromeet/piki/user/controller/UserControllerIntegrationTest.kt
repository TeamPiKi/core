package com.depromeet.piki.user.controller

import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.common.storage.ImageStorageException
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.StubImageStorage
import com.depromeet.piki.support.uuidToBytes
import com.depromeet.piki.user.controller.dto.NicknameCheckRequest
import com.depromeet.piki.user.controller.dto.UserUpdateRequest
import com.depromeet.piki.user.domain.IdentityType
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertTrue

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
                multipart(HttpMethod.PATCH, "/api/v1/users/me")
                    .param("nickname", "새닉네임")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(userId, IdentityType.GUEST)}"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.nickname").value("새닉네임"))
    }

    @Test
    fun `PATCH users me - 회원이 이미지만 업로드하면 200 과 갱신된 프로필 URL 을 반환한다`() {
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
        val userId = UUID.randomUUID()
        insertUser(userId, nickname = "초기닉네임", identityType = IdentityType.MEMBER)
        val image = MockMultipartFile("image", "photo.jpg", "image/jpeg", jpegBytes())

        mockMvc
            .perform(
                multipart(HttpMethod.PATCH, "/api/v1/users/me")
                    .file(image)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(userId, IdentityType.MEMBER)}"),
            ).andExpect(status().isOk)
            // 닉네임은 안 보냈으니 그대로, 프로필만 갱신된다.
            .andExpect(jsonPath("$.data.nickname").value("초기닉네임"))
            // key 규칙 profiles/{userId}/{uuid}.jpg 로 업로드되어 stub URL 이 내려온다.
            .andExpect(
                jsonPath("$.data.profileImage")
                    .value(startsWith("${StubImageStorage.BASE_URL}/profiles/$userId/")),
            )
    }

    @Test
    fun `PATCH users me - 회원이 닉네임과 이미지를 함께 보내면 둘 다 갱신된 200 을 반환한다`() {
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
        val userId = UUID.randomUUID()
        insertUser(userId, nickname = "초기닉네임", identityType = IdentityType.MEMBER)
        val image = MockMultipartFile("image", "photo.jpg", "image/jpeg", jpegBytes())

        mockMvc
            .perform(
                multipart(HttpMethod.PATCH, "/api/v1/users/me")
                    .file(image)
                    .param("nickname", "새닉네임")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(userId, IdentityType.MEMBER)}"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.nickname").value("새닉네임"))
            .andExpect(
                jsonPath("$.data.profileImage")
                    .value(startsWith("${StubImageStorage.BASE_URL}/profiles/$userId/")),
            )
    }

    @Test
    fun `PATCH users me - 게스트가 이미지를 보내면 403 이 반환된다`() {
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
        val userId = UUID.randomUUID()
        insertUser(userId, nickname = "게스트닉네임", identityType = IdentityType.GUEST)
        val image = MockMultipartFile("image", "photo.jpg", "image/jpeg", jpegBytes())

        // 이미지 수정은 MEMBER 전용 — 게스트는 닉네임 동반 여부·이미지 형식과 무관하게 요청 전체가 403.
        mockMvc
            .perform(
                multipart(HttpMethod.PATCH, "/api/v1/users/me")
                    .file(image)
                    .param("nickname", "새닉네임")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(userId, IdentityType.GUEST)}"),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.detail").value("프로필 이미지는 회원만 바꿀 수 있어요."))
            .andExpect(jsonPath("$.code").value("USER-008"))
    }

    @Test
    fun `PATCH users me - 게스트가 이미지를 보내면 닉네임도 함께 거부되어 변경되지 않는다`() {
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
        val userId = UUID.randomUUID()
        insertUser(userId, nickname = "게스트닉네임", identityType = IdentityType.GUEST)
        val image = MockMultipartFile("image", "photo.jpg", "image/jpeg", jpegBytes())

        mockMvc
            .perform(
                multipart(HttpMethod.PATCH, "/api/v1/users/me")
                    .file(image)
                    .param("nickname", "새닉네임")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(userId, IdentityType.GUEST)}"),
            ).andExpect(status().isForbidden)

        // 403 으로 요청 전체가 거부됐으니 닉네임도 그대로여야 한다 (부분 적용 없음).
        mockMvc
            .perform(
                get("/api/v1/users/me")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(userId, IdentityType.GUEST)}"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.nickname").value("게스트닉네임"))
    }

    @Test
    fun `PATCH users me - 게스트가 잘못된 형식 이미지를 보내도 형식 검증 전에 403 이 반환된다`() {
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
        val userId = UUID.randomUUID()
        insertUser(userId, nickname = "게스트닉네임", identityType = IdentityType.GUEST)
        // 미지원 형식(gif) — 회원이었다면 400 이 날 입력이다.
        val gif = MockMultipartFile("image", "anim.gif", "image/gif", byteArrayOf(1, 2, 3))

        // 권한 확인이 형식 검증보다 먼저라는 계약을 고정한다 — 게스트는 형식이 틀려도 400 이 아니라 403.
        // (순서가 뒤집혀 형식 검증이 먼저 실행되면 400 이 새어 이 단언이 깨진다.)
        mockMvc
            .perform(
                multipart(HttpMethod.PATCH, "/api/v1/users/me")
                    .file(gif)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(userId, IdentityType.GUEST)}"),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.detail").value("프로필 이미지는 회원만 바꿀 수 있어요."))
            .andExpect(jsonPath("$.code").value("USER-008"))
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

        // 닉네임도 이미지도 없는 빈 PATCH — 더 이상 400 이 아니라(이미지 필수였던 옛 POST 와 달리) 무동작 200.
        mockMvc
            .perform(
                multipart(HttpMethod.PATCH, "/api/v1/users/me")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(userId)}"),
            ).andExpect(status().isOk)
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
            .perform(
                multipart(HttpMethod.PATCH, "/api/v1/users/me")
                    .param("nickname", "12345678901")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(userId)}"),
            ).andExpect(status().isBadRequest)
            // @ModelAttribute(multipart) 검증 실패 응답 detail 이 OpenAPI example(UserApiExamples updateMe 400)과 같은 형식인지 고정.
            .andExpect(jsonPath("$.detail").value(UserUpdateRequest.NICKNAME_SIZE_MESSAGE))
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
            .perform(
                multipart(HttpMethod.PATCH, "/api/v1/users/me")
                    .param("nickname", "점유닉네임")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(myUserId)}"),
            ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("USER-004"))
    }

    @Test
    fun `PATCH users me - 영속화가 409 로 떨어지면 방금 올린 프로필 이미지를 S3 에서 회수한다`() {
        // 업로드는 트랜잭션 밖에서 먼저 끝나므로, 뒤이은 영속화가 떨어지면 아무도 안 가리키는 객체가 남는다.
        // 프로필 사진은 PII 라 lifecycle 만료에 맡기지 않고 즉시 회수해야 한다.
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
        val otherUserId = UUID.randomUUID()
        insertUser(otherUserId, nickname = "점유닉네임", identityType = IdentityType.MEMBER)
        val myUserId = UUID.randomUUID()
        insertUser(myUserId, nickname = "내닉네임", identityType = IdentityType.MEMBER)
        val image = MockMultipartFile("image", "photo.jpg", "image/jpeg", jpegBytes())

        mockMvc
            .perform(
                multipart(HttpMethod.PATCH, "/api/v1/users/me")
                    .file(image)
                    .param("nickname", "점유닉네임")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(myUserId, IdentityType.MEMBER)}"),
            ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("USER-004"))

        // 이 요청이 올린 key 가 그대로 삭제 호출됐는지 — 회수 대상이 방금 올린 그 객체여야 한다.
        val uploadedKey = stubImageStorage.uploadedKeys.last { it.startsWith("profiles/$myUserId/") }
        assertContains(stubImageStorage.deletedKeys, uploadedKey)
    }

    @Test
    fun `PATCH users me - 업로드가 502 로 떨어져도 그 key 를 회수 시도한다`() {
        // S3 응답 유실·timeout 이면 예외가 나도 객체는 올라가 있을 수 있다. key 는 우리가 만든 값이라
        // 업로드 성공 여부와 무관하게 삭제를 걸 수 있고, 객체가 없으면 no-op 이라 안전하다.
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
        val userId = UUID.randomUUID()
        insertUser(userId, nickname = "원래닉네임", identityType = IdentityType.MEMBER)
        val image = MockMultipartFile("image", "photo.jpg", "image/jpeg", jpegBytes())
        stubImageStorage.behavior = { _, _, _ -> throw ImageStorageException.uploadFailed() }

        try {
            mockMvc
                .perform(
                    multipart(HttpMethod.PATCH, "/api/v1/users/me")
                        .file(image)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(userId, IdentityType.MEMBER)}"),
                ).andExpect(status().isBadGateway)
        } finally {
            // 공유 컨텍스트의 stub mutable state 를 복원해 다른 테스트로 누수되지 않게 한다.
            stubImageStorage.behavior = stubImageStorage.defaultBehavior
        }

        // 업로드가 던져 uploadedKeys 에는 안 남지만, 회수는 이 유저의 프로필 key 로 시도돼야 한다.
        assertTrue(
            stubImageStorage.deletedKeys.any { it.startsWith("profiles/$userId/") },
            "업로드 실패 시에도 해당 key 회수를 시도해야 한다",
        )
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
            .perform(
                multipart(HttpMethod.PATCH, "/api/v1/users/me")
                    .param("nickname", "내닉네임")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(userId)}"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.nickname").value("내닉네임"))
    }

    @Test
    fun `PATCH users me - gif 는 지원하지 않아 400 이 반환된다`() {
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
        val userId = UUID.randomUUID()
        insertUser(userId, identityType = IdentityType.MEMBER)
        val gif = MockMultipartFile("image", "anim.gif", "image/gif", byteArrayOf(1, 2, 3))

        // 이미지 형식 분기(빈파일·미지원·시그니처 불일치)는 ProfileImageFileTest 가 단위로 망라한다.
        // 여기선 회원이 올린 이미지의 형식 검증 실패를 컨트롤러가 400 contract 로 잇는지만 대표로 고정한다.
        mockMvc
            .perform(
                multipart(HttpMethod.PATCH, "/api/v1/users/me")
                    .file(gif)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(userId, IdentityType.MEMBER)}"),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.detail").value(startsWith("지원하지 않는 이미지 형식이에요.")))
            .andExpect(jsonPath("$.code").value("USER-010"))
    }

    @Test
    fun `PATCH users me - S3 업로드 실패 시 502 가 반환된다`() {
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
        val userId = UUID.randomUUID()
        insertUser(userId, nickname = "원래닉네임", identityType = IdentityType.MEMBER)
        val image = MockMultipartFile("image", "photo.jpg", "image/jpeg", jpegBytes())
        stubImageStorage.behavior = { _, _, _ -> throw ImageStorageException.uploadFailed() }

        try {
            // 닉네임을 함께 보낸다 — S3 업로드가 영속화보다 먼저라, 업로드가 깨지면 닉네임도 반영되면 안 된다(원자성).
            mockMvc
                .perform(
                    multipart(HttpMethod.PATCH, "/api/v1/users/me")
                        .file(image)
                        .param("nickname", "새닉네임")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(userId, IdentityType.MEMBER)}"),
                ).andExpect(status().isBadGateway)
        } finally {
            // 공유 컨텍스트의 stub mutable state 를 복원해 다른 테스트로 누수되지 않게 한다.
            stubImageStorage.behavior = stubImageStorage.defaultBehavior
        }

        // 502 로 끝났으니 닉네임도 반영되지 않아야 한다 — 업로드(트랜잭션 밖)가 영속화 전에 깨져 부분 적용이 없다.
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
        val image = MockMultipartFile("image", "photo.png", "image/png", byteArrayOf(1, 2, 3))

        mockMvc
            .perform(
                multipart(HttpMethod.PATCH, "/api/v1/users/me").file(image),
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
