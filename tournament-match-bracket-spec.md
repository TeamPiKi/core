# 토너먼트 매치 브래킷 — 서버 이관 설계

P9 · 이슈 #683. 매치 진행 로직을 프론트에서 서버로 옮기고, 브래킷을 표준 싱글 엘리미네이션으로 정규화한다.
**스키마 변경 0** — 전부 기존 `tournament_histories` 에서 파생한다.

---

## 1. 배경 — 지금 무엇이 어디에 있나

매치 진행은 두 축으로 갈린다. **"누가 누구와 붙나"(페어 구성)** 와 **"지금 어느 페어 차례냐"(진행 순서)** 다.

### 프론트가 하는 일 (`client` : `match/_utils/pairItems.ts`, `match/_hooks/useTournament.ts`)

```ts
const sorted = pairByPriceAsc(remainingItems);   // price ASC, tournamentItemId ASC → 인접 2개씩
return isMounted ? shufflePairs(sorted) : sorted; // Math.random Fisher-Yates, 페어 내부 좌/우는 유지
```

- **페어 구성은 이미 결정론이다.** 가격 정렬 후 인접 묶기라 같은 입력이면 같은 조합이 나온다.
- **진행 순서만 랜덤이다.** `Math.random` 이 코드베이스에서 이 한 줄뿐이고 시드 개념이 없어 재현이 불가능하다.
- 셔플이 `useMemo([remainingItems, isMounted])` 에 걸려 **매치마다 재실행**된다. 새로고침하면 다음에 뜰 매치가 바뀐다.
- SSR 첫 렌더는 셔플을 건너뛰어(하이드레이션 미스매치 방어) 가격순 첫 페어가 그려졌다가 마운트 후 교체된다.

### 서버에 이미 있는 것 (`TournamentService.kt`)

| 자산 | 위치 |
|---|---|
| 가격 정렬 (`price ASC, tournamentItemId ASC`) | `:377` |
| 라운드 계산 | `computeExpectedRound :1092` |
| 탈락·현재라운드 대결완료 집계 | `:361-373` |
| CLONE→ROOT 아이템 해소 | `getEffectiveTournamentItems :1043` |
| 고정 snapshot 가격 | `snapshot.currentPrice` |

**없는 것은 인접 페어 묶기 · 진행 순서 결정 · 명시적 `currentMatch` 뿐이다.** 무에서 구현이 아니라 클라 로직 흡수 + 명시화다.

### 현행 브래킷의 문제

`computeExpectedRound` 는 라운드마다 인원을 절반씩 깎는다. 아이템 수 제약이 `2..32` 범위 검사뿐이라(`:195`) 2의 거듭제곱이 아닌 인원이 실제로 들어온다.

```
25명 → 12매치+1부전승 → 13명 → 6매치+1부전승 → 7명 → 3매치+1부전승 → 4명 → 2매치 → 결승
```

`13명`·`7명` 같은 어중간한 라운드가 생기고, 클라 `getRoundLabel` 이 인원수를 그대로 라벨에 써서 화면에 **"13강 라운드 1"** 이 뜬다.

---

## 2. 결정 사항

| 항목 | 결정 | 비고 |
|---|---|---|
| 브래킷 | 첫 라운드에서 **2의 거듭제곱으로 정규화** | 25명 → 9매치 → 16강 → 8강 → 4강 → 결승 |
| 부전승 선정 | **시드 랜덤** (가격 편향 없음) | 현행 "최고가 고정" 을 대체 |
| 진행 순서 | **시드 셔플** — `seed = tournamentUserId + round` | 저장 없이 새로고침 복원 |
| 페어 구성 | 가격 오름차순 **인접 페어** (현행 유지) | 부전승을 뺀 나머지로 묶는다 |
| 페어 조합 검증 | **강제** — 파생 집합에 없는 조합은 거부 | 브래킷 무결성 |
| 진행 순서 검증 | **하지 않음** | 라운드 내 매치는 서로 독립이라 결과가 같다 |
| 재요청 | **멱등** — 같은 승자면 성공, 다른 승자면 거부 | |
| 스키마 | 변경 없음 | 전부 파생 |

