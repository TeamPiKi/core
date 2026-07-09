현재 브랜치 PR 의 CodeRabbit 리뷰를 처리한다 — 인라인 review thread 와 review body 의 nitpick 을 모두 조회해 평가하고, accept/reject 를 reply·resolve 로 남긴다.

## 언제 쓰나

- PR 에 CodeRabbit 리뷰가 달린 뒤, 그 지적을 반영·답변할 때.
- `/pr` 로 PR 을 올린 뒤 리뷰 대응 단계에서 이어서 호출하거나, 단독으로 호출한다.

## 원칙

- **commit + push 로 끝내지 않는다.** 각 review thread 에 reply 를 남겨 **어떤 commit 으로 반영했는지 / reject 한 이유가 무엇인지** 가 conversation 에 박혀야 다른 리뷰어가 처리 여부를 헷갈리지 않는다.
- **CodeRabbit 은 코멘트를 두 곳에 나눠 단다 — 반드시 둘 다 본다.**
  - **인라인 review thread**: actionable 코멘트. `reviewThreads` 로 조회되고 개별 resolve 가능.
  - **review body 안에 접힌 nitpick / 추가 코멘트**: `🧹 Nitpick comments (N)` 같은 `<details>` 블록. `reviewThreads` 에 **안 잡힌다** — `pulls/{PR}/reviews` 의 review body 를 따로 조회해야 보인다. 이걸 빠뜨리면 nitpick 을 통째로 놓친다 (resolve 대상은 아니지만 평가는 해야 한다).
- **author 매칭은 `coderabbitai` 로 시작하는지(`startswith`)로 한다.** GitHub 의 두 API 가 같은 봇을 다르게 표기한다 — GraphQL `reviewThreads` 의 `author.login` 은 `coderabbitai`, REST `pulls/{PR}/reviews` 의 `user.login` 은 `coderabbitai[bot]`. 어느 한쪽 값으로 고정하면 다른 API 에서 매칭이 깨져 봇을 사람으로 오인하므로(예: reviewThreads 를 `coderabbitai[bot]` 로 비교하면 영원히 안 잡힘), 양쪽 모두 `coderabbitai` 로 시작하는지로 매칭한다.
- **이 스킬은 CodeRabbit thread/nitpick 만 다룬다 — 사람 리뷰는 조회·카운트·보고 어느 것도 하지 않는다.** 아래 모든 조회는 author 가 `coderabbitai` 로 시작하는 코멘트로 한정한다(각 절차의 `startswith("coderabbitai")` 필터). 사람 리뷰 thread 는 작성자(`@me`)가 직접 의도·뉘앙스를 담아 답할 영역이므로, 스킬이 그 존재를 들여다보거나 reply / resolve 하지 않는다.

## 절차

### 0. PR 번호 확인

```bash
PR=$(gh pr view --json number --jq '.number')
echo "PR #${PR}"
```

PR 이 없으면 (`gh pr view` 실패) 먼저 `/pr` 로 PR 을 만들라고 안내하고 중단한다.

### 1. CodeRabbit 인라인 thread 조회 — author `coderabbitai*` 필터

```bash
PR=$(gh pr view --json number --jq '.number')   # 블록마다 재도출 — 셸 변수는 bash 호출 간 유지되지 않는다 (0단계 값에 기대지 않기)
# --paginate 가 커서 페이지네이션을 자동 처리한다 (gh 규약: 변수명 $endCursor + pageInfo{hasNextPage endCursor} 선택).
# 수동 재호출 루프를 두지 않는 이유: hasNext 신호를 실행자가 놓치면 100개 초과 thread 가 조용히 누락된다 — --paginate 는 호출 1회로 결정론적으로 전부 모은다.
gh api graphql --paginate -f query='
  query($endCursor: String) { repository(owner: "TeamPiKi", name: "core") {
    pullRequest(number: '"$PR"') {
      reviewThreads(first: 100, after: $endCursor) {
        pageInfo { hasNextPage endCursor }
        nodes {
          id isResolved path line
          comments(first: 1) { nodes { author { login } body } }
        }
      }
    }
  }}' --jq '.data.repository.pullRequest.reviewThreads.nodes[]
            | select((.comments.nodes[0].author.login // "") | startswith("coderabbitai"))
            | {id, isResolved, path, line}'
```

### 1.5. review body 의 nitpick·추가 코멘트 조회 — reviewThreads 에 안 잡히므로 필수

```bash
PR=$(gh pr view --json number --jq '.number')   # 블록마다 재도출 (셸 변수 미유지 — 비면 pulls//reviews 404 가 된다)
# --paginate 필수 — 기본은 30건/페이지라, CodeRabbit 이 푸시마다 리뷰를 새로 다는 특성상
# 리뷰 왕복이 길어지면 첫 30개 이후의 review body(nitpick 포함)가 조용히 누락된다.
# 길이 필터를 두지 않는다 — 짧은 body 도 유효한 코멘트일 수 있어 비어있지 않으면 전부 수집하고,
# nitpick 여부는 길이가 아니라 본문 마커(`🧹 Nitpick comments` 등)로 가른다.
gh api --paginate repos/TeamPiKi/core/pulls/$PR/reviews \
  --jq '.[] | select(((.user.login // "") | startswith("coderabbitai")) and ((.body // "") | length > 0)) | .body'
```

출력된 body 를 읽고 `🧹 Nitpick comments` 등 접힌 코멘트를 건별 평가한다. nitpick 은 thread 가 아니라 **resolve 대상이 아니므로**, 반영하면 커밋 + PR `## Updates`(또는 PR 일반 코멘트)로 처리 사실을 남긴다.

### 2. 각 CodeRabbit thread 에 reply

```bash
gh api graphql -f query='
  mutation($t: ID!, $b: String!) {
    addPullRequestReviewThreadReply(input: {pullRequestReviewThreadId: $t, body: $b}) {
      comment { url }
    }
  }' -F t="<thread id>" -f b="<reply 내용>"
```

### 3. 자동 resolve 안 된 thread 면 resolve

CodeRabbit 은 자동 resolve 하는 경우가 있어 `isResolved` 확인 후 분기한다.

```bash
gh api graphql -f query='
  mutation($t: ID!) {
    resolveReviewThread(input: {threadId: $t}) { thread { isResolved } }
  }' -F t="<thread id>"
```

### accept / reject 규칙

- **accept**: reply 에 fix commit hash 명기 (예: "Accepted. Fixed in `371b5ba`."). 자동 resolve 안 됐으면 resolve 까지.
- **reject**: reply 에 reject 이유 (예: "CLAUDE.md '테스트 셋업 원칙' 과 충돌 — 셋업 hook 으로 stub 상태 리셋 금지"). **resolve 하지 않는다** — 사용자가 검토할 기회 보존.
- 한 thread 가 여러 commit 으로 해소됐으면 해시 모두 명기.

## 주의

- owner/repo 는 `TeamPiKi/core` 고정. 레포가 바뀌면 갱신한다.
- nitpick 반영 여부·범위는 선택적이다. actionable thread 와 달리 강제가 아니므로, 반영할지 사용자와 합의하고 진행한다.
