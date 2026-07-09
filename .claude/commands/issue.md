이슈를 생성하고, 브랜치(또는 워크트리)를 만들고, 이슈에 매핑하고, 라벨/Project를 설정합니다.

## 원칙

**이슈는 "왜 / 무엇을" 만 받는다.** 우선순위·시작일·마감일 같은 메타 정보는 묻지 않는다. 작업 시작 시점에 적는 메타는 거의 추정치라 데이터 품질이 낮고, 진짜 메타 저장소는 GitHub Project 보드다 — 작업 진행 중 보드에서 채우면 충분하다.

**Repository Issue 베타 필드는 채우지 않는다.** GitHub Issue 사이드바에 표시되는 베타 필드(Priority / Start date / Target date / Effort 등)는 Project 보드 필드와 **별개 저장소**다 — 자동 sync 없고 옵션 enum 도 일부 다르다 (예: 베타 Priority 에만 `Urgent` 추가). 메타는 Project 보드 한 곳을 single source 로 두고, 베타 필드는 None yet 으로 둔다. 베타가 정식 출시돼 sync 거동이 명확해지면 재평가.

**이슈는 작업의 시작점이라 사용자 입력이 source of truth.** 모델이 추측해 본문을 채우거나 분류를 단정하지 않는다. 자동화하는 부분은 모두 검수 단계에서 사용자 OK 를 받는다.

**Epic 과 일반 이슈는 본질이 다르다.** Epic 은 여러 작업의 묶음으로 자체 코드 작업이 없어 브랜치를 만들지 않는다. 일반 이슈는 코드 작업의 단위로 부모 Epic 에 묶일 수 있다. 이 구분만 사용자가 명시하고, 그 외 분류·prefix·상위 Epic 추천은 모델이 본문을 보고 자동 결정한 뒤 검수받는다.

**작업 공간은 워크트리를 기본으로 한다.** 일반 이슈의 브랜치를 만들 때 현재 체크아웃을 그 브랜치로 끌고 가는 대신, `.claude/worktrees/` 에 격리된 워크트리로 분리하는 것을 추천(첫 번째 옵션)한다. 메인 체크아웃이 작업 중 외부에서 바뀌어도 격리되고 CLAUDE.md "별도 작업은 worktree 로 분리" 정책과 맞다. 사용자가 현재 브랜치 작업을 고를 수 있게 묻되 default 는 워크트리. Epic 은 브랜치 자체가 없어 해당 없음.

**라벨은 한 작업 한 개.** type 차원(`feat`/`fix`/`refactor`/`perf`/`chore`) 과 영역 차원(`docs`/`test`/`infra`) 이 한 라벨로 합쳐져 있다. "외부 가시적 변화" 가 본질이면 그 type, 특정 영역만 만지면 그 영역. multi-label 부여하지 않는다.

**채팅 끊김을 최소화한다.** 자유 텍스트는 한 메시지로 일괄 받고, 옵션 결정은 `AskUserQuestion` 으로 묶어 받는다. 사용자 인터랙션은 보통 **3~4 라운드** (Epic 여부 → 자유 입력 → 검수 → [일반 이슈] 작업 위치) 안에 끝난다.

## 절차

### 1단계: Epic 여부

`AskUserQuestion` (single-select):

- `일반 이슈 (Recommended)`
- `Epic`

대화 컨텍스트가 명확하면 default 로 표시. 모호하면 추측 없이 그대로 보여준다.

선택에 따라 흐름이 갈린다.

---

## A. Epic 흐름

Epic 은 브랜치를 만들지 않고 상위 Epic 도 없다.

### A-1. 자유 입력 (1회)

사용자에게 다음과 같이 안내:

> "이슈 내용을 자유롭게 적어주세요. 제목 / 왜 / 무엇을 모두 포함해 한 번에 작성하시면 됩니다. 모델이 알아서 분해/정리합니다."

사용자 입력이 너무 짧아 분해가 어려우면 (예: "이슈 스킬 추가" 한 줄) 1~2 번 follow-up 질문 (예: "왜 필요한지 한 줄만 더 알려주세요"). 더 이상 묻지 않는다.

### A-2. 모델 자동 분해 + 중복 검사

자유 입력을 분석해 다음을 결정:

- **제목** (한 줄, 70자 이내, 슬러그 의미 보존)
- **왜** (배경, 필요성)
- **무엇을** (목표 상태, 예상 범위)

분해 룰 (다듬기 통합):
- 표현 자연스럽게, 단답을 풍부한 문장으로 풀기 OK
- 적절한 컨텍스트 추론 OK (예: "성능 개선" → "현재 X 처리가 느려 개선 필요")
- ❌ 구체적 수치/날짜/외부 시스템·라이브러리/책임자 새로 만들어내기 금지
- ❌ 사용자가 명시한 사실과 다른 내용 금지