### 왜 시드 랜덤인가

서버가 `currentMatch` 를 지정하려면 순서가 재현 가능해야 한다. 선택지는 셋이었다.

| 방식 | 새로고침하면 | 스키마 |
|---|---|---|
| **시드 랜덤** | 같은 순서 복원 | 변경 0 |
| 진짜 랜덤 + DB 저장 | 같은 순서 복원 | 컬럼 추가 |
| 매 요청 랜덤 (무상태) | 순서가 또 바뀜 | 변경 0 |

사용자 눈에는 시드 랜덤도 그냥 랜덤이다 — 가격순도 아니고 예측도 안 된다. 차이는 서버가 같은 시드로 순서를 다시 만들 수 있다는 것뿐이라, 저장 없이 새로고침 복원이 따라온다. 세 번째는 서버가 내려준 `currentMatch` 를 다음 요청에서 스스로 뒤집게 되어 이관 비용 대비 얻는 게 없다.

### 왜 순서는 검증하지 않는가

브래킷 무결성의 본질은 "누가 누구와 붙나" 이고 그건 페어 조합 검증이 잡는다. **순서는 결과에 영향을 주지 않는다** — 라운드 안의 매치들은 서로 독립이고 어차피 전부 치러야 승자가 나온다. 8강의 3번째 매치를 먼저 해도 최종 결과는 같다.

반면 순서를 강제하면 열린 탭 · 뒤로가기 · 재전송에서 오탐 400 이 난다. 얻는 것 없이 잃기만 한다.

---

## 3. 브래킷 파생 — `RoundBracket` (신설)

`TournamentService` 가 이미 1131줄이라 서비스에 얹지 않고 **순수 도메인 객체**로 분리한다. Spring · DB 없이 단위 테스트로 분기를 망라할 수 있다.

```
입력: 라운드 시작 시점 아이템들(tournamentItemId · price), round, seed
출력: 순서가 정해진 페어 목록 + 부전승 아이템 목록
```

### 매치 수 공식

```kotlin
n 이 2의 거듭제곱  → 매치 n/2,          부전승 0
아니면 target = 2^floor(log2(n))
                  → 매치 n - target,   부전승 2*target - n
```

`n` 이 이미 2의 거듭제곱인 경우를 분기하지 않으면 매치 수가 `0` 이 되어 **아무도 안 싸우고 라운드가 넘어간다.** 첫 줄이 반드시 필요하다.

| n | target | 매치 | 부전승 | 이후 |
|---|---|---|---|---|
| 32 | — | 16 | 0 | 16 → 8 → 4 → 2 |
| 25 | 16 | 9 | 7 | 16 → 8 → 4 → 2 |
| 12 | 8 | 4 | 4 | 8 → 4 → 2 |
| 8 | — | 4 | 0 | 4 → 2 |
| 7 | 4 | 3 | 1 | 4 → 2 |
| 5 | 4 | 1 | 3 | 4 → 2 |
| 3 | 2 | 1 | 1 | 2 |
| 2 | — | 1 | 0 | 결승 |

첫 라운드만 정규화하면 이후 인원은 항상 2의 거듭제곱이라 부전승이 다시 생기지 않는다.

### 파생 순서

```kotlin
val sorted  = items.sortedWith(compareBy({ it.price }, { it.tournamentItemId }))
val random  = Random(seed)                    // seed = tournamentUserId + round
val byes    = sorted.pickRandom(byeCount, random)
val pairs   = (sorted - byes).chunked(2)      // 정렬 순서 유지 → 가격 인접 보장
val ordered = pairs.shuffled(random)          // 진행 순서만 랜덤화
```

