package com.depromeet.piki.item.domain

import com.depromeet.piki.common.domain.LongBaseEntity
import com.depromeet.piki.product.domain.CanonicalLink
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.domain.ProductLinkConverter
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.Table

// 상품의 정체성. 외부 입력(link=URL 또는 sourceImageKey=S3 raw 이미지 key)으로 식별되며, 추출값(name·price·image·currency)·상태·
// 이력은 전적으로 ItemSnapshot(버전)이 보유한다 — item 자신은 추출 데이터를 들지 않는 안정적 식별 단위다(4a 에서 추출 필드를 분리).
//
// 정체성의 확정 표식은 canonical(정규화된 귀결점, #825)이다. URL 등록 시점엔 입력 URL 이 임시 정체성 노릇을 하고
// (같은 문자열 재등록은 item_links 별칭으로 이 행에 붙는다), 파싱이 finalUrl 을 돌려주면 claimCanonical 로 확정된다.
// canonical_hash unique 덕에 같은 귀결점의 item 은 하나만 존재한다 — 확정 시도가 충돌하면 이 행이 임시였다는 뜻이라
// 기존 item 으로 병합(snapshot 재부모화)된다.
//
// 입력은 link XOR sourceImageKey 다 — URL 추출 경로는 link 를, 이미지 추출 경로는 sourceImageKey 를 채운다(두 경로가 같은 item 을 만든다).
// 두 값 모두 durable 하므로(URL 문자열·S3 key) outbox 의 dispatch/recover 가 어느 입력이든 재실행할 수 있다.
// 입력은 바뀌면 사실상 다른 상품이라 재등록 영역으로 보고 불변(val).
@Entity
@Table(name = "items")
class Item(
    @Convert(converter = ProductLinkConverter::class)
    @Column(name = "source_url", nullable = true, length = 2048)
    val link: ProductLink? = null,
    // 이미지 등록 경로의 입력 — S3 에 durable 적재한 raw 이미지 object key. link 와 대칭이라 둘 중 하나만 채워진다.
    // 워커가 이 key 로 S3 에서 원본을 다시 읽어 파싱하므로, 메모리 ByteArray 와 달리 유실돼도 recover 가 재실행할 수 있다.
    @Column(name = "source_image_key", nullable = true, length = 1024)
    val sourceImageKey: String? = null,
) : LongBaseEntity() {
    // 정규화된 귀결점(#825 정체성 키). 파싱 성공 시점에 claimCanonical 로 확정되므로 그 전(PENDING·FAILED)엔 null 이다.
    // 이미지 등록 item(link 없음)과 기존 행(forward-only 소급 제외)도 null 로 남는다.
    @Column(name = "canonical_url", length = 2048)
    var canonicalUrl: String? = null
        protected set

    // canonical_url 의 SHA-256 hex — unique 인덱스용 고정 길이 대리키 (utf8mb4 인덱스 상한이 2048자 직접 unique 를 막는다).
    @Column(name = "canonical_hash", length = 64)
    var canonicalHash: String? = null
        protected set

    // 입력은 link XOR sourceImageKey 다 — 둘 다 채워지면 toClaim 이 link 를 우선해 imageKey 가 조용히 무시되는 모호성이 생긴다.
    // 정상 경로(URL 추출 / 이미지 추출)는 한쪽만 채우므로, 둘 다 들어오면 호출부 버그 → 불변식으로 즉시 깬다(둘 다 비어도 됨: 테스트 픽스처).
    init {
        require(listOfNotNull(link, sourceImageKey).size <= 1) {
            "Item 은 link 와 sourceImageKey 중 하나만 가질 수 있다"
        }
    }

    // 파싱이 알아낸 귀결점으로 정체성을 확정한다. 정체성은 불변이 원칙이라 재확정은 코드 버그다(check 500) —
    // 재추출(갱신)은 이미 canonical 이 확정된 item 의 새 버전을 만들 뿐 이 메서드를 다시 타지 않는다.
    // 같은 값 재확정은 멱등 허용: 병합 경합에서 진 쪽이 재시도할 때 같은 귀결점이면 무해하다.
    fun claimCanonical(canonical: CanonicalLink) {
        val current = canonicalHash
        current?.let {
            check(it == canonical.hash) { "이미 canonical 이 확정된 item 의 정체성은 바꿀 수 없다" }
            return
        }
        canonicalUrl = canonical.url
        canonicalHash = canonical.hash
    }
}
