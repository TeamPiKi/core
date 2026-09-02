package com.depromeet.piki.user.domain

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class UserTest {
    private fun guest() =
        User(
            id = UUID.randomUUID(),
            nickname = "테스트유저",
            profileImage = "https://example.com/img.png",
            identityType = IdentityType.GUEST,
        )

    @Test
    fun `GUEST 유저는 MEMBER 로 승격된다`() {
        val user = guest()
        user.promoteToMember()
        assertEquals(IdentityType.MEMBER, user.identityType)
    }

    @Test
    fun `이미 MEMBER 인 유저를 다시 승격하면 예외가 발생한다`() {
        val user = guest()
        user.promoteToMember()
        assertFailsWith<UserException> { user.promoteToMember() }
    }

    @Test
    fun `닉네임을 정상적으로 변경한다`() {
        val user = guest()
        user.updateNickname("새닉네임")
        assertEquals("새닉네임", user.nickname)
    }

    @Test
    fun `닉네임 10자는 허용된다`() {
        val user = guest()
        user.updateNickname("1234567890")
        assertEquals("1234567890", user.nickname)
    }

    @Test
    fun `닉네임 11자는 예외가 발생한다`() {
        val user = guest()
        assertFailsWith<UserException> { user.updateNickname("12345678901") }
    }

    @Test
    fun `빈 닉네임은 예외가 발생한다`() {
        val user = guest()
        assertFailsWith<UserException> { user.updateNickname("") }
    }

    @Test
    fun `공백만 있는 닉네임은 예외가 발생한다`() {
        val user = guest()
        assertFailsWith<UserException> { user.updateNickname("   ") }
    }

    @Test
    fun `softDelete 호출 시 deletedAt 이 설정된다`() {
        val user = guest()
        assertNull(user.deletedAt)
        user.softDelete()
        assertNotNull(user.deletedAt)
    }

    @Test
    fun `softDelete 는 멱등성을 보장한다`() {
        val user = guest()
        user.softDelete()
        val first = user.deletedAt
        user.softDelete()
        assertEquals(first, user.deletedAt)
    }

    private fun member(id: UUID = UUID.randomUUID()) =
        User(
            id = id,
            nickname = "원래닉네임",
            profileImage = "https://example.com/original.png",
            identityType = IdentityType.MEMBER,
        )

    // 탈퇴 아바타 URL 은 env 별 버킷을 흡수하려 호출부(DefaultProfileImages.deleted())가 넘긴다.
    // 엔티티는 "무엇으로 덮을지" 를 모르고 받은 값을 그대로 반영하는지만 책임지므로, 여기선 임의 URL 로 그 계약을 본다.
    private val deletedImage = "https://cdn.example/defaults/user-deleted.png"

    @Test
    fun `MEMBER 탈퇴 시 deletedAt 이 설정되고 닉네임-프로필이 비식별화된다`() {
        val id = UUID.fromString("8f1a3c2b-9d44-4e2a-9b12-1a2b3c4d5e6f")
        val user = member(id)

        user.withdraw(deletedImage)

        assertNotNull(user.deletedAt)
        assertEquals("탈퇴" + "8f1a3c2b", user.nickname)
        // 원래 프사(https://example.com/original.png)가 넘긴 탈퇴 아바타로 덮인다.
        assertEquals(deletedImage, user.profileImage)
    }

    @Test
    fun `탈퇴 닉네임은 10자 이하이고 본인 UUID 에서 파생되어 유일하다`() {
        val a = member(UUID.fromString("aaaaaaaa-0000-0000-0000-000000000000"))
        val b = member(UUID.fromString("bbbbbbbb-0000-0000-0000-000000000000"))

        a.withdraw(deletedImage)
        b.withdraw(deletedImage)

        assertEquals(true, a.nickname.length <= User.NICKNAME_MAX_LENGTH)
        assertEquals(true, b.nickname.length <= User.NICKNAME_MAX_LENGTH)
        // 서로 다른 UUID 앞 8 hex 에서 파생되므로 닉네임도 다르다(UNIQUE 충돌 회피).
        kotlin.test.assertNotEquals(a.nickname, b.nickname)
    }

    @Test
    fun `게스트에 withdraw 를 호출하면 불변식 위반으로 예외가 발생한다`() {
        val user = guest()
        // MEMBER 전용 경로 — 게스트는 도메인 check 위반(서비스가 사전에 403 으로 막아 정상 흐름에선 닿지 않음).
        assertFailsWith<IllegalStateException> { user.withdraw(deletedImage) }
    }

    @Test
    fun `탈퇴는 멱등하지 않고 재호출 시 닉네임이 재파생되어도 동일하다`() {
        val id = UUID.fromString("8f1a3c2b-9d44-4e2a-9b12-1a2b3c4d5e6f")
        val user = member(id)
        user.withdraw(deletedImage)
        val firstDeletedAt = user.deletedAt
        val firstNickname = user.nickname

        // softDelete 가 멱등이라 deletedAt 은 유지되고, 닉네임은 같은 id 파생이라 동일하다.
        user.withdraw(deletedImage)

        assertEquals(firstDeletedAt, user.deletedAt)
        assertEquals(firstNickname, user.nickname)
    }

    @Test
    fun `isActive 는 탈퇴 전 true 탈퇴 후 false 다`() {
        val user = member()
        assertEquals(true, user.isActive())
        user.withdraw(deletedImage)
        assertEquals(false, user.isActive())
    }

    @Test
    fun `탈퇴 예약 prefix 로 시작하는 닉네임은 생성 시 거부된다`() {
        // tombstone 닉네임(anonymizedNickname)과의 UNIQUE 충돌을 막기 위해 입력 경계에서 선점 자체를 거부한다.
        assertFailsWith<UserException> {
            User(
                id = UUID.randomUUID(),
                nickname = User.WITHDRAWN_NICKNAME_PREFIX + "어쩌고",
                profileImage = "https://example.com/img.png",
                identityType = IdentityType.GUEST,
            )
        }
    }

    @Test
    fun `탈퇴 예약 prefix 로 시작하는 닉네임으로 변경하면 거부된다`() {
        val user = guest()
        assertFailsWith<UserException> { user.updateNickname(User.WITHDRAWN_NICKNAME_PREFIX + "냥") }
    }

    @Test
    fun `탈퇴로 전이된 tombstone 닉네임 자체는 예약 prefix 검증을 우회한다`() {
        // withdraw 는 validateNickname 을 거치지 않고 anonymizedNickname 을 직접 대입하므로 예약 prefix 검증에 걸리지 않는다.
        val user = member()
        user.withdraw(deletedImage)
        assertEquals(true, user.nickname.startsWith(User.WITHDRAWN_NICKNAME_PREFIX))
    }
}