분해된 제목으로 중복 이슈 사전 검사:

```bash
gh issue list --repo TeamPiKi/PIKI-Server --search "{제목 키워드}" --state open --json number,title,labels --limit 5
```

결과가 1개 이상이면 검수 단계에서 사용자에게 함께 보여줌.

### A-3. 검수 — `AskUserQuestion`

분해된 본문 + 중복 검사 결과를 한 화면에 보여주고:

```
[본문 미리보기]
## 왜
{분해된 내용}

## 무엇을
{분해된 내용}

[중복 검사 결과]
유사 이슈: #X "..." (있을 때만)
```

`AskUserQuestion` (single-select):

- `OK 진행 (Recommended)`
- `본문 수정 필요` — Other 로 어떤 부분을 어떻게
- `중복 — 새 이슈 만들지 않음` (중복 검사 결과 있을 때만)

수정 요청 들어오면 반영하고 다시 검수. 만족할 때까지 반복.

### A-4. 이슈 생성

```bash
gh issue create \
  --repo TeamPiKi/PIKI-Server \
  --title "{제목}" \
  --body "{본문}" \
  --label "epic" \
  --assignee @me
```

### A-5. Issue Type 부여

`gh issue create` 는 `--type` 을 지원하지 않으므로 GraphQL `updateIssueIssueType` mutation 으로 별도 부여한다. Epic 은 `Feature` type 매핑.

```bash
ISSUE_ID=$(gh issue view {이슈번호} --repo TeamPiKi/PIKI-Server --json id --jq '.id')
gh api graphql \
  -f query='mutation($issueId:ID!,$typeId:ID!){updateIssueIssueType(input:{issueId:$issueId,issueTypeId:$typeId}){issue{id issueType{name}}}}' \
  -F issueId=$ISSUE_ID \
  -F typeId=IT_kwDOEfJ4WM4CE8Tb
```

### A-6. Project 추가

```bash
gh project item-add 99 --owner depromeet --url {이슈 URL}
```

### A-7. 결과 출력

- 이슈 URL
- "Epic 은 브랜치를 만들지 않습니다. 하위 작업은 `/issue` 로 일반 이슈를 만들고 그 단계에서 이 epic 이 자동 추천됩니다."
- "우선순위 / 일정 등 메타 정보는 GitHub Project 보드에서 직접 채우세요."

---

## B. 일반 이슈 흐름

### B-1. 자유 입력 (1회)

A-1 과 동일.

### B-2. 모델 자동 분해 + 자동 결정 + 중복 검사

**자유 입력 분해** — A-2 와 동일 룰.

**분류 자동 결정** (single-select): `feat` / `fix` / `refactor` / `perf` / `chore` / `docs` / `test` / `infra`

우선순위: **외부 가시적 변화** > **영역 한정**.

1. 외부 가시적 변화 시그널 있으면:
   - "버그", "안 됨", "결함 수정", "예외 발생" → `fix`
   - "성능 개선", "속도", "메모리 절감", "latency" → `perf`
   - "새 API", "새 엔드포인트", "외부 사용자/클라이언트 노출 새 기능" → `feat`
   - "구조 개선", "리팩터링", "정리", "추출" (외부 동작 불변) → `refactor`

2. 외부 동작 변화 없고 특정 영역만 만지면:
   - 문서만 (CLAUDE.md, README, ADR 등) → `docs`
   - 테스트만 → `test`
   - 인프라 (Terraform, AWS, secret, 배포 workflow) → `infra`
   - 그 외 (빌드·deps·CI 게이트·도구·리포 설정·잡일) → `chore`

3. 모호하면 `chore` fallback.

**브랜치 prefix**: 라벨 문자열을 그대로 사용한다 (예: `feat`, `chore`, `infra`). 슬래시는 브랜치명 템플릿(`{prefix}/{이슈번호}-{slug}`)에서만 추가되므로 prefix 자체에 슬래시를 포함하지 않는다 (`feat/` 가 아니라 `feat`).

**상위 Epic 추천**:
```bash
gh issue list --repo TeamPiKi/PIKI-Server --label epic --state open --json number,title --limit 20
```
활성 epic 목록과 본문(제목+왜+무엇을)의 의미 비교로 가장 관련 있는 1개 추천. 없으면 추천 안 함.

**중복 이슈 사전 검사**: A-2 와 동일하게 분해된 제목으로 검색.

### B-3. 검수 — `AskUserQuestion`

본문 + 자동 결정 + 중복 검사 결과를 한 화면에 보여주고:

```
[본문 미리보기]
## 상위 Epic
#{추천 번호}            ← 없으면 통째 생략

## 왜 / 무엇을
...

[자동 결정]
- 분류 라벨: {라벨}
- 브랜치 prefix: {라벨 그대로, 슬래시 없이}
- 상위 Epic: #{번호} 또는 없음

[중복 검사 결과]
유사 이슈: #X "..." (있을 때만)
```

`AskUserQuestion` (single-select):

- `OK 진행 (Recommended)`
- `본문 수정 필요` — Other 로 어떤 부분을 어떻게
- `라벨 수정` — Other 로 어떻게 (라벨이 곧 브랜치 prefix 라 함께 바뀜)
- `중복 — 새 이슈 만들지 않음` (중복 검사 결과 있을 때만, 다른 옵션 1개와 묶어 4개 한도)

수정 반영 후 만족할 때까지 반복.

### B-4. 이슈 생성

```bash
gh issue create \
  --repo TeamPiKi/PIKI-Server \
  --title "{제목}" \
  --body "{본문}" \
  --label "{선택된 분류 라벨}" \
  --assignee @me
```

### B-5. Issue Type 부여

`gh issue create` 는 `--type` 을 지원하지 않으므로 GraphQL `updateIssueIssueType` mutation 으로 별도 부여한다.

**분류 → org type 매핑** (TeamPiKi 조직 정의 type 3종 — Task / Bug / Feature):

| 우리 라벨 | org type | type ID |
|---|---|---|
| `fix` | Bug | `IT_kwDOEfJ4WM4CE8Ta` |
| `feat` | Feature | `IT_kwDOEfJ4WM4CE8Tb` |
| `refactor` / `perf` / `chore` / `docs` / `test` / `infra` | Task | `IT_kwDOEfJ4WM4CE8TZ` |
| (Epic 흐름) | Feature | `IT_kwDOEfJ4WM4CE8Tb` |

라벨이 단일이므로 분기 단순.

```bash
ISSUE_ID=$(gh issue view {이슈번호} --repo TeamPiKi/PIKI-Server --json id --jq '.id')
gh api graphql \
  -f query='mutation($issueId:ID!,$typeId:ID!){updateIssueIssueType(input:{issueId:$issueId,issueTypeId:$typeId}){issue{id issueType{name}}}}' \
  -F issueId=$ISSUE_ID \
  -F typeId={매핑된 type ID}
```

### B-6. 작업 위치 선택 + 브랜치/워크트리 생성

브랜치명 슬러그는 분해된 제목에서 영문 kebab-case 로 생성 (한국어면 의미 보존하며 영문 의역). 브랜치명은 `{prefix}/{이슈번호}-{slug}`, 워크트리 디렉토리명은 `{slug}` (prefix·번호 없이).

브랜치를 어디서 작업할지 `AskUserQuestion` (single-select) 으로 묻는다. **워크트리를 추천(첫 번째)으로 둔다**:

- `워크트리로 분리 (Recommended)` — 현재 체크아웃은 그대로 두고 `.claude/worktrees/{slug}` 에 격리된 작업 공간을 만든다.
- `현재 브랜치에서 작업` — 현재 디렉토리를 새 브랜치로 전환한다 (기존 동작).

#### 워크트리 선택 시

1. GitHub 브랜치 생성 + 이슈 연결. **`--checkout` 을 주지 않는다** — 현재 디렉토리를 끌고 가면 같은 브랜치를 워크트리에서 다시 체크아웃할 수 없어 충돌한다.
   ```bash
   gh issue develop {이슈번호} \
     --repo TeamPiKi/PIKI-Server \
     --base dev \
     --name "{prefix}/{이슈번호}-{slug}"
   ```

2. `gh` 가 만든 원격 브랜치를 가져와 워크트리로 분리한다 (로컬 브랜치는 워크트리에서 생성):
   ```bash
   git fetch origin "{prefix}/{이슈번호}-{slug}":"refs/remotes/origin/{prefix}/{이슈번호}-{slug}"
   git worktree add ".claude/worktrees/{slug}" "{prefix}/{이슈번호}-{slug}"
   ```
   `git worktree add <path> <branch>` 는 로컬에 `{branch}` 가 없고 `origin/{branch}` 가 정확히 하나면 DWIM 으로 추적 브랜치를 만들어 붙인다. DWIM 이 안 되면 명시형으로 fallback: `git worktree add --track -b "{prefix}/{이슈번호}-{slug}" ".claude/worktrees/{slug}" "origin/{prefix}/{이슈번호}-{slug}"`.

