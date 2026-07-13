package com.depromeet.piki.image.service

import com.depromeet.piki.product.service.ProductSnapshot

// raw 이미지(등록 시 S3 에 durable 적재한 object key)를 완성된 상품 스냅샷으로 바꾸는 경계 —
// download→OCR→crop→결과 업로드 오케스트레이션 전체를 안고, imageUrl 까지 채워진 스냅샷을 돌려준다(READY 불변식).
// 링크의 ProductLinkExtractor 와 같은 자리다: 워커(AsyncImageParsingWorker)는 상태 전이·재시도 정책·raw 회수만 쥐고,
// "어떻게 추출하나"(원격 extractor 호출)는 이 경계 뒤에 숨는다. 테스트는 이 경계를 stub 으로 격리한다.
interface ImageSnapshotExtractor {
    fun extract(imageKey: String): ProductSnapshot
}