부전승을 **먼저 빼고** 남은 것을 인접 페어로 묶으므로 가격이 가까운 것끼리 붙는 성질이 유지된다.
같은 `Random` 인스턴스를 순차 사용하므로 부전승 선정과 셔플이 함께 결정론이다.

`price` 는 nullable 이다. 현행 `compareBy` 의 null-first 정렬을 그대로 따른다.

### 라운드 시작 시점 집합을 입력으로 쓴다

라운드 중간에는 이미 싸운 아이템이 `remainingItems` 에서 빠진다. 그 축소된 집합으로 매번 파생하면 부전승 대상과 순서가 흔들린다. **라운드 시작 시점 집합**(= 전체 아이템 − 이전 라운드들에서 탈락한 아이템)으로 파생한 뒤, 그중 아직 안 치른 첫 매치를 `currentMatch` 로 내린다.

```kotlin
val roundItems = allItems - histories.filter { it.currentRound != currentRound }.map { it.loser() }
```

---

## 4. `currentRound` — 의미는 그대로

"그 라운드 시작 시점 인원 수" 라는 현행 의미를 유지한다. 25 → 16 → 8 → 4 → 2 로 흐른다.
클라 `getRoundLabel` 이 이 값을 라벨에 그대로 쓰므로 **"16강 · 8강 · 4강 · 결승" 이 저절로 맞아떨어진다.** 지금 나오던 "13강" 이 사라진다.

`computeExpectedRound` 는 위 매치 수 공식을 쓰도록 갈아엎는다.

```kotlin
var players = totalItems
while (players >= FINAL_ROUND_SIZE) {
    val expected = matchCountOf(players)
    val played = countByRound[players] ?: 0
    if (played < expected) return players
    if (players == FINAL_ROUND_SIZE) break   // 결승까지 다 치렀다 → 더 내려갈 라운드 없음
    players -= expected                      // 승자 + 부전승
}
```

루프 조건이 `>` 면 **결승 자체를 검사하지 않는다.** 4강 2매치를 치른 뒤 `players` 가 2로 줄면 `2 > 2` 가 false 라
결승(2)을 반환하지 못하고 루프를 빠져나가 `error()` 로 떨어진다. `>=` 로 결승을 검사 범위에 넣고,
결승까지 완료된 경우는 `break` 로 빠져 기존과 같이 "모든 라운드 완료인데 IN_PROGRESS" 불변식 위반을 알린다.

---

## 5. API 계약

관여하는 엔드포인트는 둘뿐이지만 **호환성 성격이 다르다.**

| 엔드포인트 | 변경 | 하위호환 |
|---|---|---|
| `GET /api/v1/tournaments/{id}` | `currentMatch` 필드 추가 | O — additive |
| `POST /api/v1/tournaments/{id}/matches` | `data` 가 `CompletedData \| null` 에서 래퍼로 교체 | **X — 결승 응답을 읽던 `data.result` 가 `data.completed.result` 로 이동** |

`POST` 는 필드가 느는 게 아니라 `data` 의 형태 자체가 바뀌므로 구버전 클라가 결승 결과를 읽지 못한다. 8절의 동시 배포가 필요한 이유가 첫 라운드 조합 차이 하나만이 아니다.

### `GET /api/v1/tournaments/{id}` — 진입 · 새로고침 · 라운드 전환

```jsonc
"inProgress": {
  "currentRound": 16,
  "lastHistory": { ... },
  "remainingItems": [ ... ],       // 유지
  "currentMatch": {                 // 신규
    "first":  { "tournamentItemId": 12, "name": "...", "price": 99000, "imageUrl": "...", ... },
    "second": { "tournamentItemId": 47, "name": "...", "price": 102000, "imageUrl": "...", ... }
  }
}
```

`first` / `second` 에 ID 만 주지 않고 아이템 객체를 통째로 담는다. `remainingItems` 에서 다시 찾게 하면 클라에 조합 로직이 남는다.

### `POST /api/v1/tournaments/{id}/matches` — 선택 기록

