세션을 종료해도 되는지 **read-only로 점검**한다 — 기본은 현재 워크트리/브랜치만 훑고, 인자 `all` 을 주면 전체 워크트리·브랜치·PR을 함께 스윕한다. **점검 단계는 어떤 변경도 하지 않는다.** 커밋·푸시·브랜치 삭제·prune 은 직접 실행하지 않고, 무엇을 어떤 도구로 정리할지 **안내만** 한다 (실제 정리는 `/commit`·`git push` 등으로 사용자가 한다). **예외는 마무리(close) 한 곳뿐이다.** 점검이 모두 끝나 '닫아도 안전'으로 판정되고 현재 워크트리가 제거 대상 sub-worktree면, 맨 마지막에 `AskUserQuestion` 으로 `/session-close` 호출 여부를 묻고, 사용자가 '예'를 고를 때만 close 를 실행한다 (아래 `## 절차` 6).

## 모드

인자에 `all` 이 포함됐는지로 스코프를 정한다.

- **기본 (`/session-check`)** — 현재 워크트리 + 현재 브랜치/PR만 본다. "지금 이 작업"을 닫아도 되는지 확인하는 좁은 점검. session-close 와 스코프가 일치한다.
- **전역 (`/session-check all`)** — 위에 더해 전체 워크트리 dirty·prunable, 모든 로컬 브랜치 미푸시/미삭제, 본인 열린 PR 전부를 스윕한다. 여러 워크트리를 동시에 굴린 뒤 흘린 게 없는지 보는 위생 점검.

## 언제 쓰나

- (기본) 지금 작업을 마무리하고 세션을 닫기 전, 이 워크트리/브랜치에 흘린 변경·미푸시·미삭제가 없는지 확인할 때.
- (`all`) 여러 워크트리를 동시에 굴린 뒤 어디에 dirty/미푸시/미삭제가 남았는지 한눈에 보고 싶을 때.

## 원칙

- **점검은 read-only — 읽기·안내만, 변경 없음.** session-check 의 점검 단계(A·B·C·D 수집 + 리포트)는 상태를 **읽기만** 한다. 커밋·푸시·브랜치 삭제·prune 등 **점검 중 어떤 변경도 직접 하지 않는다.** 정리가 필요하면 "무엇을 어떤 도구(`/commit`·`git push`·`git branch -D`)로 정리하라"고 **안내만** 한다. **단 하나의 예외는 마무리(close)다.** 점검이 모두 끝나 '닫아도 안전'으로 판정되고 현재 워크트리가 제거 대상 sub-worktree일 때만, 맨 마지막에 `/session-close` 호출 여부를 `AskUserQuestion` 으로 묻고 사용자가 '예'를 고르면 그때 close 를 실행한다 (아래 `## 절차` 6). 점검 도중에는 묻지도 실행하지도 않는다.
- **`AskUserQuestion` 은 마지막 close 확인 한 곳에서만 띄운다.** 점검 항목(uncommitted·미푸시·미삭제 등)의 정리는 전부 read-only 안내로 끝내고 "무엇을 처리할지" 사용자에게 고르게 하지 않는다. `AskUserQuestion` 은 오직 모든 점검이 끝나 '닫아도 안전'이고 제거 대상 워크트리일 때 `/session-close` 호출 여부를 묻는 **단 한 번**만 사용한다.
- **정리 안내 vs 정보성.** 처리 방법이 있는 항목(uncommitted, 미푸시, 미삭제 브랜치, prunable 워크트리)은 "무엇을 어떤 도구로 정리할지" 안내한다. 그 외(CI 실패, 리뷰 대기, 새 TODO, 다른 워크트리 dirty)는 정보성 리포트. 어느 쪽이든 **실행하지 않고 보여주기만** 한다.
- **체크아웃 전환 금지.** 브랜치를 갈아타지 않는다 — 다른 워크트리/브랜치는 `git -C <path>` 로 **읽기만** 한다(status·for-each-ref 등). 메인 체크아웃이 작업 중 외부에서 바뀌는 환경이므로 격리한다.
- **파괴적 동작은 더더욱 안 함.** read-only 라 변경 자체를 안 하지만, 특히 untracked 파일 삭제·`git checkout -- .`·stash drop·강제 푸시처럼 되돌리기 어려운 동작은 안내에서도 권하지 않는다. 커밋은 `/commit`(컨벤션 보존)으로 안내한다.
- **이모지·체크기호 금지.** 굵게·불릿으로만 상태를 표시한다.

## 점검 항목

먼저 현재 위치를 잡는다 (모드 공통).

```bash
BR=$(git rev-parse --abbrev-ref HEAD)
CUR=$(git rev-parse --show-toplevel)
```

### A. 현재 워크트리 Git 상태 (기본·전역 공통)

```bash
git status --porcelain        # staged / unstaged / untracked
git stash list                # 남은 stash
```