3. 세션을 워크트리로 진입시킨다 — `EnterWorktree` 도구를 **`path=".claude/worktrees/{slug}"`** 로 호출 (이미 만든 워크트리에 진입). `name=` 으로 새로 만들지 않는다 — 브랜치는 `gh issue develop` 이 base `dev` 로 이미 만들었고, `EnterWorktree(name=...)` 의 baseRef 기본값(`fresh`=origin/default-branch)은 이 레포에선 `main` 을 가리켜 CLAUDE.md(dev 분기) 와 어긋나기 때문. 이후 작업·커밋·`/pr` 은 이 워크트리에서 진행된다.

#### 현재 브랜치 선택 시 (기존 동작)

```bash
gh issue develop {이슈번호} \
  --repo TeamPiKi/PIKI-Server \
  --base dev \
  --name "{prefix}/{이슈번호}-{slug}" \
  --checkout
```

생성 후 자동 checkout 이 안 되면 명시적으로 `git checkout {prefix}/{이슈번호}-{slug}` 실행.

### B-7. Project 추가

```bash
gh project item-add 99 --owner depromeet --url {이슈 URL}
```

### B-8. 결과 출력

- 이슈 URL
- 브랜치명
- **워크트리 선택 시**: 워크트리 경로(`.claude/worktrees/{slug}`) + "세션이 워크트리로 전환됐고 현재 체크아웃(`dev` 등)은 그대로 유지됩니다" + "작업·커밋·`/pr` 은 이 워크트리에서 진행됩니다"
- **현재 브랜치 선택 시**: 현재 체크아웃된 브랜치 확인
- "우선순위 / 일정은 필요시 GitHub Project 보드에서 채우세요."
- 다음 단계 안내 (작업 시작 → 커밋 → PR)

---

## 주의 사항

- **자유 입력이 너무 짧으면 follow-up.** 다만 1~2 번 안에 끝낸다. 무한 follow-up 금지.
- **모델 분해 결과는 검수 필수.** 사용자가 거부하면 즉시 부분 수정 또는 원본 그대로.
- 분류 자동 결정은 시그널이 명확할 때만. 모호하면 `chore` fallback (보수적).
- 라벨이 레포에 없어 `gh issue create` 가 실패하면 에러 그대로 보고.
- `gh issue develop` 은 `--name` (브랜치 이름 옵션) 을 명시 (인터랙티브 회피). `--branch-name` 은 존재하지 않는 옵션이니 주의. `--checkout` 은 **현재 브랜치 작업을 고른 경우에만** 붙인다 — 워크트리 작업이면 현재 디렉토리가 끌려가 충돌하므로 빼고, 체크아웃은 `EnterWorktree` 가 대신한다.
- **워크트리 진입은 `EnterWorktree(path=...)` 로만.** `git worktree add` 로 먼저 만든 뒤 `path` 로 진입한다. `EnterWorktree(name=...)` 는 새 브랜치를 자체 생성하며 baseRef 기본값이 이 레포의 git default branch(`main`)를 가리켜 `dev` 분기 정책과 어긋난다. `path` 로 진입한 워크트리는 `ExitWorktree` 가 제거하지 않으므로, 정리는 `/session-close` 에 맡긴다.
- `gh project item-add` 권한 부족 시 사용자에게 `gh auth refresh -h github.com -s project` 안내 (인터랙티브 디바이스 인증, 일회성).
- **Project 99 번은 아직 depromeet org 소유라 cross-org 연결이다.** 이슈가 보드에 정상적으로 붙어도 `gh issue view --json projectItems` 는 빈 배열을 돌려주므로, 연결 확인은 `gh project item-list 99 --owner depromeet` 로 한다.
- 본문에 `#{epic 번호}` 가 들어가면 GitHub 가 자동 cross-reference 링크 — 별도 sub-issue API 불필요.
- 중복 이슈 검사 false positive 가능성 인지. 사용자가 "다른 이슈" 라 답하면 그대로 진행.
- 이슈 템플릿(`.github/ISSUE_TEMPLATE/`)이 우선순위·일정을 required 로 정의해도 본문 자유 양식이라 강제받지 않는다. 이 스킬은 본질("왜/무엇을")만 받는 정책을 따른다.
- **Assignee 는 `@me` 고정**. 이슈 만든 사람이 작업자라는 가정. 다른 사람에게 할당하려면 GitHub UI 에서 변경.
- **Issue Type 은 GraphQL 별도 호출.** `gh issue create` 가 `--type` 미지원이라 `updateIssueIssueType` mutation 사용. TeamPiKi org 의 type 은 Task / Bug / Feature 3종 — 우리 9개 라벨과 1:1 매핑이 안 되는 부분은 가장 가까운 type 으로 (refactor·perf·chore·docs·test·infra → Task, epic → Feature). org 가 type 을 추가/제거하면 본문의 type ID 도 갱신 필요.

$ARGUMENTS
