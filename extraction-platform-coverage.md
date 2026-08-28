# 쇼핑몰 봇 차단 및 상품 추출 가능성 조사

> 조사일: 2026-06-14 · 대상: 피키 상품 추출(위시리스트 등록) 파이프라인
>
> 이 결과는 조사 시점 스냅샷이다. 봇 차단 정책·사이트 HTML 구조는 바뀔 수 있으므로, 의심되면 §2·§8 의 방법으로 재실측한다.

## 1. 한눈 결론

우리 서비스가 어떤 쇼핑몰에서 상품 추출에 성공/실패하는지, 실제 dev 서버 등록 API 까지 통과시켜 확정했다.

- **추출 실패 (봇 차단)**: 쿠팡 · G마켓 · 옥션 · 네이버 스마트스토어
- **추출 성공**: 네이버 브랜드스토어 · 무신사 · 29cm · 나이키 · 카시나(가격 제외) · 카카오 선물하기 · 카카오쇼핑(톡딜)
- **추출 실패 (차단 아닌 다른 이유)**: 카카오 clink (추천리워드 단축링크라 상품 페이지가 아님)
- **대상 아님**: 네이버 가격비교 — 여러 판매처를 비교하는 리스트라 "단일 상품 1개"가 아님 (게다가 fetch 418 차단)

핵심: 차단은 "사람"이 아니라 "우리 서버의 자동 접근"을 막는다. 차단 사이트도 사람이 브라우저로 들어가면 다 열린다. 그리고 **fetch 단계에서 막히면 곧바로 추출 실패(FAILED)로 이어진다.**

## 2. 검증 방법 (2단계, 결과 일치)

| 단계 | 방법 | 무엇을 확인 |
|---|---|---|
| 1차 | 로컬에서 `curl` 로 우리 서비스 fetch 헤더(Chrome UA / Accept / Accept-Language)를 그대로 재현 | fetch HTTP status (차단의 1차 관문) + 받은 HTML 의 상품정보 유무 |
| 2차 | dev 서버(`https://dev.api.piki.day`) 실제 등록 API 호출 후 결과 폴링 | 전체 파이프라인(fetch + 파싱/LLM + DB)을 통과한 최종 status(READY/FAILED) + 추출된 상품명·가격 |

**두 단계 결과가 완전히 일치했다.** 1차에서 fetch 가 막힌 곳은 2차에서 전부 FAILED, 1차에서 상품정보가 있던 곳은 2차에서 전부 READY 였다. 즉 로컬 fetch 재현만으로도 차단 여부를 정확히 예측할 수 있고, dev 호출이 그것을 실제 파이프라인으로 확정했다.

## 3. 통합 결과 (최종)

모든 플랫폼은 사람이 브라우저로 들어가면 정상 접속된다. 아래는 우리 서버 자동 접근 기준. `fetch`=로컬 curl 측정(HTML 받기까지), `dev처리`=등록~READY/FAILED 까지 전체(±5s). URL 은 dev 등록에 실제 사용한 것.

