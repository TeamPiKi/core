`/pr` · `/issue` 의 마지막 단계로 자동 호출되어, 이번 PR 또는 이슈를 Notion `프로젝트 일정 관리` 보드의 매칭 카드에 반영합니다 — 카드 본문 `개발 로그` 에 PR/이슈 링크 한 줄 append, 그리고 `mode=pr` 이면 `계획중`→`진행중` 으로. 보드 카드는 "기능/주제 단위"(`[BE] Tournament` 한 장에 여러 PR·이슈가 쌓임)라, 매칭되는 카드가 있으면 그 카드에 append 하고 새로 만들지 않습니다. **카드 생성은 `mode=issue` 에서 매칭 카드가 없을 때만**, 사용자 확인을 받아 `계획중` 으로 1장 만듭니다. `완료` 이동·일정/담당자 변경은 하지 않습니다(사람 몫). 단독 수동 호출보다 `/pr`·`/issue` 흐름의 일부로 도는 것을 전제합니다.

## 모드

이 스킬은 두 진입점이 공유한다. 입력의 `mode` 로 분기한다.

- **`mode=pr`** (`/pr` 가 호출) — 코드를 올린 시점. 매칭 카드의 `개발 로그` 에 PR 링크 append + `계획중`→`진행중`. **카드 자동 생성 안 함** (없으면 멈추고 사람에게 만들라고 안내).
- **`mode=issue`** (`/issue` 가 호출) — 작업을 시작하는 시점. 매칭 카드가 있으면 그 카드의 `개발 로그` 에 이슈 링크 append (**상태는 건드리지 않음**). **매칭 카드가 없으면** 사용자 확인 후 `계획중` 카드를 1장 생성하고 거기에 이슈 링크 append.

공통: 토큰 게이트 · 후보 카드 조회 · 매칭 · dedup · append 패턴은 두 모드가 그대로 공유한다. 모드별로 갈리는 건 (a) 매칭 단서, (b) 매칭 카드가 없을 때의 처리(멈춤 vs 생성), (c) append 하는 줄(PR vs 이슈), (d) 상태 이동 여부 네 가지뿐이다.

## 전제

- **`$NOTION_TOKEN`** (Notion internal integration 토큰)이 필요하다. 없으면 이 단계 전체를 **조용히 스킵**하고 한 줄 안내만 남긴다 — PR·이슈 흐름을 실패시키지 않는다.
- 이 Bash 도구는 `~/.zshrc` 를 자동 로드하지 않으므로, 각 curl 블록 시작에 토큰을 로드한다:
  ```bash
  [ -z "$NOTION_TOKEN" ] && eval "$(grep '^export NOTION_TOKEN=' ~/.zshrc 2>/dev/null)"
  ```
- **모든 Notion 호출은 HTTP 200 을 확인한다.** read(카드 조회·본문 조회)가 비-200이면 그 단계에서 멈추고 보드 반영을 생략한다(best-effort). 비-200 응답을 본문인 양 파싱하면 401/403/429/5xx 를 "카드 없음"·"미기록"으로 오인해 잘못 진행한다. 카드 생성(`mode=issue`)·append·상태 PATCH 도 200 이 아니면 사용자에게 보고하고 멈춘다.
- **임시파일은 브랜치별 경로로 격리한다.** Notion 호출의 응답·요청 임시파일(`nb_cards`/`nb_created`/`nb_body`/`nb_append`/`nb_create`)은 고정 이름이 아니라 `/tmp/<이름>_$SLUG.json` 을 쓴다 (`SLUG` = 브랜치명의 `/` 를 `_` 로 치환). 두 워크트리 세션이 동시에 보드 반영을 돌려도 파일이 안 겹친다. 셸 변수는 bash 호출 간 유지되지 않으므로 각 bash 블록은 `SLUG=$(git branch --show-current | tr '/' '_')` 를 자기 안에서 다시 구한다. **quoted heredoc(`<<'PY'`) 안에선 `$SLUG` 가 확장되지 않으므로, `python3` 가 여는 파일 경로는 셸에서 argv 로 넘겨 `sys.argv[1]` 로 받는다.**
- **진입 시 stale 정리.** 2단계 시작에서 7일 넘게 안 건드린 `nb_*.json` 임시파일을 mtime 기준으로 지운다 — `session-close` 를 안 거치고 중단된 작업의 누수를 회수하되, 동시 세션의 최신 파일은 보존한다. 임시파일은 지워져도 API 재조회로 재생성돼 손실이 없으므로, 진행 중 작업을 안 건드리도록 임계값을 넉넉히 둔다.

