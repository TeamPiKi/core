브랜치에서 작업한 내용을 STAR 구조 PR로 정리하여 GitHub에 올립니다. 이미 PR이 있으면 `## Updates` 섹션에 증분을 append 하되, 기존 서술을 무효화하는 변경은 본문 STAR 도 최종 상태로 함께 고칩니다 (살아있는 본문 — `### 3-B` 5번). assignee(`@me`) · 라벨(연관 이슈에서 복사, 없으면 브랜치 prefix) · Project(99) 도 자동 설정합니다.

## PR 본문 작성 원칙

**대화 컨텍스트가 핵심이다.** diff 요약이 아니라, 이번 세션에서 나눈 고민·트레이드오프·결정 이유가 PR의 가치다.

**맥락은 담되 산문으로 늘어놓지 않는다.** 맥락이 많을수록 산문은 길어져 가독성이 떨어진다. "무엇을 담을지(맥락)" 는 유지하고 "어떻게 보일지(구조)" 를 더한다 — 대조·비교 정보는 표로, 동작 규칙은 굵은 키워드 불릿로 (구체 규칙은 아래 2단계 작성 지침).

## 절차

### 0단계: 작업 위치 가드 + 모드 결정 + base branch 자동 감지

**0-A. 작업 위치 가드 (워크트리 감지)** — `/pr` 의 모든 git/gh 명령은 현재 작업 디렉토리(cwd) 기준으로 돈다. 세션이 워크트리 안에 있으면 git worktree 특성상 자동으로 그 워크트리 브랜치를 바라보므로 별도 처리가 필요 없다. 문제는 cwd 가 작업 브랜치와 어긋난 경우 — 워크트리에서 작업해놓고 메인 체크아웃(base 브랜치)에서 `/pr` 을 부르면 조용히 틀린 PR(또는 "변경 없음")이 만들어진다. 이를 먼저 거른다.

```bash
CURRENT_BRANCH=$(git branch --show-current)
# origin 최신화 — base 판정과 1단계 log·diff, Start date 계산은 전부 origin/$BASE 기준이라, fetch 없이는 stale 참조로 남의 커밋이 diff 에 섞인다.
# 실패 시 중단 — stale 기준으로 그냥 진행하면 위 문제가 조용히 그대로 일어난다 (네트워크·인증 확인 후 재시도).
git fetch origin -q || { echo "git fetch 실패 — origin 이 stale 인 채 진행하지 않는다. 네트워크 확인 후 재시도."; exit 1; }
# 진입 정리: 7일 넘게 안 건드린 stale PR 본문 임시파일 제거 (session-close 를 안 거친 중단 작업의 누수를 회수 — mtime 기준이라 동시 세션의 최신 파일은 안 건드림). 임시파일은 지워져도 gh pr view 로 재생성돼 손실이 없으므로, 진행 중 장기 PR(리뷰 대기 등 며칠 걸침)을 절대 안 건드리도록 임계값을 넉넉히 7일로 둔다.
# -mmin +10080 = 7일(10080분) 초과. -mtime 계열은 find 에서 +1일 반올림되니 -mmin 으로 명시. /tmp/ 의 trailing slash 필수 — macOS /tmp 는 /private/tmp symlink 라, 슬래시 없으면 find 가 symlink 를 안 따라가 0건(조용한 no-op)이 된다.
find /tmp/ -maxdepth 1 -name 'pr_body_*.md' -mmin +10080 -delete 2>/dev/null
# base 후보 (아래 0-B 의 $BASE 결정과 동일 우선순위: origin/dev → 레포 default → main)
if git rev-parse --verify origin/dev >/dev/null 2>&1; then
  BASE_GUESS=dev
else
  BASE_GUESS=$(gh repo view --json defaultBranchRef --jq '.defaultBranchRef.name' 2>/dev/null || echo main)
fi
echo "CURRENT_BRANCH=$CURRENT_BRANCH BASE_GUESS=$BASE_GUESS"   # 아래 같음/다름 판단과 뒤 블록의 $BASE 인라인이 이 출력을 근거로 한다
```

`$CURRENT_BRANCH` 가 `$BASE_GUESS` 와 **다르면** 정상(작업 브랜치) — 가드를 통과해 0-B 로 넘어간다.

`$CURRENT_BRANCH` 가 `$BASE_GUESS` 와 **같으면** PR 을 올릴 작업 브랜치가 아니다. 작업은 다른 워크트리에 있을 가능성이 크다:

```bash
git worktree list --porcelain   # base 가 아닌 브랜치를 가진 워크트리 = 작업 후보
```

