---
paths: ["src/main/**/SecurityConfig.kt", "src/main/**/*Filter.kt", "src/main/**/*Controller.kt", "src/main/kotlin/com/depromeet/piki/admin/**", "src/main/resources/templates/**"]
---

# 웹 요청 경계에서 반복해 틀리는 것

전부 실제로 이 repo 에서 한 번씩 났던 결함이다(#986·#988). 문법이 멀쩡하고 테스트도 초록불이라 **코드만 봐서는 티가 안 나는 종류**라 여기 못박는다.

## 판단이 필요해 사람·모델이 지켜야 하는 것

- **경로로 접근을 판정할 땐 raw `request.requestURI` 가 아니라 `UrlPathHelper` 정규화 경로를 쓴다.** dispatcher 는 디코딩 경로로 라우팅하므로 `/%61dmin/...` 이 필터만 건너뛰고 컨트롤러엔 닿는다.
- **상태를 바꾸는 요청은 fetch 여도 CSRF 를 면제하지 않는다.** "JSON API 라 토큰을 못 싣는다" 는 틀렸다 — 헤더로 실으면 된다.
- **신원이 올라가는 시점(로그인·grant)에 기존 세션을 버리고 새로 발급한다.** `getSession(true)` 만 부르면 공격자가 미리 심어둔 세션 id 에 권한이 얹힌다.
- **`permitAll` 매처·필터 예외는 실제로 서빙하는 대상이 있을 때만 둔다.** 빈 채로 두면 나중에 그 경로에 놓이는 것이 무인증 공개가 되고, 보안 설정에 이미 있어 의도한 것처럼 보인다.
- **SSR 컨트롤러(Thymeleaf 반환)는 계약 예외를 잡아 리다이렉트한다.** 안 잡으면 `@RestControllerAdvice` 가 화면을 raw JSON 으로 갈아치워 운영자가 페이지를 잃는다.
- **서블릿 필터의 `@Order` 는 유일값으로 둔다.** 값이 겹치면 순서가 비명세 규칙으로 갈려, 차단 필터가 로깅 필터 바깥으로 밀리면 차단 기록 자체가 안 남는다.
- **브라우저가 스스로 반복하는 요청엔 종료 조건과 상한을 둔다.** 대상이 사라져도 멈추지 않으면 탭 하나가 시간당 수천 건을 보낸다.
- **외부 CDN 자원은 버전을 고정하고 SRI 를 건다. 단 SRI 를 걸었으면 실제 로드를 눈으로 확인한다.** CORS 헤더를 안 주는 CDN 은 `crossorigin` 이 붙는 순간 리소스를 통째로 차단해, 검사 없이 두는 것보다 나쁜 결과가 된다.

## 훅이 차단하는 것 (`.claude/settings.json`)

기계가 오탐 없이 판정하므로 산문으로 반복하지 않는다. 차단 메시지가 옳은 형태를 알려준다.

- 클라이언트 IP 를 `X-Forwarded-For` 에서 직접 읽기 → `ClientIp.of`
- `th:utext` → `th:text` (값 출처가 DB 로 바뀌면 저장형 XSS)
- `${param.x == 'v'}` → `${param.x != null and param.x[0] == 'v'}` (`param.x` 는 `String[]` 이라 직접 비교는 항상 false)
- admin 패키지 빈의 `@ConditionalOnAdminEnabled` 누락
