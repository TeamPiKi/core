package com.depromeet.piki.explore.service.dto

import java.util.UUID

data class CopyExploreWishCommand(
    val userId: UUID,
    val wishId: Long,
)

data class ExploreWishCopyResult(
    // 내 위시의 id. 이미 가진 상품이면 원래 있던 위시다
    val wishId: Long,
    val alreadySaved: Boolean,
)