- **작업 후보 워크트리가 있으면** `AskUserQuestion` (single-select) 으로 "그 워크트리로 진입해 `/pr` 을 이어갈까요?" 를 묻는다 (**진입 = Recommended, 첫 번째**). 후보가 여럿이면 각 워크트리(경로 + 브랜치)를 옵션으로 나열한다.
  - **진입 동의** → `EnterWorktree` 도구를 `path={선택한 워크트리 경로}` 로 호출해 세션을 옮긴 뒤, **0단계를 처음부터 다시 시작**한다 (이제 cwd 가 워크트리라 `CURRENT_BRANCH` 가 feature 브랜치 → 가드 통과).
  - **거부** → 멈춘다. "작업 워크트리에서 직접 `/pr` 을 불러주세요" 안내.
- **작업 후보 워크트리가 없으면** (다른 워크트리도 전부 base 이거나 워크트리가 메인뿐) — base 브랜치에서 `/pr` 을 부른 셈이라 올릴 작업 브랜치가 안 보인다. 그 사실을 알리고 멈춘다 (`$ARGUMENTS` 로 다른 base 를 명시한 의도적 dev→main 류 PR 이면 사용자가 다시 알려준다).

**0-B. 모드 결정 + $BASE 자동 감지**

**현재 브랜치의 PR 존재 여부 확인** — update 모드 vs create 모드 결정:

```bash
gh pr view --json url,number,body,baseRefName 2>/dev/null
```

- 결과 있음 → **update 모드** (`### 3-B`)
- 결과 없음 → **create 모드** (`### 3-A`)

**`$BASE` 결정**:

- **create 모드** — 0-A 에서 계산한 `$BASE_GUESS` 를 그대로 쓴다 (`BASE=$BASE_GUESS`). 우선순위: origin/dev → 레포 default branch → main.
- **update 모드** — 기존 PR 의 base 사용:
  ```bash
  BASE=$(gh pr view --json baseRefName --jq '.baseRefName')
  ```
- `$ARGUMENTS` 에 사용자가 base 명시한 경우 (`/pr main` 같은) 그 값을 우선 (create 모드 한정 — update 모드에서 base 변경하지 않는다).

**로컬 git 비교는 항상 `origin/$BASE` 기준이다.** `$BASE`(브랜치 이름)는 `gh pr create --base $BASE` 같은 GitHub 쪽 지정에만 그대로 쓰고, `git log`·`git diff`·Start date 계산 등 로컬 비교는 전부 `origin/$BASE` 를 쓴다 — 로컬 `dev` 는 stale 일 수 있어 그걸 기준 삼으면 머지로 끌려온 남의 커밋이 diff 에 부풀려 섞인다 (0-A 의 fetch 가 `origin/$BASE` 최신을 보장).

**임시파일 경로 규칙 (동시 세션 격리)** — PR 본문 임시파일은 고정 `/tmp/pr_body.md` 가 아니라 **브랜치별 경로** `/tmp/pr_body_$SLUG.md` 를 쓴다 (`SLUG` = 브랜치명의 `/` 를 `_` 로 치환, 예: `chore/skill-tmp` → `/tmp/pr_body_chore_skill-tmp.md`). 워크트리는 브랜치당 하나라(스택 금지) 브랜치별 경로면 두 워크트리 세션이 동시에 `/pr` 을 돌려도 본문 파일이 안 겹친다 — 고정 경로일 때 한 세션이 다른 세션의 본문을 덮어쓰던 race 를 막는다. 아래 3-A·3-B 의 본문 파일 경로는 모두 이 규칙을 따른다. **셸 변수는 bash 호출 간 유지되지 않으므로, 본문 파일을 다루는 각 bash 블록은 `SLUG=$(git branch --show-current | tr '/' '_')` 를 자기 안에서 다시 구한다. 같은 이유로 `$BASE`·`$ISSUE_LABELS` 처럼 앞 블록에서 결정된 값도 뒤 블록에서 변수 참조로 기대지 않는다 — 결정 시점에 echo 로 남긴 실제 값을 뒤 블록에 인라인으로 박는다.** (Write 도구로 본문을 저장할 때도 같은 경로를 쓴다 — Claude 가 현재 브랜치명으로 슬러그를 박는다.)

### 1단계: 정보 수집

아래 명령을 병렬로 실행하여 변경 내역을 파악한다 (`$BASE` 는 0단계에서 결정):

- `git log origin/$BASE..HEAD --oneline` — 커밋 목록
- `git diff origin/$BASE...HEAD --stat` — 변경 파일 요약
- `git diff origin/$BASE...HEAD` — 실제 변경 내용
- `git status` — 현재 상태 (커밋되지 않은 변경이 있는지)

커밋되지 않은 변경이 있으면 먼저 커밋할지 사용자에게 확인한다.

**브랜치 이름에서 이슈 번호 자동 추출**:
정규식 `^[a-z]+/(\d+)-` 로 추출 (예: `chore/75-pr-skill-upgrade` → `#75`).

- 매칭 → `## 연관 이슈\n- close #{번호}` 본문에 자동 채움
- 매칭 안 됨 → 섹션은 유지하되 항목을 빈 줄로 둔다 (`- ` 만). 이슈 미연결 상태가 본문에서 명시적으로 드러나야 작성자가 의도적으로 비웠음을 인지할 수 있다.