현재 응답은 `CompletedData | null` 이라 `nextMatch` 를 실을 자리가 없다. 래퍼로 감싼다.

```jsonc
// 일반 매치
"data": { "nextMatch": { "first": {...}, "second": {...} }, "completed": null }
// 라운드 마지막 매치
"data": { "nextMatch": null, "completed": null }
// 결승
"data": { "nextMatch": null, "completed": { "result": [...], "hasGroupResult": true, ... } }
```

`nextMatch` 가 있으면 클라는 재조회 없이 다음 매치를 그린다. `null` 이면 라운드가 끝났다는 뜻이고 클라는 현행대로 `GET` 을 다시 불러 다음 라운드를 받는다(전환 연출 타이밍도 현행 로직 그대로).

---

## 6. `recordMatch` 검증

기존 검증(소속 · 탈락 · 승자 유효성 · `currentRound`)은 그대로 두고 두 가지를 더한다.

```
1. 파생한 페어 집합에 {first, second} 가 있나?   → 없으면 400 (조합 위조)
2. 이미 치른 매치인가?
     같은 승자  → 멱등 성공 (nextMatch 재계산해 반환)
     다른 승자  → 409 (결과 뒤집기 시도)
3. 순서 불일치는 검사하지 않는다
```

### 멱등에 결승 예외를 두지 않는다

**진행 중(`isInProgress`) 검사는 멱등 판정보다 뒤에 와야 한다.** 결승을 기록하면 그 자리에서 `complete()` 가 호출돼 토너먼트가 `COMPLETED` 로 바뀐다. 상태 검사를 앞에 두면 결승 재전송이 멱등 블록에 닿지 못하고 `409 NOT_IN_PROGRESS` 로 떨어진다 — 그런데 응답을 못 받고 재전송하는 경우(타임아웃 · 앱 재실행 · 중복 탭)는 **결승에서 가장 흔하다.** 하필 그 케이스만 멱등 계약에서 새는 셈이다.

상태 검사가 묻는 건 "새 매치를 기록해도 되나" 이므로 재시도 판정 뒤가 제자리다.

```
멱등 히트 → 승자 다름               → 409 (이미 기록된 대결, 완료 여부 무관)
          → 승자 같음 + COMPLETED  → completed 재구성해 반환
          → 승자 같음 + 진행 중     → nextMatch 재파생해 반환
멱등 미스 → 진행 중 아니면          → 409 (진행 중 아님)
```

부수 효과로 참여자 조회가 상태 검사보다 앞서게 되어, 미참여자가 완료된 토너먼트에 요청하면 409 대신 403 이 나간다. 권한 검사가 상태 검사보다 앞서는 것이 더 정확한 응답이라 그대로 둔다.

새 예외는 `TournamentErrorCode` 에 **append-only** 로 더한다(번호 재사용 · 재배치 금지). `*Api` 의 `@ApiResponses` 와 `*ApiExamples` 를 함께 갱신한다 — `add(exception, name)` 오버로드를 쓰면 detail 이 예외에서 자동 추종한다.

---

## 7. 테스트

| 분류 | 파일 | 무엇 |
|---|---|---|
| 단위 | `tournament/domain/RoundBracketTest.kt` | 인원 2~32 전수의 매치 · 부전승 수, 같은 seed 재현성, 다른 seed 는 다름, 가격 동률 시 id 정렬, 부전승 제외 후 인접 페어 유지, 부전승 0 케이스(2의 거듭제곱) |
| 통합 | `tournament/controller/TournamentMatchIntegrationTest.kt` | `currentMatch` 배선, 위조 페어 400, 멱등 성공, 승자 뒤집기 409, **순서 바꿔 보내도 200**, 응답 계약(`status`·`code`·`detail`·`data` 모양) |

분기 망라는 단위로 내리고 통합은 시나리오 · 계약 3~5건 수준으로 유지한다.
실행 전 Docker 가드를 먼저 돌린다 — `docker info > /dev/null 2>&1 || (open -a Docker && until docker info > /dev/null 2>&1; do sleep 2; done)`.