## 상수

- 보드(DB) id: `5a0c800c-72cf-8307-8297-8124d888ca79`
- API 버전 헤더: `Notion-Version: 2022-06-28`
- 상태 property 이름: `상태`, 옵션: `계획중` / `진행중` / `보류` / `완료`
- 제목 property 이름: `프로젝트명` (title 타입)

## 호출 계약 (`/pr` · `/issue` 가 따른다)

이 보드를 쓰는 법은 전부 이 스킬 안에 있다. 호출자(`/pr`·`/issue`)는 **`mode` 와 호출 시점만 정하고, 아래 입력을 넘긴 뒤 이 스킬을 호출**한다 — 무엇을 거를지·올릴지는 호출자가 판단하지 않는다.

- **항상 호출한다.** 호출자는 라벨·토큰 유무로 미리 거르지 않는다. 무엇을 보드에 올릴지(스킵·확인·진행)는 이 스킬이 정한다 — 토큰이 없을 때만 자동 스킵(`### 1. 게이트`)하고, 내부 작업 라벨(`chore`/`test`/`infra`/`docs`/`refactor`)은 확인 문구에 덧붙여 사용자가 결정하게 한다.
- **best-effort.** 보드 반영이 실패해도 호출자의 PR·이슈 작업을 되돌리지 않는다.

넘겨받는 입력:

공통:
- `mode` (`pr` | `issue`)
- 이번 작업이 무엇인지 **한 줄 설명** (확인 문구에 그대로 쓴다)
- `$ISSUE_LABELS` · 이번 대화 맥락

`mode=pr` 추가 입력: PR URL · 번호 · 브랜치명 · 연관 이슈 번호·제목 · PR 제목
`mode=issue` 추가 입력: 이슈 URL · 번호 · 제목 · Epic 여부

## 절차

### 1. 게이트

- `$NOTION_TOKEN` 미설정 → 아무것도 하지 않고 조용히 스킵하고 한 줄만 보고한 뒤 종료한다 ("Notion 토큰 없어 보드 반영 생략"). 토큰 없이는 할 수 있는 게 없으므로 이 경우만 자동 스킵이다 (PR·이슈 결과에 영향 없음).
- 그 외에는 **라벨과 무관하게 항상 진행**한다. 보드 반영 여부를 라벨로 자동 판단해 조용히 버리지 않는다 — 3단계에서 사용자에게 묻고 사용자가 결정한다.

> `$ISSUE_LABELS` 는 스킵을 강제하는 게이트가 아니라 **참고 신호**일 뿐이다. `chore` / `test` / `infra` / `docs` / `refactor` 처럼 보통 보드 비대상인 내부 작업이면 3단계 확인에서 그 사실을 한 줄 덧붙여 알리되, 올릴지 말지는 사용자가 정한다. 매칭되는 카드가 없으면 자연히 보드에 안 올라간다 (`mode=issue` 는 사용자가 OK 해야만 새 카드 생성).

### 2. 후보 카드 조회 (완료 아닌 카드)

`상태 != 완료` 는 **서버측 filter 로 제외하고 받는다** — 전체를 받아 클라이언트에서 거르면 `page_size:100` 한도를 누적된 완료 카드가 잠식해, 정작 활성 후보가 조용히 잘린다. 빈 상태 카드는 후보에 남겨야 하므로 `is_empty` 를 or 로 함께 건다. 그래도 `has_more=True` 가 찍히면 `next_cursor` 를 `--data` 의 `"start_cursor"` 로 넘겨 이어 받는다 (`파트` 필드는 보드에서 일관되지 않으므로 필터·판단에 쓰지 않는다 — 참고용으로만 표시):