**PR 라벨 결정 — 연관 이슈 라벨 우선, 없으면 브랜치 prefix** (이슈 매칭 여부와 무관하게 항상 수행):
PR 은 연관 이슈와 같은 분류를 갖는 것이 자연스러우므로 이슈 라벨을 우선하고, 이슈가 없으면 브랜치 prefix 가 곧 분류 라벨이다 — `/issue` 의 B-2 가 만드는 **8개 prefix 는 라벨과 1:1** 이다 (전체 1:1 은 아니다: `epic` 라벨은 브랜치가 없고 커밋 타입 `style` 은 라벨이 없다 — 그래서 아래 "실재 라벨 검증" 가드가 필요하다). 이 fallback 이 없으면 이슈 없이 만든 브랜치의 PR 이 조용히 라벨 없이 올라간다 (Discord PR 봇의 라벨 표시 등 하류도 함께 빈다).

```bash
# 한 블록에서 끝까지 계산하고 echo 로 값을 남긴다 — 셸 변수는 bash 호출 간 유지되지 않으므로,
# 3-A·3-B 10단계 블록은 이 출력으로 확인한 값을 인라인으로 박아 쓴다 (SLUG 재도출과 같은 규칙).
ISSUE_LABELS=$(gh issue view {번호} --json labels --jq '[.labels[].name] | join(",")' 2>/dev/null)   # 이슈 번호 미매칭이면 이 줄은 건너뛴다
if [ -z "$ISSUE_LABELS" ]; then
  PREFIX=$(git branch --show-current | cut -d/ -f1)
  # 레포에 실재하는 라벨만 채택 (오타·비표준 prefix 방어). --limit 명시: 기본 30이라 라벨이 늘면 조용히 놓친다. grep -F: prefix 를 정규식이 아닌 리터럴로 비교.
  gh label list --limit 100 --json name --jq '.[].name' | grep -Fqx "$PREFIX" && ISSUE_LABELS=$PREFIX
fi
echo "ISSUE_LABELS=${ISSUE_LABELS:-없음}"
```

- 라벨이 있으면 `### 3-A`(LABEL_ARGS 배열) / `### 3-B` 10단계(EDIT_ARGS 배열)에서 부여한다. 두 블록 모두 **위 echo 로 확인한 값을 인라인으로 박는다** — 셸 변수가 블록 간 유지되지 않아서다.
- fallback 까지 비면 그때만 라벨 없이 진행한다. (변수명은 `ISSUE_LABELS` 를 유지한다 — 출처가 어디든 "이 PR 의 분류 라벨" 이라는 의미는 같다.)

### 2단계: STAR 본문 작성 — create 모드 한정

(update 모드는 `### 3-B` 의 자체 가이드를 따른다 — Updates 섹션에 짧은 STAR 항목을 append 하고, 이번 변경이 기존 본문을 낡게 만들면 그 자리도 함께 갱신)

이번 대화에서 나눈 내용을 중심으로, 아래 템플릿을 채운다:

```
## Situation
- 이 작업이 필요했던 배경/문제 상황
- 대화에서 논의된 동기나 맥락

## Task
- 해결하려 한 핵심 과제
- 대화에서 중요하게 다뤘던 고민이나 결정 포인트

## Action
- 실제로 한 일 (코드 변경 기반)
- 대화에서 논의한 트레이드오프나 선택의 이유

## Result
- 변경의 결과/효과 (CI 가 보증하는 "테스트 통과" 류 자명한 사실은 적지 않음 — 아래 작성 지침)
- 주의할 점이나 후속 작업이 있으면 언급

---
## 연관 이슈

- close #{자동 추출된 번호}     ← 추출 실패 시 `- ` 빈 항목으로
```

