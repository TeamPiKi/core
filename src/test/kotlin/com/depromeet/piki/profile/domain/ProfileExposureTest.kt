package com.depromeet.piki.profile.domain

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// #1285 노출 규칙 표의 행마다 한 테스트
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
    fun `프로필 공개 OFF 면 폴더 설정과 관계없이 폴더와 위시가 전부 안 보인다`() {
        val exposure = ProfileExposure.of(viewer, owner, isOwnerProfilePublic = false)

        assertFalse(exposure.canSeeFolder(isFolderPublic = true))
        assertFalse(exposure.canSeeWishOnProfile(listOf(true)))
        assertFalse(exposure.canSeeWishInExplore(listOf(true)))
        assertFalse(exposure.canSeeWishInExplore(emptyList()))
    }

    @Test
    fun `비공개 폴더에만 든 위시는 프로필과 탐색 모두 안 보인다`() {
        val exposure = ProfileExposure.of(viewer, owner, isOwnerProfilePublic = true)

        assertFalse(exposure.canSeeWishOnProfile(listOf(false, false)))
        assertFalse(exposure.canSeeWishInExplore(listOf(false, false)))
    }

    @Test
    fun `비공개 폴더와 공개 폴더에 함께 든 위시는 프로필과 탐색 모두 보인다`() {
        val exposure = ProfileExposure.of(viewer, owner, isOwnerProfilePublic = true)

        assertTrue(exposure.canSeeWishOnProfile(listOf(false, true)))
        assertTrue(exposure.canSeeWishInExplore(listOf(false, true)))
    }

    @Test
    fun `폴더에 없는 위시는 프로필에는 안 보이고 탐색에만 보인다`() {
        val exposure = ProfileExposure.of(viewer, owner, isOwnerProfilePublic = true)

        assertFalse(exposure.canSeeWishOnProfile(emptyList()))
        assertTrue(exposure.canSeeWishInExplore(emptyList()))
    }

    @Test
    fun `차단 관계면 공개 프로필이라도 폴더와 위시가 전부 안 보인다`() {
        val exposure = ProfileExposure.of(viewer, owner, isOwnerProfilePublic = true, isBlocked = true)

        assertFalse(exposure.canSeeFolder(isFolderPublic = true))
        assertFalse(exposure.canSeeWishOnProfile(listOf(true)))
        assertFalse(exposure.canSeeWishInExplore(emptyList()))
    }

    @Test
    fun `비로그인도 공개 프로필의 공개 폴더를 본다`() {
        val exposure = ProfileExposure.of(viewerId = null, ownerId = owner, isOwnerProfilePublic = true)

        assertTrue(exposure.canSeeFolder(isFolderPublic = true))
        assertFalse(exposure.canSeeFolder(isFolderPublic = false))
    }

    @Test
    fun `주인 본인은 프로필 공개 OFF 여도 비공개 폴더와 그 위시까지 본다`() {
        val exposure = ProfileExposure.of(owner, owner, isOwnerProfilePublic = false)

        assertTrue(exposure.canSeeFolder(isFolderPublic = false))
        assertTrue(exposure.canSeeWishOnProfile(listOf(false)))
        assertTrue(exposure.canSeeWishInExplore(listOf(false)))
    }
}