```bash
[ -z "$NOTION_TOKEN" ] && eval "$(grep '^export NOTION_TOKEN=' ~/.zshrc 2>/dev/null)"
SLUG=$(git branch --show-current | tr '/' '_')
find /tmp/ -maxdepth 1 -name 'nb_*.json' -mmin +10080 -delete 2>/dev/null   # 진입 정리: 7일+ stale 누수 회수, 최신 파일 보존 (임시파일은 재조회로 재생성돼 손실 없으니 임계값 넉넉히). /tmp/ trailing slash 필수 (macOS /tmp 는 /private/tmp symlink — 슬래시 없으면 find no-op), -mmin +10080 = 7일 초과 (-mtime 은 +1일 반올림)
DB=5a0c800c-72cf-8307-8297-8124d888ca79
code=$(curl -s -X POST "https://api.notion.com/v1/databases/$DB/query" \
  -H "Authorization: Bearer $NOTION_TOKEN" -H "Notion-Version: 2022-06-28" -H "Content-Type: application/json" \
  --data '{"page_size":100,"filter":{"or":[{"property":"상태","status":{"does_not_equal":"완료"}},{"property":"상태","status":{"is_empty":true}}]}}' \
  -o /tmp/nb_cards_$SLUG.json -w "%{http_code}")
[ "$code" = "200" ] || { echo "Notion query 실패 (HTTP $code) — 보드 반영 생략"; exit 0; }
python3 - "/tmp/nb_cards_$SLUG.json" <<'PY'
import json, sys
d=json.load(open(sys.argv[1]))
for p in d.get('results',[]):
    pr=p['properties']
    title=''.join(t['plain_text'] for t in pr['프로젝트명']['title'])
    st=pr['상태']['status']; st=st['name'] if st else '-'
    if st=='완료': continue   # 서버 filter 가 이미 거르지만 이중 방어로 유지
    part=pr['파트']['select']; part=part['name'] if part else '-'
    print(f"{st:<5} {part:<7} {p['id']}  {title}")
if d.get('has_more'): print('HAS_MORE next_cursor=', d.get('next_cursor'), '- start_cursor 로 이어 조회해 후보를 마저 모을 것')
PY
```

### 3. 매칭 + 확인 (유일한 판단 지점)

이번 작업 단서로 후보 중 가장 맞는 카드를 고른다. 백엔드 작업이면 보통 `[BE] ...` · `User` · 위시/토너먼트 등 기능 카드.

- `mode=pr`: 브랜치명·연관 이슈 번호·PR 제목·대화 맥락으로 고른다. **연관 이슈 번호로 우선 매칭** — 후보 카드의 `개발 로그` 에 `이슈 #<연관 이슈 번호>` 가 이미 박혀 있으면(= `/issue` 가 만들거나 연결해 둔 카드) 그 카드를 1순위로 고른다. 그런 카드가 없으면 제목·맥락 fuzzy 로 fallback. (개발 로그 확인이 필요하면 좁힌 후보의 본문을 4단계 방식으로 조회해 `이슈 #N` 포함 여부를 본다.)
- `mode=issue`: 이슈 제목·라벨·대화 맥락으로 fuzzy 매칭한다.

**확인 문구에는 항상 이번 작업이 무엇인지 한 줄 설명을 먼저 붙인다.** 카드명만 들이밀면 사용자가 "그게 무슨 작업이었지" 를 되짚어야 하므로, `[작업] <한 줄 설명>` 을 앞세운 뒤 카드를 제안한다.

- **매칭 카드 있음** → "[작업] '<한 줄 설명>' — 이 <PR|이슈>를 '<카드명>' 에 기록할게, 맞아?" (애매하면 상위 2~3개 제시)
- **매칭 카드 없음**:
  - `mode=pr` → "보드에 맞는 카드가 없어. 직접 만든 뒤 다시 `/pr` 돌릴래?" 하고 멈춘다. **자동 생성하지 않는다.**
  - `mode=issue` → "보드에 맞는 카드가 없어. '<카드 제목 제안>' 카드를 `계획중` 으로 새로 만들까?" 하고 확인받는다. OK 면 3b 로, 거절이면 보드 반영을 생략하고 멈춘다.

`$ISSUE_LABELS` 가 `chore` / `test` / `infra` / `docs` / `refactor` 면 **"이건 <라벨> 라 보통 보드 비대상인데, 그래도 <기록|새 카드 생성>할까?"** 처럼 그 신호를 확인 문구에 덧붙여 사용자가 판단하게 한다.

