세션 작업을 마무리한다 — 지금 들어가 있는 작업 워크트리를 (**머지+clean 을 자체 점검해** 그럴 때만) 나가면서 제거하고, 머지로 닫혔어야 할 연결 이슈가 아직 열려 있으면 동의받아 닫은 뒤, 사용자에게 `/clear` 입력을 안내한다. **자체 점검으로 단독 동작**하므로 `/session-check` 를 먼저 안 거쳐도 되고, `/session-check` 는 변경 없이 미리 보는 read-only 프리뷰다. `/session-check` 의 짝(teardown) 스킬.

## 언제 쓰나

- 지금 작업하던 워크트리를 정리하고 세션을 끝내려 할 때. `/session-check` 를 먼저 부르지 않아도 close 가 자체 점검한다 (check 는 변경 없이 미리 보고 싶을 때 쓰는 선택적 read-only 프리뷰).
- **항상 사용자가 직접 호출한다.** `/session-check` 는 점검만 하고 이 스킬을 자동 호출하지 않는다 — 닫아도 안전하면 사용자에게 `/session-close` 입력을 안내할 뿐이다. close(워크트리 제거)는 이 스킬을 명시적으로 호출할 때만 일어난다.

## 전제 — 작업 중엔 워크트리에 "들어가 있다"

이 워크플로우에서 claude 는 메인에서 시작하지만, 작업 중엔 `EnterWorktree` 로 생성·진입한 `.claude/worktrees/<task>` **안에 들어가 있다**(세션 cwd 가 그 워크트리). 그래서 마무리 = "지금 들어가 있는 그 워크트리를 나가면서 지우기"다. (`/issue` 의 `gh issue develop --checkout` 은 브랜치만 만들어 메인에서 체크아웃하는 별개 동작 — 워크트리를 만들지 않는다.)

## 원칙

- **머지+clean 일 때만 제거.** uncommitted 가 있거나 브랜치가 아직 머지 안 됐으면 **거부**한다(작업 유실 방지). **session-check 없이도 단독으로 안전한지 자체 점검**한다 — 아래 절차 2 의 clean·머지 판정이 그 점검이다.
- **제거는 `ExitWorktree` 로 한다.** `ExitWorktree({action:"remove"})` 가 워크트리 나가기 + 디렉터리/브랜치 삭제 + cwd 복원(메인으로)을 한 번에 처리한다 — "자기가 선 폴더를 자기가 못 지운다"는 문제를 도구가 해결한다. 수동 `git -C ... worktree remove` 보다 안전·정석.
- **현재 작업 1개만.** 다른 워크트리·머지된 다른 stale 브랜치는 안 건드린다(별도 세션 몫).
- **임시파일도 함께 정리한다.** 워크트리를 제거할 때, 이 브랜치가 `/tmp` 에 남긴 `/pr` 임시파일(`pr_body_$SLUG.md`)도 지운다. **현재 브랜치 것만** — 동시에 도는 다른 세션의 파일은 안 건드린다("현재 1개만"과 같은 결). `session-close` 를 안 거치고 떠난 세션·중단 작업의 누수는 다음 `/pr` 진입의 mtime prune 이 회수한다.
- **머지로 닫혔어야 할 연결 이슈가 열려 있으면 닫는다.** 이 레포는 `/pr` 이 PR 본문에 `close #N` 을 박아 머지 시 GitHub 가 연결 이슈를 자동으로 닫는다. 그런데 브랜치명에서 이슈 번호 추출이 실패해 키워드가 안 박혔거나(주된 사각), 머지 후 누가 이슈를 다시 열었거나 하면 머지됐는데도 이슈가 open 으로 남는다. 워크트리를 제거하기 전(아직 `$BR` 이 유효한 시점)에 이 누락을 점검해 **사용자 동의 후** 닫는다. 후속 작업 때문에 일부러 열어둔 이슈를 자동으로 닫지 않도록 닫기 전 확인을 거친다(머지+clean 이 확인된 뒤에만 점검한다 — 미머지 브랜치의 이슈는 건드리지 않는다). 단 브랜치명에 번호가 없고 PR 본문에도 `close` 키워드가 없으면 어느 이슈와 엮였는지 추적할 단서가 없어 이 점검도 못 잡는다 — 연결 정보 자체가 없는 한계다.
- **`/clear` 는 자동 호출 불가.** 스킬은 빌트인 슬래시 커맨드를 못 부른다(claude-code-guide 확인). 정리 후 사용자에게 `/clear` 입력을 안내하는 것으로 끝낸다.
- **이모지·체크기호 금지.** 굵게·불릿으로만 표시한다.

## 절차

### 1. 지금 워크트리에 들어가 있는지 판별

```bash
CUR=$(git rev-parse --show-toplevel)
MAIN=$(git worktree list --porcelain | awk '/^worktree /{print $2; exit}')
BR=$(git rev-parse --abbrev-ref HEAD)
```

- `CUR == MAIN` → 작업 워크트리에 안 들어가 있음(메인). **제거할 게 없다** → `### 3` 안내로 바로 간다.
- `CUR != MAIN` → 지금 워크트리 안. `### 2` 로.

### 2. 안전 재확인 후 제거 (CUR != MAIN)

