package com.depromeet.piki.user.service

import com.depromeet.piki.common.storage.S3Properties
import org.springframework.stereotype.Component

// 기본 프로필 아바타 URL 발급. 4종 기본 아바타는 S3 이미지 버킷의 `defaults/user-profile-{n}.png` (n=1..COUNT) 로
// 사전 업로드돼 있고(이미지 파일 자체는 운영에서 업로드), 여기선 그 공개 URL 만 조립한다.
// publicBaseUrl 로 조립하므로 dev/prod 버킷 차이를 흡수한다(프로필 업로드와 같은 버킷이라 별도 주소 불필요).
// 탈퇴 tombstone 아바타(deleted)도 같은 `defaults/` 아래 1종으로 함께 발급한다 — 위 랜덤 4종과는 다른 축이라
// COUNT 범위에 넣지 않고 별도 메서드로 둔다(랜덤이 탈퇴 아바타를 뽑으면 안 된다).
@Component
class DefaultProfileImages(
    private val s3Properties: S3Properties,
) {
    // 1..COUNT 중 하나를 랜덤으로 골라 그 기본 아바타의 공개 URL 을 돌려준다.
    fun random(): String = urlOf((1..COUNT).random())

    // URL 조립은 publicBaseUrl 외 의존이 없는 순수 로직이라 단위 테스트로 형식을 망라한다.
    // index 범위 밖은 존재하지 않는 키를 만드는 코드 버그라 불변식으로 막는다(정상 흐름은 random 이 1..COUNT 만 넘김).
    fun urlOf(index: Int): String {
        require(index in 1..COUNT) { "기본 아바타 index 는 1..$COUNT 여야 한다: $index" }
        return "${s3Properties.publicBaseUrl.trimEnd('/')}/$KEY_PREFIX/$FILENAME_PREFIX$index.$EXTENSION"
    }

    // 탈퇴 tombstone 아바타의 공개 URL. 탈퇴해도 공유 토너먼트 히스토리엔 참여자로 남아야 하므로,
    // 식별 가능한 프사를 지우되 빈 값이 아니라 "탈퇴한 유저" 를 나타내는 전용 아바타로 덮는다.
    fun deleted(): String = "${s3Properties.publicBaseUrl.trimEnd('/')}/$KEY_PREFIX/$DELETED_FILENAME.$EXTENSION"

    companion object {
        const val COUNT = 4
        const val KEY_PREFIX = "defaults"
        const val FILENAME_PREFIX = "user-profile-"
        const val DELETED_FILENAME = "user-deleted"
        const val EXTENSION = "png"
    }
}