| 플랫폼 / 테스트 URL | dev 결과 | 추출 상품정보 | 성공·실패 이유 | fetch | dev처리 |
|---|---|---|---|---|---|
| **쿠팡**<br>`https://www.coupang.com/vp/products/9491982926?itemId=28270631918&vendorItemId=95223766525` | **FAILED** | - | WAF 가 데이터센터/자동화 트래픽을 IP·지문으로 차단. `Access Denied` 고정 페이지(0.4KB)만 반환해 상품 HTML 자체가 없음 | 63ms (403) | ~6s |
| **G마켓**<br>`https://item.gmarket.co.kr/Item?goodscode=4215487508&buyboxtype=ad` | **FAILED** | - | Cloudflare `Just a moment...` JS 챌린지 페이지 반환. JS 실행 기반 사람 검증이라 단순 HTTP 로는 통과 불가 | 36ms (403) | ~6s |
| **옥션**<br>`https://itempage3.auction.co.kr/DetailView.aspx?itemno=D853983753` | **FAILED** | - | G마켓과 동일한 Cloudflare JS 챌린지(`Just a moment...`) | 35ms (403) | ~6s |
| **네이버 스마트스토어**<br>`https://smartstore.naver.com/coively/products/2685954296` | **FAILED** | - | 네이버 `nfront` 가 429 로 입구 거부. `Retry-After` 없고 요청량·UA 와 무관(단발 요청·UA 변경에도 동일). 같은 `nfront` 의 brand.naver 가 404 정상 처리되는 것과 대조돼 "호스트 차원 거부"로 확정 | 45ms (429) | ~6s |
| **네이버 가격비교**<br>`https://search.shopping.naver.com/catalog/...` | 미등록 | - | 418 하드차단. 또한 여러 판매자 상품을 비교하는 리스트라 "단일 상품 1개" 추출 대상이 아니어서 dev 등록 생략 | 418 | - |
| **네이버 브랜드스토어**<br>`https://brand.naver.com/anker/products/13502275178` | **READY** | 앤커 사운드코어 스페이스2 ... / 189,900 KRW | 200 + `og:*`/JSON-LD 상품 메타 완비. 상품명·가격·이미지 모두 정상 추출 | 135ms (200) | ~6s |
| **무신사**<br>`https://www.musinsa.com/products/6241513` | **READY** | 비바라비다 Clam 맨즈 크롭 반팔 ... / 26,900 KRW | 200 + `product:price:amount`·`currency` 메타까지 포함해 가장 풍부 | 116ms (200) | ~6s |
| **29cm**<br>`https://www.29cm.co.kr/products/3914528` | **READY** | PLEATED LINEN WRAP SKIRT (BURGUNDY) / 206,100 KRW | 200 + 상품 메타 완비 | 126ms (200) | ~6s |
| **나이키**<br>`https://www.nike.com/kr/t/%EB%8C%80%ED%95%9C%EB%AF%BC%EA%B5%AD-2026-%EC%8A%A4%ED%83%80%EB%94%94%EC%9B%80-%EC%96%B4%EC%9B%A8%EC%9D%B4-%EB%82%A8%EC%84%B1-%EB%93%9C%EB%9D%BC%EC%9D%B4-%ED%95%8F-%EC%B6%95%EA%B5%AC-%EB%A0%88%ED%94%8C%EB%A6%AC%EC%B9%B4-%EC%A0%80%EC%A7%80-xkbOT39p/IB5386-567` | **READY** | 대한민국 2026 스타디움 어웨이 ... 저지 / 135,000 KRW | 200, HTML 764KB 로 무겁지만 상품 메타가 있어 정상. dev 11s 는 큰 HTML + LLM 처리 영향 | 303ms (200) | ~11s |
| **카시나**<br>`https://www.kasina.co.kr/product-detail/133715854` | **READY** (가격 누락) | 나이키 드라이 핏 에너지 반팔 티셔츠 / 가격 null | 200 + 상품명은 추출됐으나 가격 메타 위치가 달라 가격만 놓침. 봇 차단이 아니라 **우리 파서/LLM 의 개선 영역** | 128ms (200) | ~16s |
| **카카오 선물하기**<br>`https://gift.kakao.com/product/2370524` | **READY** | [단독]하겐다즈 프리미엄 ... 케이크 / 32,900 KRW | 200, `<title>`은 generic 이지만 `og:title`·`og:image` 가 있고 LLM 이 본문에서 가격까지 추출 | 70ms (200) | ~11s |
| **카카오 clink**<br>`https://clink.kakao.com/sp/psly37kBPtIJ0VY9?ref=SHARE_AF` | **FAILED** | - | 200 이지만 추천리워드 단축링크(3KB 껍데기)라 상품정보 자체가 없음. 봇 차단이 아니라 비(非)상품 URL | 51ms (200) | ~11s |
| **카카오쇼핑 (톡딜)**<br>`https://store.kakao.com/koreasusan/products/517667572?ref=SHARE` | **READY** | [대한민국농수산] 제주 한돈 왕구이 1kg+1kg / 16,900 KRW | 루트는 SPA 지만 상품 페이지엔 `og:*`+JSON-LD 메타가 있어 정상 추출 | 105ms (200) | ~26s |

## 4. 차단 유형 분석

| 사이트 | 차단 방식 | fetch status | 뚫기 난이도 |
|---|---|---|---|
| 쿠팡 | `Access Denied` (IP/지문 기반 WAF) | 403 | 높음 (단순 HTTP 불가) |
| G마켓 · 옥션 | Cloudflare `Just a moment...` JS 챌린지 | 403 | 매우 높음 (헤드리스 브라우저로도 까다로움) |
| 네이버 스마트스토어 | 입구 거부 (`server: nfront`, `Retry-After` 없음) | 429 | 높음, 단 시점/IP 따라 변동 가능 |
| 네이버 가격비교 | 하드차단 | 418 | 높음 |

**스마트스토어 429 는 우리 요청량과 무관하다.** 단발/저빈도 요청에도, UA 를 바꿔도 항상 429 다. 같은 `nfront` 인프라의 brand.naver 에 똑같이 존재하지 않는 임의 상품 ID 를 던지면 404(정상 처리)가 나오는 것과 대조하면, 429 는 "URL 이 없어서"가 아니라 "스마트스토어 호스트가 접근 자체를 입구에서 거부"하는 것이다. 실존 상품 URL 로도, dev 실제 등록에서도 동일하게 실패가 재확인됐다.

## 5. 같은 브랜드여도 경로(호스트)별로 갈린다

**네이버** (호스트마다 결과가 완전히 다름)
- `brand.naver.com` (브랜드스토어) = 추출 성공 (READY)
- `smartstore.naver.com` (개인 스마트스토어) = 429 입구 거부 → FAILED
- `search.shopping.naver.com` (가격비교) = 418 하드차단 + 애초에 단일 상품 페이지가 아님

