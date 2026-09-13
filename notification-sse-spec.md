# 실시간 알림 (SSE) 명세

> OpenAPI 는 스트림(`text/event-stream`)을 표현하지 못한다. 이 문서가 클라이언트용 정본이다(수동 관리).

## 1. 개요

- 인증 유저가 **자기 스트림 1개**를 열면 개인 알림과 토너먼트 알림이 모두 그 스트림으로 온다. 토너먼트별로 따로 열지 않는다.
- 서버 → 클라이언트 단방향. 클라이언트 → 서버 생존 신호는 별도 HTTP 요청(4-1)으로 보낸다.
- 다중 탭·기기 접속을 허용한다. 알림은 그 유저의 모든 연결에 전달된다.

## 2. 엔드포인트

| 항목 | 값 |
|---|---|
| Method / Path | `GET /api/v1/notifications/subscribe` |
| 응답 | `text/event-stream` |
| 인증 | 필요 (GUEST 포함) |
| 미인증 | `401`, `ApiResponseBody` JSON |

## 3. 인증

서버는 `Authorization: Bearer <accessToken>` 헤더를 먼저, 없으면 `access_token` 쿠키를 읽는다.

- **WEB**: 브라우저 `EventSource` 는 커스텀 헤더를 못 보내므로 쿠키 인증이다. `withCredentials: true` 로 연다.
- **APP**: `Authorization` 헤더로 보낸다.

인증은 **연결을 여는 순간 1회만** 검증된다. 연결 도중 토큰이 만료돼도 그 연결은 끊기지 않는다(최대 30분). 재연결 시점에 만료된 토큰이면 `401` 이므로, **재연결 전에 토큰을 확인하고 만료됐으면 refresh 한 뒤 연다.**

## 4. 스트림 이벤트

이벤트 `name` 으로 구분한다.

### `connect`

구독 직후 1회. `data` 는 **이 연결의 번호(UUID)** 다. 보관했다가 클라이언트 하트비트(4-1)에 되돌려 보낸다. 재연결하면 번호가 바뀐다.

```text
event: connect
data: 3f1c2b0e-7d4a-4c8b-9e2f-1a2b3c4d5e6f
```

### `notification`

알림 1건. `data` 는 5절의 JSON.

```text
event: notification
data: {"id":123,"type":"TOURNAMENT_JOINED","kind":"TOURNAMENT","title":"홍길동님이 참가했어요","body":"","refId":45,"isRead":false,"createdAt":"2026-06-06T14:32:10"}
```

### `silent-sync`

알림이 아닌 **화면 갱신 신호**. 알림센터·토스트·FCM 없이 SSE 로만 흐른다. `data` 는 5-1절의 JSON 이고 `type` 으로 사건을 분기한다.

```text
event: silent-sync
data: {"type":"TOURNAMENT_ITEM_PARSED","tournamentId":99,"tournamentItemId":555,"status":"READY"}
```

### `heartbeat`

서버 ping. 약 30초 간격, `data` 는 `connect` 와 같은 연결 번호.

```text
event: heartbeat
data: 3f1c2b0e-7d4a-4c8b-9e2f-1a2b3c4d5e6f
```

**60초 동안 안 오면 스트림이 죽은 것이므로 재연결한다.** SSE 는 중간 프록시·이동통신망에서 조용히 끊겨도 양쪽 다 모를 수 있고, 조용한 시간엔 이 이벤트가 유일한 유입이다. 임계값은 한 번 밀린 ping 을 끊김으로 오판하지 않도록 두 주기(60초)다.

### 4-1. 클라이언트 하트비트

반대 방향 생존 신호. 서버 ping 은 서버 쪽 프록시까지 도달한 것만 확인되므로, 클라이언트가 비정상 종료돼도 서버는 약 17분 뒤에야 안다. 클라이언트가 직접 알려야 서버가 제때 정리한다.

| 항목 | 값 |
|---|---|
| Method / Path | `POST /api/v1/notifications/heartbeat` |
| 인증 | 구독과 동일 |
| Body | `{ "connectionId": "<connect·heartbeat 로 받은 번호>" }` |
| 응답 | `200` (`data` 없음) |