**작성 지침:**
- 각 섹션은 bullet point(`-`)로 작성
- 대화에서 나온 고민, 왜 이 방식을 선택했는지, 어떤 대안을 검토했는지를 우선 반영
- diff에서만 보이는 기계적 변경 나열은 최소화
- **사람 말로 먼저, 코드 이름은 최소.** 클래스·메서드·상수명을 모든 문장에 박지 않는다. 본문은 일상어로 "무엇을·왜" 를 말하고, 꼭 필요한 식별자는 Action 의 "구현" 묶음 한 곳에 모은다. 기준: 이 도메인 처음 보는 팀원이 표·첫 문장만으로 "누가 무엇을" 을 이해하는가. (정밀도를 버리는 게 아니라 자리를 옮기는 것이다. 코드 디테일을 없애지는 않는다.)
- **대조·비교 정보는 표로.** 역할·분기·매핑("누가 무슨 알림을 받나"), 응답 계약(케이스별 status·code·detail), 모델·옵션 비교처럼 **대상·조건이 갈리는 정보**는 산문보다 표가 빠르게 읽힌다. 행=대상/케이스, 열=비교 축으로 둔다.
- **결정 트레이드오프는 비교 표로.** 대안을 저울질한 결정(A 안 vs B 안)은 선택지를 행, 장단점·비용을 열로 둔 비교 표로 보이면 왜 그 선택을 했는지가 한눈에 드러난다. 검토 후 채택 안 한 안도 같은 표에 함께 둔다.
- **동작 규칙은 굵은 키워드 불릿로.** "이럴 땐 이렇게 동작한다" 류 규칙은 핵심 키워드를 **굵게** 앞세운 불릿로 끊어 쓴다. 산문 한 문단에 규칙 여러 개를 잇지 않는다.
- **수학기호·과한 약어 금지.** `∪`·`∩` 같은 기호는 "그리고/합쳐서/둘 다" 로, 약어는 풀어서 쓴다.
- **한 문장 한 뜻.** 절을 여러 개 길게 잇지 말고 끊는다.
- **섹션 간 재진술 금지 — 각 섹션은 자기 정보만 한 번.** 특히 Result 는 Situation/Task 에 이미 쓴 문제·과제를 "~문제가 해소됐다"로 다시 풀어 쓰지 않는다. Result 에는 새 정보만 — 결과 수치·효과·리스크·후속. (앞에서 깔고 → 과제로 다시 → 결과로 또 풀면 같은 내용이 본문에 세 번 실려 비대해진다.)
- Task 섹션은 Discord PR 봇이 읽으므로, 핵심 작업을 간결하게 요약
- 한국어로 작성, 기술 용어는 영어 허용
- **제목·본문에 이모지·물결(`~`)·em dash(`—`)를 쓰지 않는다.** 이모지는 렌더링 환경에 따라 깨지고 미적 선호에도 어긋난다. `~text~` 는 GitHub-flavored markdown 이 취소선(strikethrough)으로 렌더링해 두 물결 사이 텍스트를 통째로 줄 그어버린다. em dash 는 가독성 선호상 쓰지 않는다. 대체: 곁가지·부연은 쉼표·괄호·콜론(`:`)이나 문장 분리로, approximately 는 "약", 범위는 "에서"나 하이픈(`-`)으로 표현한다.
- **1단계에서 수집한 `git log` 의 모든 커밋이 STAR(특히 Action)에 빠짐없이 반영됐는지 최종 점검한다.** 해시 명기는 update 모드 전용이지만, "누락된 커밋이 없는지" 점검은 create 모드도 같은 레벨로 거친다 — 기억·추측이 아니라 로그와 대조한다.
- **CI 가 보증하는 자명한 결과는 본문에 적지 않는다.** "전체 테스트 통과"·"컴파일 성공"·"그린"·"전체 회귀 통과" 같은 머지 전제 사실은 리뷰어에게 새 정보가 0 이므로 Result 에서 뺀다. 검증은 **결과가 아니라 "무엇을·어떻게·왜 그렇게 확인했나"가 비자명할 때만** 적는다 — 동시성·negative control(임시 제거 시 FAIL 확인 등)·실측으로 확정한 가정·분기 망라의 폭(예: 케이스 N건)·검증의 한계와 후속 같은 것. 단순 통과 단언과 비자명한 검증 설명을 구분하라. 테스트 결과 XML·로그 전문을 `<details>` 로 덤프하지 않는다 (필요하면 한 줄로 요약).

**Action 섹션 그룹화 가이드:**
- bullet 이 6개 이상이거나 결이 다른 갈래(설계 / UX / 안전망 / 드라이푸딩 / 검토 후 채택 안 한 안 등)가 섞이면 sub-heading(`### 설계` 등)으로 그룹화한다.
- 그룹은 작업 성격에 따라 자유롭게 — 매번 같은 그룹명을 쓸 필요 없음. 이번 PR 이 다룬 결을 가장 잘 드러내는 이름으로.
- bullet 5개 이하의 짧은 PR 은 그룹화 없이 평범한 bullet 리스트로 둔다.
- 한 그룹 안에서도 대상·조건이 갈리는 변경(엔드포인트별 응답, 케이스별 분기 등)은 불릿 나열 대신 표로 묶는다 — 그룹 경계와 무관하게 "대조는 표로" 를 적용한다.

**채울 수 없는 섹션 처리:**
- 대화 컨텍스트나 diff에서 근거를 찾을 수 없는 섹션은 **억지로 채우지 않는다**
- 해당 섹션에 `- TODO: 작성자가 직접 보완해주세요` 를 남긴다
- 부분적으로만 파악 가능한 경우, 파악된 내용만 적고 나머지에 `- TODO: ...` 를 추가한다

### 본문 확인 — IDE 로 열기 (create · update 공통)

