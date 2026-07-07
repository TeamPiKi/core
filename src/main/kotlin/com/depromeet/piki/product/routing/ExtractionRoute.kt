package com.depromeet.piki.product.routing

// 플랫폼(host)별 추출 라우팅 정책의 종류. DB(extraction_platform_policies.route)에 문자열로 저장되고
// 백오피스에서 배포 없이 지정·해제한다. 정책 행이 없는 도메인은 기본 추출 체인을 탄다 — enum 에 DEFAULT 를
// 두지 않는 이유: "행 없음 = 기본" 규약이라 DEFAULT 행이 생길 수 없어야 하고, 소비처는 null(정책 없음)로 받는다.
enum class ExtractionRoute {
    // 등록 입력 경계에서 400 으로 거절한다 — fetch 로 상품 정보를 가져올 수 없는 플랫폼(봇 차단 등).
    // 담아봐야 파싱이 무의미하게 실패하고 사용자에겐 틀린 안내("주소를 다시 확인")가 나가기 때문.
    UNSUPPORTED,

    // 기본 체인(plain: 정적 HTTP)을 건너뛰고 처음부터 헤드리스 브라우저로 추출한다 — plain 이 항상 차단되는
    // 플랫폼에서 느린-실패(fetch 타임아웃 후 에스컬레이트) 낭비를 없앤다. 헤드리스 스위치
    // (product.extract.headless.enabled)가 꺼져 있으면 지정해도 효과가 없다(전량 plain 위임) — 이관 7단계에서 활성화.
    HEADLESS_FIRST,
}