- **보내는 조건**: SSE 연결 중이고 앱이 포그라운드일 때 30초마다. 백그라운드에선 보내지 않는다. 실패해도 재시도하지 않고 다음 주기에 보낸다.
- **`401`**: 다른 API 와 같이 토큰 갱신 대상이다. 연결(30분)이 토큰(15분)보다 오래 살아 연결 중 한 번은 만난다.
- **`409` (`code = NOTIFICATION-002`)**: 서버에 그 번호의 연결이 없다(배포로 서버가 바뀌었거나 이미 정리됨). **즉시 재연결**한다.
- **서버 동작**: 하트비트를 한 번이라도 보낸 연결에 한해, 60초 넘게 안 오면 정상 종료한다(실제 종료는 60에서 90초 사이). 클라이언트는 평소처럼 재연결한다. 백그라운드에서 돌아왔을 때 끊겨 있는 것은 의도된 동작이다.

두 방향을 연결 번호가 묶는다. 서버 → 클라 `heartbeat` 는 스트림 생존(클라이언트가 판정), 클라 → 서버 `POST` 는 클라이언트 생존(서버가 판정). POST 는 되는데 스트림만 죽은 경우는 클라이언트가 `heartbeat` 결측으로 알아채 재연결하고, 새 번호로 POST 가 오면 서버가 옛 연결을 정리한다.

## 5. `notification` payload

| 필드 | 타입 | 설명 |
|---|---|---|
| `id` | long | 알림 식별자. 읽음 처리 API 의 키 |
| `type` | enum | 알림 종류. 화면 분기 키 (6절) |
| `kind` | enum | `WISH` \| `TOURNAMENT` \| `SYSTEM`. 전 알림 공통. 카드 라벨·아이콘용 |
| `title` | string | 표시용 제목 (변수 치환 완료본) |
| `body` | string | 표시용 본문. 현재 대부분 `""` |
| `refId` | long | 딥링크 대상 식별자. `type` 마다 대상이 다르다 (6절) |
| `isRead` | boolean | 읽음 여부 |
| `createdAt` | string | ISO-8601 LocalDateTime, 오프셋 없음. 예 `2026-06-06T14:32:10` |
| `tournamentId`, `tournamentItemId` | long, 생략 가능 | 아이템 좌표. **좌표를 싣는 `type` 에만** 실리고 없으면 키 자체가 생략된다 |
| `wishId` | long, null 가능 | 위시 출처 알림(위시 파싱·새로고침)에만. 키는 실리되 옛 알림은 값이 `null` 일 수 있다 |

> **좌표 유무는 `kind` 가 아니라 `type` 이 가른다.** 소셜 알림도 `kind = TOURNAMENT` 지만 좌표가 없다. 서버는 URL 을 내리지 않고 식별자만 내린다.

## 5-1. `silent-sync` payload

공통 필드는 `type` 하나. 나머지는 `type` 별로 다르다.

| `type` | 필드 | 클라 동작 |
|---|---|---|
| `TOURNAMENT_ITEM_PARSED` | `tournamentId`, `tournamentItemId`, `status` (`READY` \| `FAILED`) | 그 출전 카드를 찾아 `status` 반영. 수신자는 그 토너먼트 참여자 전원이고, 한 아이템이 여러 토너먼트에 있으면 각 토너먼트 좌표로 각자 받는다 |
| `UNREAD_COUNT_CHANGED` | `unreadCount` | 읽음 처리 후 다른 기기의 인앱 배지를 그 값으로 미러링(+1/-1 산수 없이). 읽은 기기도 받지만 응답과 같은 값이라 멱등 |

위시로만 담긴 아이템의 파싱 결과는 `silent-sync` 가 아니라 `notification`(`ITEM_PARSING_*`)으로 온다.

## 6. `type` 별 분기표