---

## 8. 배포 주의 — 클라 동반 배포가 **필수**

브래킷 정규화로 서버와 구버전 클라의 **페어 구성 자체가 달라진다.**

```
25명일 때
  구버전 클라: 12페어 + 최고가 1개 부전승
  새 서버:      9페어 + 시드 랜덤 7개 부전승   ← 조합이 완전히 다름
```

| 참가 인원 | 서버 ↔ 구버전 클라 |
|---|---|
| 2 · 4 · 8 · 16 · 32 | 페어 구성 동일 → 통과 |
| **그 외 전부** | **첫 라운드 조합이 다름 → 400 거부** |

서버만 먼저 내보내면 비정형 인원 토너먼트가 첫 매치부터 막힌다. **서버 · 클라를 함께 배포한다.**

배포 순간 `IN_PROGRESS` 로 남아 있던 토너먼트는 이미 쌓인 히스토리가 새 브래킷과 어긋난다. 비정형 인원일수록 어긋남이 크다 — 25명이면 구버전은 12매치 + 부전승 1이고 새 로직은 32로 정규화해 9매치 + 부전승 7이라, 남은 매치의 페어 구성 자체가 다르다. 그래서 다음 요청은 `invalidCurrentRound` 뿐 아니라 `invalidMatchPair` 로도 떨어질 수 있다. 어느 쪽이든 조용히 깨지지는 않지만 그 사용자는 진행하던 토너먼트를 끝내지 못한다.

**그럼에도 마이그레이션·브래킷 버전 필드·사전 차단을 두지 않는다.** 셋 다 "한 번 쓰고 버릴 코드" 를 스키마나 도메인에 남기는데, 진행 중 토너먼트는 수명이 짧아(대개 한 세션 안에 끝난다) 그 비용을 회수하지 못한다. 대신 **트래픽이 적은 새벽에 배포해 노출을 줄인다.** 걸린 사용자는 새로 시작하면 되고, 그 손실이 영구 복잡도보다 싸다는 판단이다.

## 9. 프론트가 함께 걷어내는 것

| 지금 | 이관 후 |
|---|---|
| `pairItems.ts` (`pairByPriceAsc` · `shufflePairs`) | 파일째 삭제 |
| `const currentMatch = pairs[0]` | `inProgress.currentMatch` 사용 |
| `setRemainingItems(prev => prev.filter(...))` 낙관적 조작 | `POST` 응답의 `nextMatch` 로 교체 |
| 홀수 시 마지막 아이템 제외 | 서버가 처리 |
| `isMounted` / `useSyncExternalStore` 하이드레이션 방어 | 불필요 — 난수가 사라져 SSR/CSR 이 같아짐 |

`ByeWarningDialog`(시작 전 "부전승이 포함돼요" 안내)는 **그대로 둔다.** 판정이 `isPowerOfTwo` 라 새 브래킷과 기준이 정확히 같고 문구도 여전히 참이다.

---

## 10. 범위 밖

- **진행 중 부전승 표시** — 부전승이 첫 라운드에 몰리면서(25명이면 7개) "얘가 부전승이야" 를 알려주는 화면이 지금보다 더 필요해진다. 디자인 협의가 필요해 이번 서버 작업에서 뺀다. 파생 함수가 부전승 목록을 이미 계산하므로 나중에 필드로 내려주기만 하면 된다.
- **`recordMatch` 행 락 완화 (D)** — 참여자별 독립 매치인데 ROOT tournament 행에 `FOR UPDATE` 를 걸어 동시 매치가 직렬화된다. 정원 검증 원자성과의 관계 확인이 필요해 별도 판단.
- **상태 매 요청 재구성 (E)** — 32강 상한이라 절대 비용이 작다.
- **`transitionStage` 필드 신설** — `currentRound` 파생으로 충분해 서버는 내리지 않는다.