```bash
git status --porcelain          # 비어야 함 (clean)
# 머지 판정: head 가 정확히 $BR 인 merged PR 이 있어야 "진짜 머지"로 본다.
#  - [gone](upstream:track)은 '원격 ref 미존재'일 뿐 머지 보장이 아니다 (버려서 삭제된 브랜치도 [gone]) → 머지 근거로 쓰지 않는다.
#  - --search "head:..." 는 fuzzy 라 fork 동명 브랜치까지 매치된다 → --head 정확 일치 + headRefName 재필터.
#  - merge-base --is-ancestor 도 안 쓴다: 이 레포는 squash-merge라 머지돼도 ancestor 가 아니라 미머지로 오판한다.
gh pr list --head "$BR" --state merged --json number,headRefName \
  --jq '[.[] | select(.headRefName == "'"$BR"'")] | length'   # >0 이면 이 브랜치 head 의 merged PR 존재
```

- **dirty** (status 비어있지 않음) → **거부.** "uncommitted N건 — 먼저 `/commit` 하거나 `/session-check`." 중단.
- **미머지** (위 merged PR 카운트가 0) → **거부.** "브랜치 `$BR` 미머지 — PR 머지 후 다시." 중단. (열린 PR 만 있는 경우도 여기 해당 — 머지 전이므로 삭제 금지.)
- 둘 다 통과(clean + 머지됨) → **제거한다**:
  1. **머지로 닫혔어야 할 연결 이슈가 아직 열려 있는지 점검한다.** ExitWorktree 가 cwd·브랜치를 날리기 전, 아직 워크트리 안이라 `$BR` 이 유효한 이 시점에 한다. 머지된 PR 이 공식적으로 닫기로 한 이슈(`closingIssuesReferences`)와 브랜치명에서 추출한 이슈 번호(`/pr` 과 동일 규칙 `^[a-z]+/(\d+)-`)를 합쳐, 그중 아직 OPEN 인 것을 찾는다. 별도 bash 호출이라 `$BR`·PR 번호를 다시 구한다:
     ```bash
     BR=$(git rev-parse --abbrev-ref HEAD)
     PR=$(gh pr list --head "$BR" --state merged --json number,headRefName \
       --jq '[.[] | select(.headRefName == "'"$BR"'")][0].number // empty')   # 머지 확인 통과했으므로 비지 않음
     { gh pr view "$PR" --json closingIssuesReferences --jq '.closingIssuesReferences[].number';
       echo "$BR" | sed -nE 's#^[a-z]+/([0-9]+)-.*#\1#p'; } | sort -un | while read -r N; do
         gh issue view "$N" --json number,state --jq 'select(.state=="OPEN") | .number'
     done   # 출력된 번호 = 머지됐는데 안 닫힌 이슈
     ```
     - **출력이 비어 있으면** (OPEN 이슈 없음) 바로 2번으로. 정상이다 — 자동닫힘이 동작했거나 연결 이슈가 없다.
     - **OPEN 이슈가 있으면** `AskUserQuestion` 으로 어떤 걸 닫을지 받는다(`multiSelect`, 기본 추천은 전부 닫기). 일부러 열어둔 이슈를 자동으로 닫지 않기 위함이다. 고른 것만 닫는다:
       ```bash
       gh issue close "$N" --reason completed --comment "PR #$PR 머지로 닫음 (자동닫힘 누락 보정)"
       ```
  2. 이 브랜치가 `/tmp` 에 남긴 임시파일을 정리한다 (작업이 끝났으므로). **현재 브랜치 슬러그 것만** 지워 동시에 도는 다른 세션 파일은 건드리지 않는다. 별도 bash 호출이라 슬러그를 다시 구한다 (`$BR` 은 유지되지 않음):
     ```bash
     SLUG=$(git branch --show-current | tr '/' '_')
     rm -f /tmp/pr_body_"$SLUG".md /tmp/nb_*_"$SLUG".json 2>/dev/null
     ```
  3. `ExitWorktree({action: "remove"})` 를 호출한다.
  4. 거부하면서 변경 목록을 돌려주면 — clean 은 이미 확인했으니 그 목록은 **squash-merge 커밋(원래 브랜치 dev 의 ancestor 가 아닌 것)** 인 false alarm 이다. 이때만 `ExitWorktree({action: "remove", discard_changes: true})` 로 재호출한다. (clean·머지를 확인하기 **전에는 절대** `discard_changes: true` 를 주지 않는다.)
  5. ExitWorktree 가 **no-op** 이라고 하면(이번 세션의 `EnterWorktree` 로 들어간 워크트리가 아님 — 이전 세션·수동 생성 등), **fallback** 으로 메인에서 ref 연산한다. 이건 이 스킬의 **마지막 bash 호출**이어야 한다(`$CUR` 삭제 시 cwd 가 사라짐 — 세 명령 모두 `-C "$MAIN"` 이라 cwd 비의존):
     ```bash
     git -C "$MAIN" worktree remove "$CUR" && git -C "$MAIN" branch -D "$BR" && git -C "$MAIN" worktree prune
     ```

### 3. /clear 안내

정리 결과(나간/지운 워크트리·브랜치, 또는 "제거할 워크트리 없음")를 한 줄로 보고한 뒤, 사용자에게 **명확히** 안내하고 끝낸다:

> "컨텍스트를 비우려면 이제 **`/clear`** 를 입력하세요."

`/clear` 를 스킬이 직접 호출하려 시도하지 않는다(불가능하다).

owner/repo 는 하드코딩하지 않는다 — 위 모든 `gh` 명령이 현재 워크트리의 origin 에서 레포를 자동 도출하므로, 어느 소비 repo(core·extractor·renderer)에서 호출해도 자기 레포를 가리킨다.

$ARGUMENTS
