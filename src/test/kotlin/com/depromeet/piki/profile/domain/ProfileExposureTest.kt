package com.depromeet.piki.profile.domain

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileExposureTest {
    private val owner = UUID.randomUUID()
    private val viewer = UUID.randomUUID()

    @Test
    fun `프로필 공개면 공개 폴더는 보이고 비공개 폴더는 안 보인다`() {
        val exposure = ProfileExposure.of(viewer, owner, isOwnerProfilePublic = true)

        assertTrue(exposure.canSeeFolder(isFolderPublic = true))
        assertFalse(exposure.canSeeFolder(isFolderPublic = false))
    }

    @Test
    fun `프로필 공개 OFF 면 폴더 설정과 관계없이 폴더가 안 보인다`() {
        val exposure = ProfileExposure.of(viewer, owner, isOwnerProfilePublic = false)

        assertFalse(exposure.canSeeFolder(isFolderPublic = true))
    }

    @Test
    fun `차단 관계면 공개 프로필이라도 폴더가 안 보인다`() {
        val exposure = ProfileExposure.of(viewer, owner, isOwnerProfilePublic = true, isBlocked = true)

        assertFalse(exposure.canSeeFolder(isFolderPublic = true))
    }

    @Test
    fun `비로그인도 공개 프로필의 공개 폴더를 본다`() {
        val exposure = ProfileExposure.of(viewerId = null, ownerId = owner, isOwnerProfilePublic = true)

        assertTrue(exposure.canSeeFolder(isFolderPublic = true))
        assertFalse(exposure.canSeeFolder(isFolderPublic = false))
    }

    @Test
    fun `주인 본인은 프로필 공개 OFF 여도 비공개 폴더까지 본다`() {
        val exposure = ProfileExposure.of(owner, owner, isOwnerProfilePublic = false)

        assertTrue(exposure.canSeeFolder(isFolderPublic = false))
    }
}
