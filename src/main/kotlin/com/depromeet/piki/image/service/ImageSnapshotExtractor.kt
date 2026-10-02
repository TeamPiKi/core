package com.depromeet.piki.image.service

import com.depromeet.piki.product.service.ProductSnapshot

// 원본 이미지 키를 받아 상품 스냅샷을 돌려줌. 대표 이미지(imageUrl)까지 채워 옴
interface ImageSnapshotExtractor {
    fun extract(imageKey: String): ProductSnapshot
}