`mode=issue` 신규 생성 시 **카드 제목 제안**: 보드 관례인 `[BE] ` prefix + 이슈 제목 기반 기능명. 사용자가 Other 로 고칠 수 있다.

이후 단계는 확정/생성된 카드의 `<PAGE_ID>` 로 진행.

### 3b. 카드 생성 (`mode=issue` · 매칭 없음 · 사용자 OK 일 때만)

```bash
[ -z "$NOTION_TOKEN" ] && eval "$(grep '^export NOTION_TOKEN=' ~/.zshrc 2>/dev/null)"
SLUG=$(git branch --show-current | tr '/' '_')
cat > /tmp/nb_create_$SLUG.json <<'JSON'
{"parent":{"database_id":"5a0c800c-72cf-8307-8297-8124d888ca79"},
 "properties":{
   "프로젝트명":{"title":[{"text":{"content":"[BE] <카드 제목>"}}]},
   "상태":{"status":{"name":"계획중"}}
 }}
JSON
code=$(curl -s -X POST "https://api.notion.com/v1/pages" \
  -H "Authorization: Bearer $NOTION_TOKEN" -H "Notion-Version: 2022-06-28" -H "Content-Type: application/json" \
  --data @/tmp/nb_create_$SLUG.json -o /tmp/nb_created_$SLUG.json -w "%{http_code}")
[ "$code" = "200" ] || { echo "카드 생성 실패 (HTTP $code) — 보드 반영 생략"; exit 0; }
python3 - "/tmp/nb_created_$SLUG.json" <<'PY'
import json, sys
print(json.load(open(sys.argv[1]))['id'])
PY
```

- 출력된 page id 가 `<PAGE_ID>`. `프로젝트명`·`상태` 외 property(`파트`/`시작일`/`마감일`/`담당자`/`진행률`)는 **세팅하지 않는다** — 사람 몫.
- 갓 만든 카드는 본문이 비어 있다 → 4단계(dedup)를 건너뛰고 5단계에서 `개발 로그` heading 을 포함해 append 한다.

### 4. dedup (이미 기록됐는지) + 본문 구조 확인

3b 로 갓 생성한 카드는 건너뛴다 (본문이 비어 있어 중복일 수 없다). 매칭된 기존 카드에 대해서만 수행:

```bash
[ -z "$NOTION_TOKEN" ] && eval "$(grep '^export NOTION_TOKEN=' ~/.zshrc 2>/dev/null)"
SLUG=$(git branch --show-current | tr '/' '_')
code=$(curl -s "https://api.notion.com/v1/blocks/<PAGE_ID>/children?page_size=100" \
  -H "Authorization: Bearer $NOTION_TOKEN" -H "Notion-Version: 2022-06-28" -o /tmp/nb_body_$SLUG.json -w "%{http_code}")
[ "$code" = "200" ] || { echo "카드 본문 조회 실패 (HTTP $code) — 보드 반영 생략"; exit 0; }
python3 - "/tmp/nb_body_$SLUG.json" <<'PY'
import json, sys
d=json.load(open(sys.argv[1]))
heading=False; already=False
TOKEN='<MATCH_TOKEN>'   # mode=pr: 'PR #<번호>', mode=issue: '이슈 #<번호>'
for b in d.get('results',[]):
    t=b['type']; node=b.get(t,{})
    txt=''.join(x.get('plain_text','') for x in (node.get('rich_text',[]) if isinstance(node,dict) else []))
    if t=='heading_3' and txt.strip()=='개발 로그': heading=True
    if TOKEN in txt: already=True
print('heading_exists=',heading,'already_logged=',already)
PY
```

- `already_logged=True` → append 생략하고 6단계(상태)로.
- `heading_exists` 값으로 5단계에서 heading 포함 여부 결정.

### 5. append (개발 로그)

`/tmp/nb_append_$SLUG.json` 을 만든다 (`SLUG` = 브랜치명의 `/`→`_`, 전제 규칙) — `개발 로그` heading 이 **없을 때만**(3b 신규 카드는 항상 없음) heading_3 을 먼저 포함, 이어서 bullet (전체를 링크로):

