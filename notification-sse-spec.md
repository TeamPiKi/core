# 실시간 알림 (SSE) 명세

> API docs(OpenAPI/Stoplight)는 요청→단일 JSON 응답 모델이라 **스트림(text/event-stream)** 을 제대로 표현하지 못한다. 이 문서는 그 공백을 메우는 클라이언트용 명세다. (자동 생성 docs 아님, 수동 관리)

---

## 1. 개요

- 인증된 유저가 **자기 알림 스트림 1개**를 열어두면, 개인 알림과 토너먼트 알림이 모두 그 스트림으로 흘러온다.
- 프로토콜: **SSE (Server-Sent Events)**, 단방향(서버→클라이언트), `text/event-stream`.
- 토너먼트 알림은 해당 토너먼트 참여자에게만 fan-out 되므로, **스트림은 유저당 1개면 충분**하다 (토너먼트별로 따로 열 필요 없음).

---

## 2. 엔드포인트

| 항목 | 값 |
|---|---|
| Method | `GET` |
| Path | `/api/v1/notifications/subscribe` |
| 응답 Content-Type | `text/event-stream` |
| 인증 | 필요 (GUEST 포함 인증 유저면 OK) |

---

## 3. 인증 (중요 — 클라이언트 구현이 갈리는 지점)

서버는 토큰을 **이 우선순위**로 읽는다:

1. `Authorization: Bearer <accessToken>` 헤더 (우선)
2. 없으면 `access_token` **쿠키**

### 플랫폼별 권장

- **WEB (브라우저 네이티브 `EventSource`)**
  - 브라우저 `EventSource` 는 **커스텀 헤더(`Authorization`)를 보낼 수 없다.** 따라서 **쿠키 인증**에 의존한다.
  - 같은 도메인/CORS 설정에서 `access_token` 쿠키가 자동 전송되도록 `withCredentials: true` 로 연다.
    ```js
    const es = new EventSource("/api/v1/notifications/subscribe", { withCredentials: true });
    ```
  - 헤더로 토큰을 꼭 실어야 하면 `EventSourcePolyfill` 같은 라이브러리로 헤더 주입이 가능하다.
- **APP (직접 HTTP 스트림 처리)**
  - `Authorization: Bearer <accessToken>` 헤더로 보내면 된다.

> 미인증/유효하지 않은 토큰 → **401** (아래 8번). 스트림이 열리기 전에 거절된다.

---

## 4. 스트림에 흐르는 이벤트 4종

SSE 한 연결 위로 아래 네 가지가 흐른다. 클라이언트는 **이벤트 `name`** 으로 구분한다.

### (1) `connect` — 연결 성립 신호

구독 직후 **1회** 전송. 응답 헤더를 즉시 flush 해 클라이언트가 "연결됨"을 곧장 인지하게 한다.

```text
event: connect
data: connected
```

### (2) `notification` — 알림 1건 (핵심)

