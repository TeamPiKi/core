package com.depromeet.piki.item.domain

import com.depromeet.piki.common.domain.LongBaseEntity
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.domain.ProductLinkConverter
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.Table

// 상품의 정체성. 외부 입력(link=URL 또는 sourceImageKey=S3 raw 이미지 key)으로 식별되며, 추출값(name·price·image·currency)·상태·
// 이력은 전적으로 ItemSnapshot(버전)이 보유한다 — item 자신은 추출 데이터를 들지 않는 안정적 식별 단위다(4a 에서 추출 필드를 분리).
//
// wish 와 tournament_item 이 함께 참조하는 공유 엔티티. 같은 입력이라도 위시·출전마다 1:1 독립 행이라,
// 한쪽의 수정이 다른 쪽에 번지지 않는다(수정 격리). 입력은 바뀌면 사실상 다른 상품이라 재등록 영역으로 보고 불변(val).
//
// 입력은 link XOR sourceImageKey 다 — URL 추출 경로는 link 를, 이미지 추출 경로는 sourceImageKey 를 채운다(두 경로가 같은 item 을 만든다).
// 두 값 모두 durable 하므로(URL 문자열·S3 key) 작업 큐의 dispatch/recover 가 어느 입력이든 재실행할 수 있다.
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
    // 입력은 link XOR sourceImageKey 다 — 둘 다 채워지면 toClaim 이 link 를 우선해 imageKey 가 조용히 무시되는 모호성이 생긴다.
    // 정상 경로(URL 추출 / 이미지 추출)는 한쪽만 채우므로, 둘 다 들어오면 호출부 버그 → 불변식으로 즉시 깬다(둘 다 비어도 됨: 테스트 픽스처).
    init {
        require(listOfNotNull(link, sourceImageKey).size <= 1) {
            "Item 은 link 와 sourceImageKey 중 하나만 가질 수 있다"
        }
    }
}
