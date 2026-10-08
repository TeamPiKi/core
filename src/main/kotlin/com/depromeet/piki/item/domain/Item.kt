package com.depromeet.piki.item.domain

import com.depromeet.piki.common.domain.LongBaseEntity
import com.depromeet.piki.product.domain.ProductLink
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table

@Entity
@Table(name = "items")
class Item(

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val sourceType: ParseInputType,

    canonicalUrl: String,

) : LongBaseEntity() {
    // 링크는 정체 URL, 이미지는 이미지 키
    // TODO: 값이 빈 옛 행을 지운 뒤 non-null 로 바꾼다
    @Column(unique = true, length = ProductLink.MAX_LENGTH)
    val canonicalUrl: String? = canonicalUrl

    // 출처 표시용. 이미지 아이템은 쇼핑몰 주소가 없다
    fun sourceLink(): ProductLink? {
        if (sourceType != ParseInputType.LINK) return null
        return canonicalUrl?.let(ProductLink::parse)
    }

    // 원본 이미지는 파싱 뒤 지우므로 지금은 링크만 다시 가져올 수 있다
    fun refreshInput(): ParseInput {
        val url = canonicalUrl ?: throw ItemException.notRefreshable()
        if (sourceType != ParseInputType.LINK) throw ItemException.notRefreshable()
        return ParseInput(sourceType, url)
    }

    companion object {
        fun ofLink(canonical: CanonicalLink): Item = Item(ParseInputType.LINK, canonical.url)

        fun ofImage(key: String): Item = Item(ParseInputType.IMAGE, key)
    }
}