**카카오** (status 는 다 200 = 차단 아님. 단 "추출 가능"은 상품정보 유무에 달림)
- `gift.kakao.com` (선물하기) = 추출 성공 (READY). `<title>` 은 generic 했지만 HTML 에 `og:title`·`og:image` 가 있고, LLM 이 본문에서 가격(32,900 KRW)까지 뽑아냈다. **단축링크(`kko.to`)로 등록해도 cross-domain 단축·딥링크 추출 지원 덕에 따라가서 성공**: `kko.to/SDOiGd50Rq` → `gift.kakao.com/product/11636454` (아디다스 새틴 숄더백 34,650 KRW dev 실측, `sourceUrl` 은 원본 `kko.to` 유지)
- `store.kakao.com` (카카오쇼핑/톡딜) = 추출 성공 (READY). 루트는 SPA(og generic `톡딜`)지만 **개별 상품 페이지엔 `og:title`·`og:image`+JSON-LD 가 실려 있어 추출됨**: `koreasusan/products/517667572` → 제주 한돈 왕구이 16,900 KRW dev 실측. 단 톡딜은 카카오톡 앱 내 소비가 중심이라 웹 상품 URL 공유는 드문 편
- `clink.kakao.com` (추천리워드 단축링크) = 3KB 껍데기라 상품 페이지가 아님 → FAILED

## 6. status 200 ≠ 추출 가능

차단(HTTP status)과 추출 가능 여부(HTML 에 상품정보가 실리는지)는 **다른 축**이다.

- 200 을 받아도 상품정보가 HTML 에 없으면(JS 렌더 SPA · 단축링크 껍데기) 추출이 안 된다 → 카카오 clink 가 200 이지만 FAILED.
- 추출에 성공한 사이트는 모두 `og:*` 또는 JSON-LD 같은 상품정보 메타가 HTML 에 실려 있다. 무신사는 `product:price:amount`·`currency` 까지 포함해 가장 풍부하다.
- 반대로 카시나처럼 200 + 상품명은 있어도 가격 메타 위치가 달라 가격만 누락될 수 있다(부분 추출).

## 7. 시간 해석

개별 수치는 §3 표의 `fetch`·`dev처리` 컬럼 참조. 패턴만 요약하면:
- **차단되는 곳이 더 빠르다**: fetch 35~63ms(차단 페이지만 즉시 반환) → 전체 약 6초만에 FAILED.
- **정상은** fetch 110~303ms(HTML 다운로드, 나이키가 764KB 로 최대) → 전체 6~16초 READY(LLM/파서 추출 포함).

주의:
- fetch 는 추출의 **첫 단계만**이다. 정상 플랫폼의 실제 등록 체감 시간은 전체값(6~16초)이다.
- `dev처리` 는 폴링 5초 간격이라 ±5s 오차가 있다. 단계별 정확한 latency 는 분산 트레이싱(fetch HTTP client span + Gemini span)으로 봐야 한다.
- fetch(로컬 집/회사 IP) 절대값은 운영과 다를 수 있다. dev(AWS 서울)가 운영에 가까운 실측이다.

## 8. 테스트 환경 · 방법 (재실측용)

### fetch 헤더 (운영 `HttpPageFetcher` / `PageFetchHttpClientConfig` 와 동일)
| 항목 | 값 |
|---|---|
| User-Agent | `Mozilla/5.0 ... Chrome/125.0.0.0 Safari/537.36` (브라우저 위장) |
| Accept | `text/html,application/xhtml+xml,*/*;q=0.8` |
| Accept-Language | `ko,en;q=0.9` |
| connect / read timeout | 5s / 15s |

### dev 등록 API 호출 흐름
1. `POST /api/v1/auth/guest` (헤더 `X-Client-Type: app`) → body 로 게스트 토큰
2. `POST /api/v1/dev/users` (게스트 토큰 + `{"nickname":"..."}`) → MEMBER 토큰
3. `POST /api/v1/wishlists` (MEMBER 토큰 + `{"url":"..."}`) → 201 `status=PENDING` (비동기)
4. `GET /api/v1/wishlists` 폴링 → `status` 가 `PENDING → PROCESSING → READY/FAILED` 로 전이, READY 면 name·currentPrice·currency·imageUrl 채워짐
- 인증: 기본 WEB(토큰을 HttpOnly 쿠키로 내림). `X-Client-Type: app` 헤더를 보내야 body 로 토큰 수신.
- 레이트리밋: `POST /api/v1/wishlists` 는 전역 그물(`piki_general`, IP당 초당 20회)과 계정별 등록 quota(시간당 30회)를 받는다.

> 테스트한 전체 URL 은 §3 표의 각 플랫폼 행에 풀로 기재돼 있다.