- `mode=pr`: bullet 텍스트 = `PR #<번호> <사람이 읽는 PR 제목> (<KST 오늘 날짜>)`, link = `<PR_URL>`.
- `mode=issue`: bullet 텍스트 = `이슈 #<번호> <이슈 제목> (<KST 오늘 날짜>)`, link = `<이슈 URL>`.

날짜는 `date +%Y-%m-%d`. 예 (`mode=issue`, heading 없는 신규 카드):

```json
{"children":[
  {"object":"block","type":"heading_3","heading_3":{"rich_text":[{"type":"text","text":{"content":"개발 로그"}}]}},
  {"object":"block","type":"bulleted_list_item","bulleted_list_item":{"rich_text":[
    {"type":"text","text":{"content":"이슈 #312 토너먼트 단건 조회 (2026-05-29)","link":{"url":"<이슈 URL>"}}}
  ]}}
]}
```

- heading 이 이미 있으면 위 children 에서 heading_3 객체를 빼고 bullet 만.

```bash
[ -z "$NOTION_TOKEN" ] && eval "$(grep '^export NOTION_TOKEN=' ~/.zshrc 2>/dev/null)"
SLUG=$(git branch --show-current | tr '/' '_')
code=$(curl -s -X PATCH "https://api.notion.com/v1/blocks/<PAGE_ID>/children" \
  -H "Authorization: Bearer $NOTION_TOKEN" -H "Notion-Version: 2022-06-28" -H "Content-Type: application/json" \
  --data @/tmp/nb_append_$SLUG.json -o /dev/null -w "%{http_code}")
echo "append HTTP $code"; [ "$code" = "200" ] || echo "append 실패 — 사용자에게 보고"
```

### 6. 상태 이동

- **`mode=pr`**: 4 에서 읽은 카드 `상태` 기준 `계획중` → `진행중` 으로:
  ```bash
  [ -z "$NOTION_TOKEN" ] && eval "$(grep '^export NOTION_TOKEN=' ~/.zshrc 2>/dev/null)"
  code=$(curl -s -X PATCH "https://api.notion.com/v1/pages/<PAGE_ID>" \
    -H "Authorization: Bearer $NOTION_TOKEN" -H "Notion-Version: 2022-06-28" -H "Content-Type: application/json" \
    --data '{"properties":{"상태":{"status":{"name":"진행중"}}}}' -o /dev/null -w "%{http_code}")
  echo "status HTTP $code"; [ "$code" = "200" ] || echo "상태 변경 실패 — 사용자에게 보고"
  ```
  `진행중` / `보류` / `완료` → 건드리지 않는다.
- **`mode=issue`**: **상태를 건드리지 않는다.** 신규 카드는 3b 에서 이미 `계획중` 이고, 기존 매칭 카드는 그 상태를 유지한다(이미 `진행중` 인 기능에 새 이슈가 붙는 건 정상). 이슈는 "작업 시작 전" 신호라 `진행중` 으로 올리지 않는다 — `진행중` 전환은 PR 단계(`mode=pr`)의 몫이다.
- **`완료` 이동은 두 모드 모두 자동화하지 않는다** — 기능 완료는 사람의 편집적 판단이다.

### 7. 보고

한 줄로: 어느 카드에 무엇을 했는지. 예) `mode=pr` → `'[BE] Tournament' 에 PR #169 기록, 상태 계획중→진행중.` / `mode=issue` (매칭) → `'[BE] Tournament' 에 이슈 #312 기록 (상태 유지).` / `mode=issue` (신규) → `'[BE] 알림' 카드를 계획중으로 생성하고 이슈 #312 기록.` dedup/스킵이면 그 사실을 보고.

## 안 하는 것 (스코프 경계)

- `mode=pr` 의 카드 자동 생성, 두 모드 공통 `완료` 상태 이동, 일정 필드(`시작일`/`마감일`/`진행률`)·`담당자` 변경 — 전부 사람 몫. (`mode=issue` 의 카드 생성은 3b 의 조건에서만 예외.)
- `파트` 필드 신뢰 (보드에서 일관되지 않음). 매칭은 제목·맥락(+`mode=pr` 의 연관 이슈 번호)으로만.
- Claude 안 쓰는 팀원의 PR·이슈 는 잡지 못한다 (알려진 한계). 필요해지면 GitHub Action 으로 보강.