본문 초안을 채팅에 길게 펼치지 않고, 저장한 본문 파일을 IntelliJ 로 열어 사용자가 **직접 보고 편집**하게 한다. 임시 파일이 single source 이므로 사용자가 IDE 에서 고친 내용이 그대로 `--body-file` 로 올라간다 (채팅 copy-paste 왕복 제거, 마크다운 프리뷰로 표가 표처럼 보임).

```bash
SLUG=$(git branch --show-current | tr '/' '_')
BODY=/tmp/pr_body_$SLUG.md
if [[ -z "${CI:-}" ]] && command -v idea >/dev/null 2>&1; then
  idea "$BODY"        # IntelliJ 로 본문 열기 (사람이 도는 로컬의 기본 경로)
else
  cat "$BODY"         # idea 가 없거나 CI 등 GUI 없는 실행: 인라인 출력 폴백
fi
```

- 연 뒤 **확인 게이트를 `AskUserQuestion` 번호 선택으로 띄운다.** 자유 텍스트로 "검토 후 진행하라고 하세요" 라고 묻지 않는다. 사용자가 매번 답을 타이핑하지 않고 번호로 고르게 한다. 채팅엔 제목 한 줄만 짧게 남기고, 본문은 IDE 에 띄운 채 다음 두 선택지를 `AskUserQuestion` 으로 묻는다. **첫 옵션의 동사는 0-B 에서 이미 결정된 모드를 그대로 따른다 — create 면 "올린다", update 면 "갱신한다". 모드는 `gh pr view` 의 PR 존재 여부로 결정론적으로 갈리므로(주관 판단이 아니다) 실제 호출엔 그 한 단어만 넣는다. 슬래시로 둘 다 띄우지 않는다:**
  - **이대로 올린다** (update 모드면 "이대로 갱신한다"): 현재 본문 그대로 진행한다. 사용자가 IDE 에서 직접 고쳤든 초안 그대로든, `gh pr create` · `gh pr edit` 가 `--body-file` 로 그 시점 파일을 읽어 반영한다.
  - **취소**: PR 생성·갱신을 멈춘다.
- 본문을 고치고 싶은 사용자는 `AskUserQuestion` 이 항상 제공하는 **Other 에 "이렇게 고쳐줘" 를 직접 입력**한다. Claude 가 그 요청대로 본문을 고친 뒤 이 게이트를 다시 띄운다. **"수정해줘"를 별도 명시 옵션으로 두지 않는 이유**: 옵션은 눌러도 수정 내용이 안 담겨 Claude 가 "무엇을 고칠지"를 한 번 더 물어야 하고, 그러면 취소와 다를 바 없다. Other 자유 입력은 수정 내용을 한 번에 전달하므로 명시 옵션이 불필요하다. (IDE 직접 편집도 별도 옵션이 아니다 — 고치고 "올린다"를 누르면 반영되어 첫 선택지에 흡수된다.)
- GUI 만 띄우고 끝내지 않는다. 반드시 이 번호 선택 게이트로 사용자 결정을 받는다.
- 사용자가 IDE 에서 본문을 편집·저장할 수 있으므로, **확인 이후 그 파일을 다시 Write 로 덮어쓰지 않는다.** `gh pr create` / `gh pr edit` 는 `--body-file` 로 파일을 그 시점에 읽어 사용자 편집을 자동 반영한다. 최종 본문을 알아야 하면 Write 가 아니라 Read 로 다시 읽는다.
- 폴백은 `idea` 가 없거나 `CI` 가 설정된 실행(GitHub Actions 등) 한정이다 — 사람이 도는 로컬은 IntelliJ 가 깔려 있다고 가정한다. `DISPLAY` / `WAYLAND_DISPLAY` 로 GUI 가능 여부를 판별하지 않는다: macOS 는 그 둘이 없어도 GUI 가 동작해, 그 변수로 막으면 로컬 맥에서 늘 폴백으로 떨어져 기능이 죽는다.

### 3-A. Create 모드 — PR 신규 생성

