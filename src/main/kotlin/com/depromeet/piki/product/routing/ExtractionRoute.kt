package com.depromeet.piki.product.routing

// 플랫폼(host)별 추출 라우팅 정책의 종류. DB(extraction_platform_policies.route)에 문자열로 저장되고
// 백오피스에서 배포 없이 지정·해제한다. 정책 행이 없는 도메인은 기본 추출 체인을 탄다 — enum 에 DEFAULT 를
// 두지 않는 이유: "행 없음 = 기본" 규약이라 DEFAULT 행이 생길 수 없어야 하고, 소비처는 null(정책 없음)로 받는다.
//
// SUPPORTED 는 그 DEFAULT 와 다르다. DEFAULT 는 "행 없음"의 동어반복이지만 SUPPORTED 는 정보를 더한다 —
// "행 없음"이 "아직 안 봐서 모름"인 반면 SUPPORTED 는 "실측으로 잘 됨을 확인함"이다. 라우팅 동작이 같아도
// 운영자가 아는 사실이 다르므로 값을 나눈다.
//
// 선언 순서는 백오피스 3열 뷰의 열 순서(느슨함 → 강함)로 쓴다. 매칭·판정은 이 순서에 기대지 않는다
// (DbExtractionRoutingPolicy 는 도메인 길이 내림차순 최장 매치로 승자를 정한다).
enum class ExtractionRoute {
    // 기본 추출 체인(정적 fetch → 구조화/LLM)으로 잘 된다고 실측 확인한 플랫폼. 라우팅 동작은 정책 행이
    // 없는 도메인과 같다 — 소비처(verifyRegistrable · HttpProductLinkExtractor)가 UNSUPPORTED · HEADLESS_FIRST
    // 와의 정확한 값 비교만 하므로 이 값은 두 분기를 모두 빗나가 기본 체인을 탄다. 지정 목적은 판정이 아니라
    // 기록이다: 실측 근거(reason)를 남겨, 커버리지를 확인한 플랫폼과 아직 안 본 플랫폼을 백오피스에서 구분한다.
    SUPPORTED,

    // 기본 체인(plain: 정적 HTTP)을 건너뛰고 처음부터 헤드리스 브라우저로 추출한다 — plain 이 항상 차단되는
    // 플랫폼에서 느린-실패(fetch 타임아웃 후 에스컬레이트) 낭비를 없앤다. HttpProductLinkExtractor 가 요청
    // 힌트(headlessFirst)로 extractor 에 실어 보내며, extractor 의 headless 스위치
    // (product.extract.headless.enabled)가 꺼진 환경에선 지정해도 효과가 없다.
    HEADLESS_FIRST,

    // 등록 입력 경계에서 400 으로 거절한다 — fetch 로 상품 정보를 가져올 수 없는 플랫폼(봇 차단 등).
    // 담아봐야 파싱이 무의미하게 실패하고 사용자에겐 틀린 안내("주소를 다시 확인")가 나가기 때문.
    UNSUPPORTED,
}
