package com.depromeet.piki.item.domain

import com.depromeet.piki.common.domain.LongBaseEntity
import com.depromeet.piki.product.domain.ProductLink
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name = "items")
class Item(
    // 유니크는 DB에서 처리 -> V20261004060615__generate_items_canonical_hash.sql
    // 이미지 아이템과 정규화할 수 없는 링크 -> null
    @Column(length = ProductLink.MAX_LENGTH)
    val canonicalUrl: String?,
) : LongBaseEntity() {
    // 새로고침·출처 표시의 URL. 정체 URL 이 없으면 다시 가져올 곳도 없다
    fun canonicalLink(): ProductLink? = canonicalUrl?.let(ProductLink::parse)
}