| `type` | `kind` | 의미 | `refId` | 좌표 | 수신자 |
|---|---|---|---|---|---|
| `TOURNAMENT_JOINED` | `TOURNAMENT` | 참가 | tournamentId | 없음 | 참여자 (행위자 제외) |
| `TOURNAMENT_ITEM_ADDED` | `TOURNAMENT` | 아이템 추가 | tournamentId | 없음 | 참여자 (행위자 제외) |
| `TOURNAMENT_ITEM_DELETED` | `TOURNAMENT` | 아이템 삭제 | tournamentId | **있음** | 참여자 (행위자 제외) |
| `TOURNAMENT_STARTED` | `TOURNAMENT` | 토너먼트 시작 | tournamentId | 없음 | 참여자 (주최자 제외) |
| `TOURNAMENT_PLAYED_FROM_LINK` | `TOURNAMENT` | 플레이링크로 플레이 시작 | ROOT tournamentId | 없음 | ROOT 주최자 |
| `TOURNAMENT_COMPLETED` | `TOURNAMENT` | 클론 완료 | ROOT tournamentId | 없음 | ROOT 주최자 |
| `TOURNAMENT_RESULT_READY` | `TOURNAMENT` | ROOT 결과 나옴 | ROOT tournamentId | 없음 | 참여자 (주최자 제외) |
| `ITEM_PARSING_COMPLETED` / `FAILED` / `INCOMPLETE` | `WISH` \| `TOURNAMENT` (출처) | 내 상품 추출 결과 | itemId | `TOURNAMENT` 출처면 있음, `WISH` 출처면 `wishId` | 본인 |
| `ITEM_REFRESH_COMPLETED` / `FAILED` | `WISH` | 위시 새로고침 결과 | itemId | 없음 (`wishId` 있음) | 본인 |
| `ANNOUNCEMENT` | `SYSTEM` | 전체 공지 | 공지 id | 없음 | 전 유저 |

- `title` 문구는 서버 템플릿이라 바뀔 수 있다. **문구가 아니라 `type` 으로 분기**한다.
- **파싱 알림(`ITEM_PARSING_*`)만 `kind` 로 출처를 가른다.** `WISH` 면 `wishId` 로 위시 상세(값이 `null` 이면 `/archive/wish`), `TOURNAMENT` 면 `tournamentId` 로 입장해 `tournamentItemId` 를 지목한다. 이 분기는 그 `type` 안에서만 쓴다.
- 새로고침(`ITEM_REFRESH_*`)은 항상 `WISH` 이고 `wishId` 로 위시 상세에 간다. 실패해도 카드의 옛 값은 유지한다.

## 7. 연결 수명

| 항목 | 값 |
|---|---|
| 연결 타임아웃 | 30분. 만료 시 서버가 닫는다 |
| 서버 `heartbeat` | 30초 주기. 60초 결측이면 클라이언트가 재연결 |
| 클라이언트 `POST /heartbeat` | 30초 주기, 포그라운드·연결 중에만. 60초 결측이면 서버가 정상 종료 |
| 재연결 | 클라이언트 책임. `EventSource` 는 자동, APP 은 직접 구현 |

**끊김 중 발생한 알림은 재연결해도 스트림으로 다시 오지 않는다.** 알림은 DB 에 있으므로 `GET /api/v1/notifications` 로 따라잡는다(항목 셰입은 5절과 동일, `unreadCount` 동봉). `silent-sync` 는 영속되지 않으니 재연결·앱 진입 시 목록 API 로 최신 `status` 를 받는다. 권장 패턴은 **진입·재연결 시 목록 동기화 + SSE 실시간 갱신**이다.

## 8. 구현 예시

### WEB

