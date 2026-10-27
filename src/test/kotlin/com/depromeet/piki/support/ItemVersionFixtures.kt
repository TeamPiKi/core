package com.depromeet.piki.support

import com.depromeet.piki.contracts.extraction.v1.ExtractionMethod
import com.depromeet.piki.item.domain.ItemSnapshot
import java.util.UUID

// 값은 기본이 READY. 필드를 null 로 넘기면 INCOMPLETE 버전이 된다
fun itemVersion(
    itemId: Long,
    name: String? = "상품",
    price: Int? = 1_000,
    imageUrl: String? = "https://img.example.com/p.png",
    currency: String? = "KRW",
    userInput: Boolean = false,
    method: ExtractionMethod? = ExtractionMethod.STRUCTURED.takeUnless { userInput },
    createdBy: UUID = UUID.randomUUID(),
): ItemSnapshot =
    ItemSnapshot(
        itemId = itemId,
        name = name,
        imageUrl = imageUrl,
        price = price,
        currency = currency,
        method = method,
        userInput = userInput,
        createdBy = createdBy,
    )