- uncommitted(staged + unstaged) → **정리 안내**: 커밋하려면 `/commit`, 또는 stash / 그대로 둠. 여기선 실행하지 않는다.
- untracked → 그중 `.md`·스크래치 파일은 작업 부산물일 수 있으니 따로 표시한다(삭제는 안 함, 커밋 여부는 `/commit` 판단에 위임).
- stash 잔여 → **정보성** 리포트만 (apply/drop은 위험하므로 자동 처리하지 않는다).

### B. 현재 브랜치 / PR (기본·전역 공통)

기본 모드에선 **현재 브랜치 하나만** 본다.

```bash
git for-each-ref --format='%(refname:short) | %(upstream:short) | %(upstream:track)' refs/heads/"$BR"   # 현재 브랜치의 ahead / no-upstream / [gone]
gh pr list --author "@me" --state open --head "$BR" --json number,title,headRefName,reviewDecision,statusCheckRollup
```

- `%(upstream:track)` 가 `[gone]` → 현재 브랜치의 upstream(원격 추적 브랜치)이 삭제됨 = **머지된 신호**. 단 현재 브랜치는 정의상 현재 워크트리에 체크아웃돼 있어 `git branch -D` 로 삭제 불가다. → **액션 아님, 안내**: "이 브랜치는 머지됨 → 워크트리째 정리하려면 `/session-close`." (session-close 가 워크트리 제거 + 브랜치 삭제를 함께 처리한다.)
- upstream 이 없음(=한 번도 push 안 됨)이거나 ahead → **정리 안내**: `git push`(필요 시 `-u`) 하면 됨(여기선 실행 안 함). `[gone]` 과 혼동하지 않는다 — `[gone]` 은 push 가 아니라 머지 신호다.
- 현재 브랜치 PR의 CI 실패(`statusCheckRollup` 에 FAILURE)·리뷰 대기(`reviewDecision` 가 REVIEW_REQUIRED / CHANGES_REQUESTED) → **정보성** 리포트.

### C. 전체 워크트리·브랜치 스윕 (`all` 모드 한정)

기본 모드에선 **건너뛴다.** 인자에 `all` 이 있을 때만 실행한다.

```bash
git worktree list --porcelain     # worktree 경로 / branch 파싱
git worktree prune --dry-run -v   # prunable(경로가 사라진) 워크트리
git for-each-ref --format='%(refname:short) | %(upstream:short) | %(upstream:track)' refs/heads   # 모든 브랜치
gh pr list --author "@me" --state open --json number,title,headRefName,reviewDecision,statusCheckRollup
```

파싱한 각 워크트리 경로에 대해:

```bash
git -C "<worktree_path>" status --porcelain
```

- 현재 세션 워크트리가 아닌 다른 워크트리에 dirty/untracked → **정보성** 리포트(거기서 정리하라고 안내). 커밋은 여기서 하지 않는다.
- prunable 워크트리 → **정리 안내**: `git worktree prune` 하면 됨(여기선 실행 안 함).
- 현재 브랜치가 아닌 다른 브랜치가 `[gone]`(머지됐는데 안 지움) → **정리 안내**: `git branch -D <branch>` 로 삭제 가능(여기선 실행 안 함).
  - **삭제는 `git branch -D` 를 권한다.** squash/rebase 머지된 브랜치는 커밋이 현재 HEAD 의 ancestor 가 아니라 `git branch -d`(안전 삭제)가 "not fully merged" 로 거부한다. `[gone]` 은 원격에서 이미 사라진 머지 완료 브랜치라 force 삭제(`-D`)가 안전하다. 확신이 필요하면 `gh pr list --state merged --json headRefName` 로 머지 여부를 교차 확인하라고 함께 안내한다.
  - 단, 그 브랜치가 어느 워크트리에 체크아웃돼 있으면(위 `git worktree list` 의 branch 칼럼으로 판별) `git branch -D` 가 거부된다 → "그 워크트리부터 `/session-close` 로 정리"라고 안내한다.
- 다른 브랜치의 upstream 없음/ahead → **정리 안내**: `git push`(필요 시 `-u`), 다른 워크트리에 물린 브랜치는 `git -C <path> push`.
- 다른 열린 본인 PR의 CI 실패·리뷰 대기 → **정보성** 리포트.

### D. 작업 흔적 (기본·전역 공통, 정보성)

```bash
# base 는 origin/dev (이 레포의 PR base). origin/main 이 아니다.
# .claude/** (커맨드 문서 등 툴링)는 제외 — 스킬 자기 문서의 "TODO" 단어가 오탐되므로.
git diff origin/dev...HEAD -- ':(exclude).claude/**' | grep -nE '^\+[^+].*(TODO|FIXME)'
```