```js
const es = new EventSource("/api/v1/notifications/subscribe", { withCredentials: true });
let connectionId;
let lastHeartbeatAt = Date.now();

function reconnect() {
  es.close();
  // 기존 재연결 로직으로 새 EventSource 를 연다. 새 connect 가 새 connectionId 를 준다.
}

es.addEventListener("connect", (e) => { connectionId = e.data; lastHeartbeatAt = Date.now(); });
es.addEventListener("heartbeat", () => { lastHeartbeatAt = Date.now(); });

setInterval(async () => {
  if (document.visibilityState !== "visible" || !connectionId) return;
  if (Date.now() - lastHeartbeatAt > 60_000) return reconnect();
  const res = await fetch("/api/v1/notifications/heartbeat", {
    method: "POST",
    credentials: "include",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ connectionId }),
  });
  if (res.status === 409) reconnect();
  // 401 은 다른 API 와 같이 토큰 갱신 후 다음 주기에
}, 30_000);

es.addEventListener("notification", (e) => {
  const n = JSON.parse(e.data);
  renderKindBadge(n.kind);
  switch (n.type) {
    case "TOURNAMENT_ITEM_DELETED":
      removeTournamentItemCard(n.tournamentId, n.tournamentItemId);
      break;
    case "ITEM_PARSING_COMPLETED":
    case "ITEM_PARSING_FAILED":
    case "ITEM_PARSING_INCOMPLETE":
      if (n.kind === "TOURNAMENT") goToTournamentItem(n.tournamentId, n.tournamentItemId);
      else goToWishDetail(n.wishId); // null 이면 /archive/wish
      break;
    case "ITEM_REFRESH_COMPLETED":
    case "ITEM_REFRESH_FAILED":
      goToWishDetail(n.wishId);
      break;
    default: // 나머지 TOURNAMENT_* 는 refId 가 tournamentId
      goToTournament(n.refId);
  }
  showToast(n.title);
});

es.addEventListener("silent-sync", (e) => {
  const s = JSON.parse(e.data);
  if (s.type === "TOURNAMENT_ITEM_PARSED") updateTournamentItemCard(s.tournamentId, s.tournamentItemId, s.status);
  if (s.type === "UNREAD_COUNT_CHANGED") setBadge(s.unreadCount);
});
```

### APP

앱은 네이티브 HTTP 스트림(OkHttp-sse, URLSession 등)이라 `Authorization` 헤더를 실을 수 있다. 핵심은 **재연결할 때마다 유효한 토큰으로 다시 여는 것**이다.

```kotlin
fun openSse() {
    val token = tokenStore.validAccessToken()   // 만료됐으면 refresh 후 반환
    val request = Request.Builder()
        .url("$BASE_URL/api/v1/notifications/subscribe")
        .header("Authorization", "Bearer $token")
        .build()
    EventSources.createFactory(client).newEventSource(request, object : EventSourceListener() {
        override fun onEvent(es: EventSource, id: String?, type: String?, data: String) {
            when (type) {
                "connect" -> connectionId = data           // 30초마다 POST /heartbeat {connectionId}
                "heartbeat" -> lastHeartbeatAt = now()     // 60초 결측이면 재연결
                "notification" -> handle(json.decode<NotificationPayload>(data))   // type 으로 분기 (6절)
                "silent-sync" -> handleSilentSync(data)    // data 의 "type" 을 먼저 읽고 그 셰입으로 디코드
            }
        }
        override fun onFailure(es: EventSource, t: Throwable?, response: Response?) {
            scheduleReconnect()   // 재연결 시 openSse() 가 다시 validAccessToken() 을 싣는다
        }
    })
}
```

## 9. 요약

- `type` 으로 분기한다. 문구로 분기하지 않는다
- `refId` 의 의미는 `type` 마다 다르다 (tournamentId / itemId)
- 좌표 유무는 `type` 이 가른다. `kind` 만 보고 좌표를 읽지 않는다
- 파싱 알림 안에서만 `kind` 로 출처를 가른다
- `silent-sync` 는 토스트·알림센터 없이 화면만 갱신한다
- `connect`·`heartbeat` 의 연결 번호를 보관하고 재연결 시 갱신한다
- `heartbeat` 60초 결측이면 재연결한다
- 연결 중·포그라운드일 때 30초마다 `POST /heartbeat`, `409` 면 즉시 재연결한다
- 재연결 시 목록 API 로 놓친 알림을 동기화한다
- 재연결 전에 토큰이 만료됐으면 refresh 한다
