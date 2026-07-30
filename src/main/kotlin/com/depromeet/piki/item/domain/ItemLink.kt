package com.depromeet.piki.item.domain

import com.depromeet.piki.common.domain.LongBaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

// 상품 별칭(#825): "이 링크 모양은 이 item 이더라"의 기록 한 줄. 등록 즉시 원본(정규화 후)이 기록되어
// 파싱 완료 전(pending 창)의 같은 문자열 재등록이 기존 item 에 붙고, 파싱 완료 시 귀결점도 한 줄 추가된다.
// url_hash unique 가 같은 링크 동시 등록을 "한쪽만 성공"으로 직렬화한다(#826 의 바닥).
//
// 행은 불변이다 — 별칭은 "본 적 있다"는 사실의 축적이라 수정 개념이 없고, 병합 시 item_id 이관만
// 리포지토리 벌크 연산으로 일어난다(엔티티를 통한 개별 수정 경로를 두지 않는다).
@Entity
@Table(name = "item_links")
class ItemLink(
    @Column(name = "url", nullable = false, length = URL_MAX_LENGTH)
    val url: String,
    // url 정규형의 SHA-256 hex — unique 색인용 고정 길이 대리키. 값 생성은 CanonicalLink 가 책임진다.
    @Column(name = "url_hash", nullable = false, length = HASH_LENGTH)
    val urlHash: String,
    @Column(name = "item_id", nullable = false)
    val itemId: Long,
) : LongBaseEntity() {
    // 불변식 — url·hash 는 CanonicalLink.of 산출물이라 정상 경로에선 항상 유효하다. 어긋나면 호출부 버그(500).
    init {
        require(url.isNotBlank()) { "별칭 url 은 비어 있을 수 없다" }
        require(url.length <= URL_MAX_LENGTH) { "별칭 url 길이가 ${URL_MAX_LENGTH}자를 초과했다" }
        require(urlHash.length == HASH_LENGTH) { "url_hash 는 SHA-256 hex ${HASH_LENGTH}자여야 한다" }
    }

    companion object {
        const val URL_MAX_LENGTH = 2048
        const val HASH_LENGTH = 64
    }
}
