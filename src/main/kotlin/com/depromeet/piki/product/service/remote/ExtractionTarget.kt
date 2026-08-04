package com.depromeet.piki.product.service.remote

// 추출 경로. 어떤 LLM 모델을 쓸지의 축이며, 원격 계약(프로브 요청의 target)에도 이 이름이 그대로 실린다.
//
// 축을 나누는 이유: 링크는 HTML 에서 텍스트를 읽어 JSON 스키마로 뽑고 이미지는 vision 이라, 한쪽에 맞는 모델이
// 다른 쪽에 맞지 않을 수 있다. 지금은 두 경로가 extractor 안에서 같은 클라이언트를 지나지만 그건 구현 사정이고,
// 운영자가 고르는 단위는 경로별이어야 한다.
enum class ExtractionTarget {
    LINK,
    IMAGE,
}
