package com.depromeet.piki.admin.announcement

import com.depromeet.piki.admin.config.ConditionalOnAdminEnabled
import com.depromeet.piki.common.storage.S3Properties
import org.springframework.stereotype.Component

// 백오피스 공지 화면의 푸시 미리보기 아이콘 URL 발급. 아이콘 파일은 이미지 버킷의 `defaults/push-icon.svg` 로
// 사전 업로드돼 있고(랜덤 기본 프사와 같은 공개 `defaults/` 경로), 여기선 그 공개 URL 만 조립한다.
// publicBaseUrl 로 조립하므로 dev/prod 버킷 차이를 흡수한다(DefaultProfileImages 와 같은 방식 — 주소를 박지 않는다).
// 원래는 시스템 알림(actor 없음)의 기본 아바타였으나(#473), 알림 payload 가 더는 imageUrl 을 싣지 않아
// (#473 고도화 — kind 로 라벨·아이콘을 가른다) 알림 발송 경로의 소비자가 사라졌다. 지금 유일한 소비자가
// AdminAnnouncementController 뿐이라 admin 패키지에 둔다 — 알림 발송이 이 이미지를 쓴다는 오독을 막는다.
@Component
@ConditionalOnAdminEnabled
class DefaultPushImage(
    s3Properties: S3Properties,
) {
    val url: String = "${s3Properties.publicBaseUrl.trimEnd('/')}/$KEY"

    companion object {
        const val KEY = "defaults/push-icon.svg"
    }
}