1. 원격에 푸시되지 않았으면 `git push -u origin {브랜치명}`
2. PR 제목과 본문 초안을 작성해 **`/tmp/pr_body_$SLUG.md` 에 저장(Write)**한다 (경로 규칙은 0단계 참조 — Claude 가 현재 브랜치 슬러그를 박는다). 이어서 본문 파일을 IDE 로 열어 확인받는다 (위 "본문 확인 — IDE 로 열기"). 제목은 채팅에 한 줄로 함께 보인다.
3. 확인 후 PR 생성 — assignee / 라벨을 함께 부여한다:
   ```bash
   SLUG=$(git branch --show-current | tr '/' '_')
   ISSUE_LABELS="{1단계 echo 로 확인한 값. '없음'이면 빈 값}"   # 셸 변수는 블록 간 미유지 — SLUG 처럼 블록 안에서 확정한다 ($BASE 는 0-A echo 의 BASE_GUESS)
   # 라벨 플래그는 배열로 — `${VAR:+--label "$VAR"}` 관용구는 zsh 가 unquoted 확장을
   # word split 하지 않아 "--label chore" 한 단어가 되어 unknown flag 로 터진다 (bash/zsh 양쪽 안전형).
   LABEL_ARGS=()
   [ -n "$ISSUE_LABELS" ] && LABEL_ARGS=(--label "$ISSUE_LABELS")
   gh pr create --base $BASE \
     --title "{제목}" \
     --body-file /tmp/pr_body_$SLUG.md \
     --assignee @me \
     "${LABEL_ARGS[@]}"
   ```
   - `--assignee @me` — PR 작성자가 작업자라는 가정 (`issue` 스킬과 동일).
   - `--label` — 1단계에서 수집한 `$ISSUE_LABELS`(연관 이슈 라벨, 없으면 브랜치 prefix fallback)가 있을 때만 붙인다.
   - 라벨이 레포에 없어 실패하면 라벨 없이 재시도하고 사용자에게 보고한다.
4. **Project 추가 + Status In review + Start date** — 생성된 PR 을 Project 99 에 등록하고 Status 를 `In review`, Start date 를 첫 commit author date 로 세팅한다. `item-add` 만 하면 기본값 `Backlog` 이 되므로, 반환된 item id 로 후속 mutation 들을 이어서 호출한다:
   ```bash
   ITEM_ID=$(gh project item-add 99 --owner depromeet --url {PR URL} --format json --jq '.id')

   # Status → In review
   gh project item-edit \
     --project-id PVT_kwDOARZVGM4BVVRV \
     --id "$ITEM_ID" \
     --field-id PVTSSF_lADOARZVGM4BVVRVzhQxyAA \
     --single-select-option-id df73e18b

   # Start date → 첫 commit author date (작업이 실제로 시작된 시점의 fact)
   # --first-parent --no-merges: 브랜치 메인라인의 첫 커밋만 — 머지로 끌려온 다른 topic 의 옛 커밋이 시작일을 앞당기지 않게
   START_DATE=$(git log --reverse --first-parent --no-merges "origin/$BASE..HEAD" --format=%aI | head -1 | cut -d'T' -f1)
   gh api graphql -F itemId="$ITEM_ID" -F date="$START_DATE" -f query='
     mutation($itemId: ID!, $date: Date!) {
       updateProjectV2ItemFieldValue(input: {
         projectId: "PVT_kwDOARZVGM4BVVRV"
         itemId: $itemId
         fieldId: "PVTF_lADOARZVGM4BVVRVzhQxyGA"
         value: { date: $date }
       }) { projectV2Item { id } }
     }'
   ```
   - PR 을 올린다는 것은 곧 리뷰 대기 상태이므로 `In review` 가 자연스럽다. create 모드는 방금 만든 PR 이라 Status 가 항상 기본값(`Backlog`)이므로 조건 없이 세팅한다 (update 모드 10단계는 기존 Status 를 확인 후 분기).
   - Start date 는 "이슈 생성일" 이 아니라 **첫 commit author date** 를 쓴다 — 이슈만 만들어 두고 작업 안 들어가는 백로그 케이스의 노이즈를 피하기 위해. 첫 commit 은 history rewrite 가 없는 한 바뀌지 않는 fact 라 create 모드에선 무조건 set.
   - ID 의미: project=99 노드 ID, Status 필드/`In review` 옵션 ID, Start date 필드 ID(`PVTF_..GA`, DATE 타입). 보드에서 필드/옵션이 바뀌면 이 ID 들도 갱신 필요.
   - Target date 와 Status `Done` 은 PR 머지 시점에 별도 CI workflow (`.github/workflows/pr-merge-project-sync.yml`) 가 자동 세팅. PR 스킬은 머지 이전 단계만 책임짐.
   - 권한 부족 시 사용자에게 `gh auth refresh -h github.com -s project,read:project` 안내 (일회성 디바이스 인증).
5. PR URL 과 부여된 assignee / 라벨 / Project(Status: In review, Start date) 결과를 사용자에게 전달한다.

### 3-B. Update 모드 — 기존 PR 본문 갱신

1. 기존 본문 가져오기 (브랜치별 경로 — 0단계 규칙):
   ```bash
   SLUG=$(git branch --show-current | tr '/' '_')
   gh pr view --json body --jq '.body' > /tmp/pr_body_$SLUG.md
   ```
2. **이번 추가 변경 내역을 `git log` 로 정확히 식별한다 — 기억·추측에 의존하지 않는다.**
   ```bash
   git log origin/$BASE..HEAD --oneline   # PR 의 전체 커밋
   ```
   - PR 전체 커밋 중 기존 본문·`## Updates` 에 **이미 반영된 커밋(해시로 대조)** 을 제외해, 이번에 새로 추가된 커밋만 가려낸다.
   - merge 커밋이 있으면 `--no-merges` 로 우리 커밋만, merge 사실 자체는 별도 항목으로 다룬다.
   - 가려낸 커밋 목록과 실제 변경(`git diff`)을 대조해 누락이 없는지 확인한다.