- 이번 브랜치에서 새로 추가된 TODO/FIXME → 리포트만.
- **base 는 `origin/dev`** 다 — 이 레포 PR base 가 dev 이므로. `origin/main` 으로 비교하면 엉뚱한 diff 가 나온다.
- `.claude/**` 는 pathspec 으로 제외한다 — 이 스킬 문서처럼 산문에 "TODO"·"FIXME" 단어가 들어가면 코드 TODO 가 아닌데도 잡히는 오탐을 막기 위함.
- 현재 브랜치 PR에 미해결 CodeRabbit/사람 리뷰 thread가 있으면 건수만 알리고 `/coderabbit` 로 처리하라고 안내한다(여기서 처리하지 않는다).

## 정리 안내 (변경은 하지 않는다)

- 점검에서 **정리 안내** 로 표시된 항목은, 최종 리포트에 "무엇을 어떤 도구로 정리하면 되는지"를 한 줄씩 적어준다. 이들 정리 안내 항목에는 **`AskUserQuestion` 을 띄우지 않고, 어떤 변경도 실행하지 않는다** (close 확인 ask 는 별개다 — `## 절차` 6).
- 도구 매핑: 커밋은 `/commit`, 푸시는 `git push`, 미삭제 머지 브랜치는 `git branch -D`, prunable 워크트리는 `git worktree prune`, 워크트리 제거+브랜치 삭제+연결 이슈 닫기는 `/session-close` — 각 항목 옆에 해당 도구를 명시한다.
- 정리를 실제로 하는 건 사용자다. session-check 는 "여기까지가 현황, 정리는 이 도구로" 까지만 한다.

## 최종 리포트 형식

점검이 끝나면 다음 형식으로 요약한다(read-only 라 항상 점검 시점 그대로의 상태다). 이모지 없이 굵게·불릿으로. 전역(`all`) 모드일 때만 다른 워크트리/브랜치 줄을 포함한다. **정리 필요** 항목은 줄 끝에 어떤 도구로 정리할지 함께 적는다.

```
## 세션 마무리 점검 (<기본 | 전역(all)>)

브랜치: <현재 브랜치>

**정리 필요 (주의 <M>건)** — 각 줄 끝의 도구로 사용자가 직접 정리
- (현재) uncommitted <n>건 (staged <a> / unstaged <b> / untracked <c>) → /commit
- 미푸시: <branch> ahead <n> → git push
- 머지됨(현재 브랜치): <branch> (upstream [gone]) → /session-close
- CI 실패: PR #<num> (정보성)
- [all] (워크트리 <name>) dirty <n>건 (정보성 — 거기서 정리)
- [all] 미삭제(머지됨): 로컬 브랜치 <branch> (upstream [gone]) → git branch -D

**안전**
- stash 없음 / 새 TODO 없음
```

주의 0건이면 맨 위에 **"닫아도 안전합니다"** 한 줄을 명확히 띄운다.

## 절차

1. 인자에 `all` 이 포함됐는지로 모드를 정한다 (없으면 기본).
2. read-only로 상태를 수집한다 — 기본 모드는 A·B·D, 전역(`all`) 모드는 A·B·C·D.
3. 최종 리포트 형식으로 현재 상태를 먼저 보여준다.
4. 정리가 필요한 항목은 리포트에 "무엇을 어떤 도구로 정리할지" 함께 적는다. **이 정리 안내 항목들은 실행하거나 `AskUserQuestion` 을 띄우지 않는다** (close 확인 ask 는 아래 절차 6 에서 별도로 처리한다).
5. 한 줄 결론(닫아도 안전 / 남은 주의 N건)으로 마무리한다.
6. **close 는 `AskUserQuestion` 으로 묻고, '예'일 때만 호출한다.** 결론이 "닫아도 안전"이고, 현재 워크트리(`CUR`)가 메인 체크아웃이 아니면서 머지+clean 인 **제거 대상 sub-worktree** 일 때:
   - 최종 리포트를 보여준 **뒤**, `AskUserQuestion` 으로 "이 워크트리를 정리하고 마무리하려면 `/session-close` 를 호출할까요?"를 묻는다. 옵션은 **호출(첫 번째·추천) / 호출 안 함** 둘로 둔다.
   - 사용자가 **호출**을 고르면 그때 `/session-close` 를 `Skill` 도구로 호출한다. **호출 안 함**을 고르면 아무것도 실행하지 않고, 나중에 직접 `/session-close` 를 입력하면 된다고 한 줄 안내하고 끝낸다.
   - **사용자가 명시적으로 '호출'을 고르기 전에는 `/session-close` 를 절대 먼저 실행하지 않는다.** ask 없이 자동 호출하지 않는다.
   - 현재가 메인 체크아웃이거나 제거 대상 워크트리가 없으면(마무리할 게 없으면) 이 ask 를 생략한다.
   - 결론이 "닫아도 안전"이 아니면(주의 N건 남음) ask 하지 않는다 — 먼저 정리가 우선이다.

owner/repo 는 하드코딩하지 않는다 — 위 모든 `gh` 명령이 현재 워크트리의 origin 에서 레포를 자동 도출하므로, 어느 소비 repo(core·extractor·renderer)에서 호출해도 자기 레포를 가리킨다.

$ARGUMENTS
