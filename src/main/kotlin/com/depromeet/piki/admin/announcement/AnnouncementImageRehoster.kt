package com.depromeet.piki.admin.announcement

import com.depromeet.piki.admin.config.ConditionalOnAdminEnabled
import com.depromeet.piki.announcement.domain.AnnouncementBodyImages
import com.depromeet.piki.announcement.domain.AnnouncementImageFile
import com.depromeet.piki.common.storage.ImageStorage
import com.depromeet.piki.common.storage.S3Properties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

// 공지 본문의 외부 이미지 URL 을 우리 S3 로 옮기고 본문 URL 을 우리 것으로 치환한다(#561 rehost).
// fetch·업로드는 외부 호출이라 @Transactional 을 두지 않는다 — 호출부(AdminAnnouncementService)가
// 트랜잭션 밖에서 이 빈을 부르고, 영속화만 짧은 트랜잭션(AnnouncementWriter)에 위임한다(## 트랜잭션 경계).
@Component
@ConditionalOnAdminEnabled
class AnnouncementImageRehoster(
    private val fetcher: AnnouncementImageFetcher,
    private val imageStorage: ImageStorage,
    private val s3Properties: S3Properties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 본문의 이미지 URL 중 "우리 S3 가 아닌" 것만 가져와 announcement/{id}/ 로 올리고 URL 을 치환한다.
    // 이미 우리 S3 인 URL(재저장 시 직전 rehost 결과)은 건너뛴다.
    fun rehost(
        announcementId: Long,
        body: String,
    ): String {
        val ourPrefix = s3Properties.publicBaseUrl.trimEnd('/') + "/"
        // 같은 외부 URL 이 본문에 여러 번 등장해도(패치노트에서 같은 이미지 재사용) 한 번만 fetch·업로드하고 같은 S3 URL 로 치환한다.
        val rehosted = HashMap<String, String>()
        return AnnouncementBodyImages.rewrite(body) { url ->
            if (url.startsWith(ourPrefix)) null else rehosted.getOrPut(url) { rehostOne(announcementId, url) }
        }
    }

    private fun rehostOne(
        announcementId: Long,
        url: String,
    ): String {
        val fetched = fetcher.fetch(url) // 외부 호출 — SSRF 가드·크기·Content-Type 검증 내장
        val image = AnnouncementImageFile.of(fetched.bytes, fetched.contentType) // 형식·매직바이트 검증
        val key = "announcement/$announcementId/${UUID.randomUUID()}.${image.extension}"
        val newUrl = imageStorage.upload(image.bytes, key, image.mimeType) // 외부 호출
        log.info("공지 이미지 rehost 완료: announcementId={}, key={}", announcementId, key)
        return newUrl
    }
}