3. 가려낸 새 커밋들에 대해 짧은 STAR 형식 항목을 작성한다.
   - **각 항목 끝에 해당 커밋의 short hash(7자리)를 `` (`a1b2c3d`) `` 형태로 명기**한다. 어떤 변경이 어떤 커밋인지 리뷰어가 추적할 수 있고, 다음 갱신 때 "무엇이 이미 반영됐는지" 대조 기준이 된다.
   - 한 항목이 여러 커밋을 묶으면 해시를 모두 적는다 (예: `` (`d3e53ab`, `cbef613`) ``).
   - 모든 새 커밋이 어느 항목엔가 반영됐는지 최종 점검한다 — 빠진 커밋이 없어야 한다.
4. 기존 본문 끝에 `## Updates` 섹션이 없으면 새로 추가, 있으면 그 안에 새 항목 append.
   - 항목은 날짜 또는 추가 변경의 의도를 sub-heading 으로 (`### CodeRabbit 리뷰 대응`, `### dev 머지 충돌 해결` 등)
5. **본문 STAR 는 항상 최종 진실을 유지한다 (살아있는 본문).** 기준 한 줄: **"이번 변경으로 기존 본문이 거짓말을 하게 되는가."**
   - **순수 추가 작업** (기존 서술을 무효화하지 않는 리뷰 반영·버그 수정·보강) → 기존 본문은 건드리지 않고 Updates 항목 추가만.
   - **본문을 거짓으로 만드는 변경** (접근 전환·스코프 변경·응답 계약 변경 등) → Updates 항목 추가에 **더해**, 낡은 서술이 있는 본문 자리(Action·Result 등)를 최종 상태로 고친다. 큰 전환은 "처음 시도 → 전환한 흐름" 자체를 STAR 서사에 흡수한다 (`/commit` 본문 철학과 같은 결).
   - 본문을 고친 update 는 해당 Updates 항목에 **"본문 Action 갱신"** 처럼 어느 섹션을 고쳤는지 한 줄 명기한다 — 증분(Updates)만 보는 리뷰어가 본문 변화를 놓치지 않게.
   - **남이 쓴 본문은 건드리지 않는다.** 갱신 범위는 이 스킬이 쓴 STAR 로 한정한다. 사람이 GitHub 에서 직접 고친 문구는 그대로 보존하고, 봇 소유 블록(CodeRabbit 의 `<!-- ... auto-generated ... -->` 마커 쌍 등)은 마커째 통째로 유지한다 — `gh pr edit --body-file` 은 전체 덮어쓰기라 한 번 누락되면 복구되지 않는다.
   - **`## Updates` 항목은 원장이라 고치지 않는다.** 2단계 해시 dedup 의 대조 기준이므로 낡아 보여도 재작성하지 않고, 정정이 필요하면 새 Updates 항목으로 덧붙인다. "살아있는" 대상은 본문 STAR 만이다.
   - 이유: 스쿼시 머지라 dev 히스토리에 증분이 안 남아 **PR 본문이 유일한 원장**이다. 머지 후 blame 으로 오는 독자가 "초판 + 정오표"를 머릿속에서 리플레이하지 않고 본문만 읽으면 되게 한다. 본문이 늘 최종이므로 머지 시점의 별도 통합(fold) 단계도 필요 없다 — 언제 어디서 머지되든 본문은 이미 완결 서사다.
6. **제목 변경 필요 검토**: 추가 변경으로 작업 의도/스코프가 바뀌었거나 기존 제목에 오타·부정확한 표현이 있으면 새 제목 제안. 그 외엔 제목 유지.
7. 갱신본(최신화된 본문 + Updates)을 `/tmp/pr_body_$SLUG.md` 에 저장(Write)한 뒤 IDE 로 열어 확인받는다 (위 "본문 확인 — IDE 로 열기"). 새 제목이 있으면 채팅에 제목·변경 이유를 함께 짚는다.
8. 확인 후 `gh pr edit --body-file /tmp/pr_body_$SLUG.md` 로 갱신 (1번과 같은 브랜치 경로 — 별도 bash 호출이라 `SLUG=$(git branch --show-current | tr '/' '_')` 를 다시 구한다). 제목 변경이 있으면 `--title "새 제목"` 추가.
9. **CodeRabbit 리뷰 대응** — 이번 변경이 CodeRabbit 리뷰 대응이라면 commit + push 로 끝내지 않는다. CodeRabbit 리뷰(인라인 thread + review body nitpick) 조회·평가·reply·resolve 는 **`/coderabbit` 스킬**로 처리한다. 그 스킬이 author 매칭(GraphQL `reviewThreads` 는 `coderabbitai`, REST `reviews` 는 `coderabbitai[bot]` 이라 `coderabbitai` 로 시작하는지로 판별), nitpick 조회, accept/reject reply·resolve 정책을 담는다. (사람 리뷰 thread 는 작성자가 직접 답하므로 `/coderabbit` 도 건드리지 않는다.)