알림이 발생할 때마다 전송. `data` 는 아래 [5. payload](#5-notification-payload-스키마) JSON.

```text
event: notification
data: {"id":123,"type":"TOURNAMENT_JOINED","kind":"TOURNAMENT","title":"홍길동님이 참가했어요","body":"","refId":45,"isRead":false,"createdAt":"2026-06-06T14:32:10"}
```

아이템 좌표가 실리는 타입(토너먼트 출처 파싱 알림 · `TOURNAMENT_ITEM_DELETED`)은 공통 필드 뒤에 `tournamentId`·`tournamentItemId` 가 더 붙는다.

```text
event: notification
data: {"id":124,"type":"ITEM_PARSING_COMPLETED","kind":"TOURNAMENT","title":"상품 정보가 저장됐어요","body":"","refId":513,"isRead":false,"createdAt":"2026-06-06T14:33:02","tournamentId":99,"tournamentItemId":555}
```

### (3) `silent-sync` — 조용한 화면 갱신 신호 (알림 아님)

**이건 "알림"이 아니라 화면 갱신 신호다.** 알림센터에 쌓이지 않고 FCM 표시 푸시도 가지 않으며, 오직 열려 있는 SSE 연결로만 흐른다(`notification` 과 구분 — `silent` 은 토스트 없이 조용히 화면만 갱신함을 뜻한다). 한 채널로 여러 갱신 사건이 흐르고, 클라는 payload 의 **`type`** 으로 사건을 분기한다. `data` 는 아래 [5-1. payload](#5-1-silent-sync-payload-스키마) JSON.

```text
event: silent-sync
data: {"type":"TOURNAMENT_ITEM_PARSED","tournamentId":99,"tournamentItemId":555,"status":"READY"}
```

현재 흐르는 `type`:

| `type` | 의미 | 클라 동작 |
|---|---|---|
| `TOURNAMENT_ITEM_PARSED` | 토너먼트 출전 아이템의 파싱 완료/실패(`READY`/`FAILED`) | `(tournamentId, tournamentItemId)` 로 그 출전 카드를 찾아 `status` 반영 (`READY`=상품 정보, `FAILED`=에러/재시도). 또는 그 토너먼트 아이템 목록 재조회. |
| `UNREAD_COUNT_CHANGED` | 읽음 처리로 본인 안읽음 수가 바뀜 (멀티 디바이스 동기화) | `unreadCount`(전체)를 그대로 인앱 배지에 미러링 |

`TOURNAMENT_ITEM_PARSED` 상세:
- **수신자**: 그 토너먼트 참여자 **전원**(아이템을 올린 주최자 포함). 한 아이템이 여러 토너먼트에 출전 중이면 각 토너먼트 참여자가 **각자 그 토너먼트의 좌표**로 받는다.
- 주최자가 아이템을 추가해 참여자 화면에 뜬 **PENDING 로딩 카드**(`notification` 의 `TOURNAMENT_ITEM_ADDED` 로 뜸)를 파싱 완료/실패로 마무리한다.
- 위시로만 담긴(어느 토너먼트에도 없는) 아이템의 파싱 완료/실패는 오지 않는다 — 위시 주인은 `notification`(`ITEM_PARSING_*`)으로 받는다.

### (4) 하트비트 (주석 ping)

약 **30초 간격**. 연결 유지(프록시 read timeout 회피)용이며 **`data` 이벤트가 아니다.** SSE 주석 라인(`:` 으로 시작)이라 `EventSource` 의 `onmessage`/리스너에 잡히지 않는다. **클라이언트는 무시하면 된다.**

```text
: ping
```

---

## 5. notification payload 스키마

`notification` 이벤트의 `data` 로 직렬화되는 JSON.

| 필드 | 타입 | 설명 |
|---|---|---|
| `id` | number (long) | 알림 식별자. 추후 읽음 처리 API 의 키. |
| `type` | string (enum) | 알림 종류. 딥링크 분기 키. (아래 표) |
| `kind` | string (enum) | 알림의 도메인 축. `WISH` \| `TOURNAMENT` \| `SYSTEM`. **전 알림에 항상 실리는 공통 필드**이며, 알림 카드의 라벨·아이콘(위시/토너먼트/시스템)이 된다. `type` + 발행 출처에서 서버가 파생한다. |
| `title` | string | 표시용 제목. 발송 시점에 변수 치환이 끝난 완성본. |
| `body` | string | 표시용 본문. **현재는 전 타입 빈 문자열(`""`)** (후속 템플릿 작업에서 채워질 예정). |
| `refId` | number (long) | 딥링크 대상 식별자. `type` 에 따라 가리키는 대상이 다름 (아래 표). |
| `isRead` | boolean | 읽음 여부. SSE 로 즉시 도착하는 알림은 사실상 `false`. |
| `createdAt` | string (ISO-8601 LocalDateTime) | 생성 시각. 예: `2026-06-06T14:32:10`. 타임존 오프셋 없음(서버 로컬). |
| `tournamentId` | number (long) \| 생략 | **아이템 좌표를 싣는 `type` 에만.** 딥링크로 입장할 토너먼트. |
| `tournamentItemId` | number (long) \| 생략 | **아이템 좌표를 싣는 `type` 에만.** 입장한 토너먼트 안에서 지목할 출전 아이템. |

> **아이템 좌표(`tournamentId`·`tournamentItemId`)는 `type` 이 가른다 — `kind` 가 아니다.** 좌표가 실리는 건 ① 토너먼트 출처 파싱 알림(`ITEM_PARSING_*` + `kind`=TOURNAMENT) ② 아이템 삭제(`TOURNAMENT_ITEM_DELETED`) 둘뿐이다. 토너먼트 소셜 알림(`TOURNAMENT_JOINED` 등)도 `kind`=TOURNAMENT 지만 좌표는 **없다** — `kind === "TOURNAMENT"` 만 보고 좌표 존재를 단정하면 `undefined` 를 들고 이동하게 된다. 값이 없는 키는 **JSON 에서 아예 생략**된다(NON_NULL). 서버는 완성 URL 을 박지 않고 도메인 식별자만 내려보내며, URL 조립은 클라이언트가 한다.

> `kind` 는 옛 `category`(ACTIVITY/SYSTEM)와 옛 `imageUrl`(아바타)을 대체한다. 두 필드는 **응답에서 제거됐다** — 카드는 아바타 대신 `kind` 라벨·아이콘으로 그린다.

---

## 5-1. `silent-sync` payload 스키마

`silent-sync` 이벤트의 `data` 로 직렬화되는 JSON. `notification` payload 와 **별개 셰입**이며, 알림 식별자(`id`)·제목·읽음 같은 알림 필드가 없다 — 알림이 아니라 화면 갱신 신호이기 때문. **공통 필드는 사건 종류를 가리키는 `type` 하나**이고, 나머지 필드는 `type` 별로 다르다(클라는 `type` 으로 먼저 분기한다).

### `type = TOURNAMENT_ITEM_PARSED`

| 필드 | 타입 | 설명 |
|---|---|---|
| `type` | string (enum) | 항상 `"TOURNAMENT_ITEM_PARSED"`. |
| `tournamentId` | number (long) | 갱신 대상 출전 카드가 속한 토너먼트. |
| `tournamentItemId` | number (long) | 그 토너먼트 안에서 갱신할 출전 아이템(카드). 추가 시점에 받은 목록의 그 항목과 같은 id. |
| `status` | string (enum) | 파싱 결과. **`READY`(완료) \| `FAILED`(실패)** 둘 중 하나만 실린다. 토너먼트 아이템 목록 응답의 `status` 와 같은 값 체계. |

> 같은 아이템이 여러 토너먼트에 출전 중이면, 각 토너먼트 참여자는 **그 토너먼트의 `(tournamentId, tournamentItemId)`** 좌표로 각자 한 건씩 받는다. (`notification` 파싱 알림이 단일 출처 좌표만 싣는 것과 달리, 이 동기화는 모든 출전 토너먼트로 fan-out 한다.)

### `type = UNREAD_COUNT_CHANGED`

읽음 처리(POST `/api/v1/notifications/read`) 후, 같은 유저의 다른 **열린 기기 인앱 배지**를 맞추는 신호. REST 읽음 응답과 같은 값(`unreadCount`)이라 클라는 +1/-1 산수 없이 그대로 미러링한다.

```text
event: silent-sync
data: {"type":"UNREAD_COUNT_CHANGED","unreadCount":1}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| `type` | string (enum) | 항상 `"UNREAD_COUNT_CHANGED"`. |
| `unreadCount` | number (long) | 처리 후 전체 안읽음 수(앱 badge). |

> 읽은 기기 자신도 받지만(그 유저의 모든 연결로 fan-out), 응답 body 와 같은 값이라 멱등이다. 오프라인 기기의 OS 아이콘 badge 는 FCM silent push 가 따로 맞춘다 — **온라인 인앱 배지=SSE, 오프라인 OS 배지=FCM** 으로 두 경로가 같은 수로 수렴한다.

---

## 6. 알림 타입(`type`)별 의미 — 딥링크 분기표

클라이언트는 `type` 으로 화면을 분기하고 `refId` 로 이동 대상을 정한다. `kind` 는 카드 라벨·아이콘용으로 함께 실린다.

| `type` | `kind` | 의미 | `title` (현재 문구) | `refId` | 아이템 좌표 | 수신자 |
|---|---|---|---|---|---|---|
| `TOURNAMENT_JOINED` | `TOURNAMENT` | 누군가 토너먼트에 참가 | `{참가자}님이 참가했어요` | **tournamentId** | 없음 | 참여자 (행위자 제외) |
| `TOURNAMENT_ITEM_ADDED` | `TOURNAMENT` | 누군가 아이템 추가 | `{참가자}님이 아이템을 추가했어요` | **tournamentId** | 없음 | 참여자 (행위자 제외) |
| `TOURNAMENT_ITEM_DELETED` | `TOURNAMENT` | 누군가 아이템 삭제 | `{참가자}님이 '{상품명}'을(를) 삭제했어요` | **tournamentId** | **있음** | 참여자 (행위자 제외) |
| `TOURNAMENT_STARTED` | `TOURNAMENT` | 주최자가 토너먼트 시작 | `{주최자}님이 토너먼트를 시작했어요` | **tournamentId** | 없음 | 참여자 (주최자 제외) |
| `TOURNAMENT_PLAYED_FROM_LINK` | `TOURNAMENT` | 플레이링크로 내 토너먼트 플레이 시작 | `{플레이어}님이 회원님 토너먼트를 플레이했어요` | **ROOT 토너먼트 id** | 없음 | ROOT 주최자 |
| `TOURNAMENT_COMPLETED` | `TOURNAMENT` | 멤버/게스트가 클론 완료 | `{멤버}님이 회원님 토너먼트를 완료했어요` | **ROOT 토너먼트 id** | 없음 | ROOT 주최자 |
| `TOURNAMENT_RESULT_READY` | `TOURNAMENT` | 주최자가 ROOT 완료(결과 나옴) | `참여하신 {주최자}님의 토너먼트 결과가 나왔어요` | **ROOT 토너먼트 id** | 없음 | 참여자 (주최자 제외) |
| `ITEM_PARSING_COMPLETED` | 출처에 따라 `WISH` \| `TOURNAMENT` | 내 상품 정보 추출 성공 | `상품 정보가 저장됐어요` | **itemId** | `TOURNAMENT` 출처면 있음 | 본인(위시 주인/등록자) |
| `ITEM_PARSING_FAILED` | 출처에 따라 `WISH` \| `TOURNAMENT` | 내 상품 정보 추출 실패 | `상품 정보를 가져오지 못했어요` | **itemId** | `TOURNAMENT` 출처면 있음 | 본인(위시 주인/등록자) |
| `ANNOUNCEMENT` | `SYSTEM` | 전체 공지(관리자, 후속) | (관리자 입력) | **공지 id/0** | 없음 | 토큰 보유 유저(후속) |

> `title` 문구는 서버 템플릿에서 렌더된 값이라 바뀔 수 있다. 클라이언트는 **문구가 아니라 `type` 으로 분기**할 것.

> `kind` 는 파싱 알림(`ITEM_PARSING_*`)만 발행 출처에 따라 갈린다 — 같은 `type` 이 위시 등록·토너먼트 추가 두 플로우에서 발행되기 때문. 나머지 타입은 위 표의 값 하나로 고정이다.

### 파싱 알림(`ITEM_PARSING_*`)의 출처별 라우팅

파싱 알림은 위시 등록·토너먼트 추가 두 플로우에서 모두 발행되므로, `type`·`refId` 만으론 어디로 보낼지 알 수 없다. **그 두 타입에 한해** `kind` 와 토너먼트 식별자로 분기한다.

| `kind` | 추가로 실리는 필드 | 이동 대상 |
|---|---|---|
| `WISH` | 없음 | `/archive` |
| `TOURNAMENT` | `tournamentId` · `tournamentItemId` | `/tournament/{tournamentId}/create` 로 입장 후 `tournamentItemId` 로 그 아이템 지목 |

- **이 `kind` 분기는 `case ITEM_PARSING_*` 안에서만 유효하다.** 다른 `type` 에도 `kind`=TOURNAMENT 가 실리므로(소셜 알림 전부), `type` 분기 없이 `kind` 만 보면 좌표 없는 알림이 이 경로로 새어 들어온다.
- `refId`(=itemId)는 두 출처 공통으로 항상 실리지만, 위시·토너먼트 이동에는 직접 쓰이지 않는다(향후 아이템 단위 참조용).
- 한 아이템은 파싱 시점에 단일 출처라 라우팅 식별자도 단건이다 (여러 토너먼트 동시 출전 같은 복수 대상은 발생하지 않는다).

---

## 7. 연결 수명 (lifecycle)

| 항목 | 값 | 비고 |
|---|---|---|
| 연결 타임아웃 | **30분** | 만료 시 서버가 연결을 닫음. |
| 하트비트 주기 | **30초** | nginx proxy_read_timeout(60s) 아래로 유지. |
| 재연결 | **클라이언트 책임** | 끊기면(타임아웃·네트워크) 재호출해 다시 연다. |
| 인증 검증 시점 | **연결을 여는 순간 1회만** | 아래 "토큰 만료" 참고. |

- `EventSource` 는 연결이 끊기면 **자동 재연결**을 시도한다(브라우저 기본 동작). APP 직접 구현은 재연결 로직을 직접 넣어야 한다.
- 다중 탭/기기 접속을 허용한다 (한 유저가 여러 연결을 동시에 열 수 있고, 알림은 그 전부에 전달된다).

### 토큰 만료와 재연결 (특히 APP)

인증은 **연결을 여는 순간 1회만** 검증된다 (SSE 는 단일 요청이 길게 열려 있는 형태라, 인증 필터가 그 시점에 한 번만 돈다).

- **연결 도중 access token 이 만료돼도 그 연결은 끊기지 않는다** (최대 30분 타임아웃까지 유지). 알림은 계속 도착한다.
- 문제는 **재연결 시점**이다. 타임아웃·네트워크 끊김으로 다시 열 때, 그때 들고 있는 토큰이 만료됐으면 **401 로 거절**된다.
- 따라서 클라이언트는 **재연결 전에 토큰 유효성을 확인하고, 만료됐으면 refresh 한 뒤 재연결**해야 한다.
  - WEB(쿠키 인증): `access_token` 쿠키가 만료되면 `/api/v1/auth/token/refresh` 로 갱신(쿠키 재발급) 후 재연결.
  - APP(헤더 인증): refresh 로 새 access token 을 받아 **재연결 요청의 `Authorization` 헤더에 새 토큰**을 실어 연다.

### 끊김 중 발생한 알림 (현재 한계)

- 현재 SSE 는 **연결 중에 발생한 알림만** 실시간 전달한다. **연결이 끊겨 있던 동안 쌓인 알림은 재연결해도 스트림으로 다시 오지 않는다.**
- 단, 모든 알림은 DB(`notifications`)에 영속되므로, **알림 히스토리 조회 API**(`GET /api/v1/notifications`)로 놓친 알림을 따라잡는다. 응답 항목 셰입은 이 문서의 `notification` payload 와 동일하고, `data.unreadCount` 로 배지도 함께 맞춘다.
- `silent-sync`(조용한 화면 갱신)는 **영속되지 않는다** — 끊겨 있던 동안의 파싱 완료/실패는 재전송되지 않는다. 하지만 재연결·앱 진입 시 토너먼트 아이템 목록을 재조회하면 그 시점의 최신 `status`(READY/FAILED)가 그대로 내려오므로 자연히 따라잡힌다.
- 따라서 권장 클라이언트 패턴: **앱 진입/재연결 시 목록 API 로 동기화 + SSE 로 실시간 갱신.**

---

## 8. 에러 응답

스트림이 열리기 전 단계의 거절은 일반 JSON(`ApiResponseBody`) 으로 내려간다.

| 상태 | 의미 | 응답 형태 |
|---|---|---|
| **401** | 미인증 (토큰 없음/유효하지 않음) | `ApiResponseBody` JSON (`status`, `code`, `detail`) |

---

## 9. 클라이언트 구현 예시

### WEB (브라우저, 쿠키 인증)

```js
const es = new EventSource("/api/v1/notifications/subscribe", { withCredentials: true });

es.addEventListener("connect", () => {
  console.log("SSE 연결됨");
});

es.addEventListener("notification", (e) => {
  const n = JSON.parse(e.data);
  // 반드시 n.type 으로 먼저 분기한다. n.kind 는 라벨·아이콘용이며, kind 만으로는 좌표 유무를 알 수 없다
  // (소셜 알림도 kind === "TOURNAMENT" 지만 tournamentId 가 없다).
  renderKindBadge(n.kind); // "WISH" | "TOURNAMENT" | "SYSTEM" → 카드 라벨·아이콘
  switch (n.type) {
    case "TOURNAMENT_JOINED":
    case "TOURNAMENT_ITEM_ADDED":
    case "TOURNAMENT_STARTED":
    case "TOURNAMENT_PLAYED_FROM_LINK":
    case "TOURNAMENT_COMPLETED":
    case "TOURNAMENT_RESULT_READY":
      goToTournament(n.refId); // refId = tournamentId
      break;
    case "TOURNAMENT_ITEM_DELETED":
      // 이 type 은 좌표를 싣는다 — 재조회 없이 그 카드만 목록에서 제거.
      removeTournamentItemCard(n.tournamentId, n.tournamentItemId);
      break;
    case "ITEM_PARSING_COMPLETED":
    case "ITEM_PARSING_FAILED":
      // 파싱 알림에 한해 출처(kind)로 분기 — refId(itemId)는 이동에 직접 쓰지 않는다.
      if (n.kind === "TOURNAMENT") goToTournamentItem(n.tournamentId, n.tournamentItemId); // 입장 후 그 아이템 지목
      else goToArchive();      // kind === "WISH"
      break;
  }
  showToast(n.title);
});

// 조용한 화면 갱신(알림 아님, 토스트도 없음). payload 의 type 으로 사건 분기.
es.addEventListener("silent-sync", (e) => {
  const s = JSON.parse(e.data);
  switch (s.type) {
    case "TOURNAMENT_ITEM_PARSED":
      // (tournamentId, tournamentItemId)로 그 카드를 찾아 status 로 갱신 (또는 그 토너먼트 아이템 목록 재조회).
      updateTournamentItemCard(s.tournamentId, s.tournamentItemId, s.status); // status: "READY" | "FAILED"
      break;
    case "UNREAD_COUNT_CHANGED":
      // 읽음 후 다른 기기의 인앱 배지 동기화: 전체 안읽음 수를 그대로 미러링(+1/-1 산수 없이).
      setBadge(s.unreadCount);
      break;
  }
});

es.onerror = () => {
  // EventSource 가 자동 재연결을 시도한다. 필요 시 추가 처리.
};
```

### APP (헤더 인증 + 재연결 시 토큰 갱신)

브라우저 `EventSource` 와 달리 앱은 네이티브 HTTP 스트림(Android `OkHttp-sse`, iOS `URLSession` 등)을 직접 다루므로 `Authorization` 헤더를 자유롭게 실을 수 있다. 핵심은 **재연결할 때마다 유효한 토큰으로 다시 여는 것**.

```kotlin
// 의사코드 (Android/OkHttp-sse 가정)
fun openSse() {
    val token = tokenStore.validAccessToken()   // 만료됐으면 refresh 후 새 토큰 반환
    val request = Request.Builder()
        .url("$BASE_URL/api/v1/notifications/subscribe")
        .header("Authorization", "Bearer $token")
        .build()

    EventSources.createFactory(client).newEventSource(request, object : EventSourceListener() {
        override fun onEvent(es: EventSource, id: String?, type: String?, data: String) {
            when (type) {
                "connect" -> { /* 연결됨 */ }
                "notification" -> {
                    val n = json.decode<NotificationPayload>(data)
                    // 반드시 type 으로 먼저 분기한다(kind 는 라벨·아이콘용 — 좌표 유무를 가르지 않는다):
                    //   TOURNAMENT_ITEM_DELETED -> tournamentId·tournamentItemId 로 그 카드만 제거
                    //   그 외 TOURNAMENT_*      -> refId(= tournamentId) 로 토너먼트 (좌표 없음)
                    //   ITEM_PARSING_*          -> 이 안에서만 kind 로 출처를 가른다:
                    //                              kind == "TOURNAMENT" -> tournamentId 로 입장 후 tournamentItemId 로 그 아이템 지목
                    //                              kind == "WISH"       -> /archive
                }
                "silent-sync" -> {
                    // 조용한 화면 갱신(알림 아님). SilentSyncPayload 는 sealed 지만 @JsonTypeInfo 가 없어 인터페이스로 바로
                    // 디코드하면 안 된다. data 의 "type" 을 먼저 읽고 그 concrete 타입으로 디코드한다.
                    when (jsonType(data)) {  // data 의 "type" 필드만 먼저 파싱
                        "TOURNAMENT_ITEM_PARSED" -> {
                            val s = json.decode<TournamentItemParsed>(data)  // {type, tournamentId, tournamentItemId, status: READY|FAILED}
                            // (s.tournamentId, s.tournamentItemId)로 그 출전 카드를 s.status 로 갱신
                        }
                        "UNREAD_COUNT_CHANGED" -> {
                            val s = json.decode<UnreadCountChanged>(data)  // {type, unreadCount}
                            // 다른 기기의 인앱 배지를 s.unreadCount 로 미러링
                        }
                    }
                }
                // 그 외(주석 ping 등)는 무시
            }
        }

        override fun onFailure(es: EventSource, t: Throwable?, response: Response?) {
            // 401 이면 토큰 만료 가능성 → refresh 후 재연결
            // 그 외 끊김도 backoff 후 openSse() 재호출 (자동 재연결 직접 구현)
            scheduleReconnect()  // 재연결 시 openSse() 가 다시 validAccessToken() 으로 최신 토큰을 싣는다
        }
    })
}
```

> 포인트: `validAccessToken()` 처럼 **연결을 여는 함수가 매번 최신 토큰을 가져오게** 하면, 재연결 경로가 자연히 "만료 시 refresh 후 새 토큰으로 재연결"이 된다.

### 핵심 체크리스트

- [ ] `type` 으로 분기, **문구로 분기하지 않기**
- [ ] `refId` 의미가 `type` 마다 다름 (tournamentId vs itemId)
- [ ] `kind`(`WISH`\|`TOURNAMENT`\|`SYSTEM`)는 **전 알림 공통** — 카드 라벨·아이콘에 그대로 쓴다 (옛 `category`·`imageUrl` 은 응답에서 제거됨)
- [ ] **좌표(`tournamentId`·`tournamentItemId`) 유무는 `kind` 가 아니라 `type` 이 가른다** — `kind === "TOURNAMENT"` 만 보고 좌표를 읽지 말 것 (소셜 알림도 그 `kind` 지만 좌표가 없다)
- [ ] 파싱 알림(`ITEM_PARSING_*`)은 **그 `type` 분기 안에서만** `kind` 로 출처 분기 (WISH → `/archive`, TOURNAMENT → `tournamentId`·`tournamentItemId`)
- [ ] `silent-sync` 는 알림이 아닌 **화면 갱신 신호** — payload 의 `type` 으로 분기 (`TOURNAMENT_ITEM_PARSED` 면 `(tournamentId, tournamentItemId)` 로 카드를 찾아 `status`(READY/FAILED) 반영). 토스트·알림센터 아님
- [ ] 주석 `: ping` 은 무시 (data 이벤트 아님)
- [ ] 재연결 시 목록 API 로 놓친 알림 동기화
- [ ] WEB 은 쿠키 인증(`withCredentials`), APP 은 `Authorization` 헤더
- [ ] **재연결 시 토큰이 만료됐으면 refresh 후 새 토큰으로 연결** (연결 도중 만료는 무관, 재연결 시점이 관건)
