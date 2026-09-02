package com.depromeet.piki.user.service

import com.depromeet.piki.common.storage.S3Properties
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// URL 조립은 publicBaseUrl 외 의존이 없는 순수 로직이라 Spring 없이 단위 테스트로 형식·범위를 망라한다.
class DefaultProfileImagesTest {
    private fun defaultProfileImages(publicBaseUrl: String): DefaultProfileImages =
        DefaultProfileImages(
            S3Properties(bucket = "piki-assets", publicBaseUrl = publicBaseUrl),
        )

    @Test
    fun `urlOf 는 publicBaseUrl 과 defaults profile 경로 png 를 조립한다`() {
        val images = defaultProfileImages("https://piki.s3.ap-northeast-2.amazonaws.com")

        assertEquals("https://piki.s3.ap-northeast-2.amazonaws.com/defaults/user-profile-1.png", images.urlOf(1))
        assertEquals("https://piki.s3.ap-northeast-2.amazonaws.com/defaults/user-profile-4.png", images.urlOf(4))
    }

    @Test
    fun `publicBaseUrl 끝 슬래시는 무시해 슬래시가 중복되지 않는다`() {
        val images = defaultProfileImages("https://piki.s3.ap-northeast-2.amazonaws.com/")

        assertEquals("https://piki.s3.ap-northeast-2.amazonaws.com/defaults/user-profile-2.png", images.urlOf(2))
    }

    @Test
    fun `random 은 항상 publicBaseUrl + defaults profile 1부터 COUNT 까지 png 형식 URL 을 돌려준다`() {
        val base = "https://piki.s3.ap-northeast-2.amazonaws.com"
        val images = defaultProfileImages(base)
        val expected = (1..DefaultProfileImages.COUNT).map { "$base/defaults/user-profile-$it.png" }.toSet()

        // 여러 번 호출해 4종 범위 안에만 떨어지는지 확인한다.
        val produced = (1..200).map { images.random() }.toSet()

        assertTrue(produced.all { it in expected }, "random() 이 기본 아바타 4종 범위를 벗어났다: $produced")
    }

    @Test
    fun `deleted 는 publicBaseUrl 과 defaults 탈퇴 아바타 png 를 조립한다`() {
        val images = defaultProfileImages("https://piki.s3.ap-northeast-2.amazonaws.com")

        assertEquals("https://piki.s3.ap-northeast-2.amazonaws.com/defaults/user-deleted.png", images.deleted())
    }

    @Test
    fun `deleted 도 publicBaseUrl 끝 슬래시를 무시해 슬래시가 중복되지 않는다`() {
        val images = defaultProfileImages("https://piki.s3.ap-northeast-2.amazonaws.com/")

        assertEquals("https://piki.s3.ap-northeast-2.amazonaws.com/defaults/user-deleted.png", images.deleted())
    }

    // 탈퇴 아바타는 랜덤 4종과 다른 축이라, random() 이 절대 이 값을 뽑아선 안 된다(탈퇴 아닌 유저가 탈퇴 프사를 갖는 사고).
    @Test
    fun `random 은 탈퇴 아바타를 뽑지 않는다`() {
        val images = defaultProfileImages("https://piki.s3.ap-northeast-2.amazonaws.com")
        val deleted = images.deleted()

        val produced = (1..200).map { images.random() }.toSet()

        assertTrue(produced.none { it == deleted }, "random() 이 탈퇴 아바타를 뽑았다: $deleted")
    }
}