10. **메타데이터 보정** — 이전 버전 스킬로 만든 PR 은 assignee / 라벨 / Project / Start date 가 비어 있을 수 있다. update 모드에서도 멱등하게 보정한다 (이미 설정돼 있으면 no-op). `item-add` 는 이미 등록된 PR 이면 기존 item id 를 그대로 반환한다.
    Status 는 **현재 값을 먼저 조회해, 리뷰 이전 단계(`Backlog` / `Ready` / `In progress`)일 때만** `In review` 로 올린다 — 이미 `Done` 등으로 옮긴 PR 을 되돌리지 않기 위함이다. Start date 도 멱등 — 이미 set 되어 있으면 건드리지 않는다 (사람이 수동으로 다른 의미로 박았을 수 있어 보존). Status / Start date 조회는 item 노드를 직접 부르는 GraphQL 이 안정적이다 (`gh project item-list` 는 단일 선택 필드 값을 신뢰성 있게 주지 않는다):
    ```bash
    ISSUE_LABELS="{1단계 echo 로 확인한 값. '없음'이면 빈 값}"   # 셸 변수는 블록 간 미유지 — 블록 안에서 확정한다
    EDIT_ARGS=(--add-assignee @me)                               # 조건부 플래그는 배열로 (3-A 와 동일한 zsh 함정 회피, 호출 1회)
    [ -n "$ISSUE_LABELS" ] && EDIT_ARGS+=(--add-label "$ISSUE_LABELS")
    gh pr edit "${EDIT_ARGS[@]}"
    ITEM_ID=$(gh project item-add 99 --owner depromeet --url {PR URL} --format json --jq '.id')

    # Status + Start date 현재 값 동시 조회. @tsv 출력이라 IFS 를 탭으로 고정한다.
    # 기본 IFS(공백 포함)면 "In review"·"In progress" 같은 공백 포함 단일 선택 값이 첫 단어에서
    # 쪼개져 CURRENT_STATUS 가 "In" 으로 잘리고, In review 승격 분기가 조용히 어긋난다.
    IFS=$'\t' read -r CURRENT_STATUS CURRENT_START < <(gh api graphql -F id="$ITEM_ID" -f query='
      query($id: ID!) {
        node(id: $id) {
          ... on ProjectV2Item {
            status: fieldValueByName(name: "Status") {
              ... on ProjectV2ItemFieldSingleSelectValue { name }
            }
            start: fieldValueByName(name: "Start date") {
              ... on ProjectV2ItemFieldDateValue { date }
            }
          }
        }
      }' --jq '[(.data.node.status.name // "null"), (.data.node.start.date // "null")] | @tsv')

    # Status: 리뷰 이전 단계일 때만 In review 로 올림
    if [[ "$CURRENT_STATUS" == "Backlog" || "$CURRENT_STATUS" == "Ready" || "$CURRENT_STATUS" == "In progress" ]]; then
      gh project item-edit \
        --project-id PVT_kwDOARZVGM4BVVRV \
        --id "$ITEM_ID" \
        --field-id PVTSSF_lADOARZVGM4BVVRVzhQxyAA \
        --single-select-option-id df73e18b
    fi

    # Start date: 비어있을 때만 첫 commit author date 로 세팅
    if [[ "$CURRENT_START" == "null" ]]; then
      # --first-parent --no-merges: 브랜치 메인라인의 첫 커밋만 — 머지로 끌려온 다른 topic 의 옛 커밋이 시작일을 앞당기지 않게
   START_DATE=$(git log --reverse --first-parent --no-merges "origin/$BASE..HEAD" --format=%aI | head -1 | cut -d'T' -f1)
      gh api graphql -F itemId="$ITEM_ID" -F date="$START_DATE" -f query='
        mutation($itemId: ID!, $date: Date!) {
          updateProjectV2ItemFieldValue(input: {
            projectId: "PVT_kwDOARZVGM4BVVRV"
            itemId: $itemId
            fieldId: "PVTF_lADOARZVGM4BVVRVzhQxyGA"
            value: { date: $date }
          }) { projectV2Item { id } }
        }'
    fi
    ```
11. PR URL 을 재출력한다.

### PR 제목 규칙
- 70자 이내
- 타입 prefix를 붙이지 않는다 (커밋과 다름)
- 예: `예외 처리 시스템 공통화`, `PR 생성 스킬 추가`
- update 모드에서는 기본적으로 제목 유지. 작업 의도/스코프 변화나 정정이 필요한 경우만 변경하고, 사용자에게 변경 이유를 짚어 확인받는다.

$ARGUMENTS
