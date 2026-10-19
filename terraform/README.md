# Terraform 운영 가이드

본 디렉터리의 인프라 코드를 만지기 전에 한 번 읽어주세요.

## State 의 역할

```
코드 (.tf 파일)  =  의도        (what should exist)
AWS              =  실제 리소스  (what actually exists)
state            =  매핑         (terraform 이 자기가 만든 리소스 추적)
```

state 가 없으면 terraform 은 AWS 에 떠있는 리소스를 "내가 모르는 것" 으로 취급해 다음 apply 때 새로 만들려 든다. 즉 **state = terraform 의 기억**이며, 잃으면 안 된다.

## 우리가 채택한 방식 — S3 remote backend + native lock

| 결정 | 값 | 이유 |
|---|---|---|
| state 위치 | `s3://piki-tfstate-<ACCOUNT_ID>/terraform.tfstate` | 팀 공유 + 단일 진실 원천 |
| 락 메커니즘 | S3 native (`use_lockfile = true`) | Terraform 1.10+ 의 S3 conditional PUT 락. DynamoDB 추가 운영 부담 0 |
| 버킷 보안 | versioning ON / 퍼블릭 차단 / SSE-S3 | 롤백 가능, 외부 노출 차단 |
| 접근 권한 | 관리자 역할 `piki-admin` (아래 권한 모델) | 조회 그룹은 S3 객체를 못 읽어 state 를 못 봄 |

이 문서와 코드의 `<ACCOUNT_ID>` 는 AWS 계정번호 자리다. public repo 라 계정번호를 커밋하지 않는 규율(infra `conventions/infra.md`)에 따라, 실값은 자격증명에서 파생해(`aws sts get-caller-identity`) gitignore 된 `backend.hcl` 과 환경변수로 주입한다 (아래 워크플로우) — 계정번호를 어디에 따로 적어 둘 필요가 없다.

대안과 비교:

| 방식 | 채택 안 한 이유 |
|---|---|
| Local state | 팀 협업 불가 |
| S3 + DynamoDB lock | DynamoDB 추가 운영 부담. native lock 으로 같은 효과 가능 |
| Terraform Cloud | 개인 프로젝트엔 오버 |
| Atlantis / Spacelift | 자체 호스팅 운영 부담 |

## 권한 모델

사람 권한은 `iam_people.tf` 가 정본이다. 그룹 소속만 CLI 로 넣는다(사람 이메일을 커밋하지 않는다).

| 주체 | 가능한 일 |
|---|---|
| `PiKi-Viewer` 그룹 | 콘솔 조회(ViewOnlyAccess). S3 객체·SSM 시크릿은 못 읽는다 |
| `PiKi-Backend` 그룹 | 조회 + SSM 서버 접속·원격 명령 + `/piki*` 시크릿 읽기 + 비용 조회 + `aws login` |
| `piki-admin` 역할 | 모든 변경. 백엔드 그룹만, Touch ID(MFA) 로 로그인한 지 20분 안에만 전환된다 |

→ **`terraform plan`·`apply` 는 둘 다 `piki-admin` 으로 돈다.** plan 도 state 버킷에 락을 쓰고, 이 스택이 IAM 을 만들어 apply 권한이 곧 관리자 권한이다. 자동화 도구(Claude 등)는 사람이 Touch ID 를 누르지 않으면 관리자가 될 수 없다.

## 변수 값 / secret 어디서 받나

`*.tfvars` 파일은 **git 에 커밋되지 않으며 S3 등 어디에도 업로드되지 않는다.** 본인 로컬 머신에서만 필요하면 만들어 쓰는 임시 파일. 공유 자산이 아님.

### 외부 주입이 필요한 변수

| 변수 | 종류 | 비고 |
|---|---|---|
| `image_bucket_name` | 환경 설정 (필수) | default 없음 — 계정번호 포함이라 커밋 금지. 자격증명에서 파생 ("일상 워크플로우"의 도출 명령이 자동 세팅) |
| `db_backup_bucket_name` | 환경 설정 (필수) | 위와 같음 |

그 외 변수 (`project`, `environment`, `aws_region`, VPC CIDR, EC2 instance type 등) 는 `variables.tf` 의 default 그대로 쓴다.

**RDS 마스터 비밀번호는 변수가 아니다.** `rds.tf` 의 `data.aws_ssm_parameter` 가 SSM `/piki-core/prod/db-password` 를 plan/apply 시점에 직접 읽는다 (앱 시크릿 SSM 단일화의 후속 — 사람이 값을 옮겨 적는 채널이 없다). 실행 자격증명에 `ssm:GetParameter` 권한만 있으면 된다.

### 주입 방식 (권장 순)

**(권장) 환경변수 — 세션 한정**

```bash
export TF_VAR_image_bucket_name="piki-images-$(aws sts get-caller-identity --query Account --output text)"   # 계정번호 파생 — 공지 불필요
```

세션 종료 시 자동 소멸. 디스크에 평문 안 남음.

**(비권장) `terraform.tfvars` 파일**

만들면 동작은 하는데 평문 비번이 로컬 디스크에 남는 게 단점. gitignore 가 막아주긴 하지만 실수 한 번이면 노출. 굳이 안 만드는 게 안전.

만들 거면:
```bash
cp terraform.tfvars.example terraform.tfvars
# 값 채워 넣기 — 절대 커밋 X
```

**(비권장) 인터랙티브 프롬프트**

값 안 주면 terraform 이 매번 묻는다. 매번 같은 값 정확히 타이핑해야 하고 (한 글자 다르면 RDS password 가 의도치 않게 변경됨), 자동화 안 됨. 임시로만.

## 일상 워크플로우

### 0. (최초 1 회) 프로필 준비

액세스 키는 만들지 않는다. 노트북 준비(`aws login` 프로필 `piki`)는 infra `conventions/infra.md` 5절. 여기에 관리자 역할 프로필 둘을 더한다.

```ini
[profile piki-admin]
region = ap-northeast-2
role_arn = arn:aws:iam::<ACCOUNT_ID>:role/piki-admin
source_profile = piki

# terraform 은 aws login 자격을 직접 못 읽어 CLI 를 거친다(로컬 terraform 1.14·AWS 프로바이더 5.x)
[profile piki-tf]
region = ap-northeast-2
credential_process = aws configure export-credentials --profile piki-admin --format process
```

### 1. 작업 시작

```bash
aws login --profile piki     # 브라우저에서 새로 로그인 + Touch ID. 20분 안에 아래 plan 까지 시작한다
export AWS_PROFILE=piki-tf
cd terraform
# 버킷명은 전부 계정번호 파생(piki-*-<계정번호>)이라 자격증명에서 도출한다 — 값을 외우거나 공지에서 찾을 필요 없음.
# 파생값이라 재실행해도 항상 현재 자격증명 계정 기준 같은 값이 나와 덮어써도 안전하다.
ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
echo "bucket = \"piki-tfstate-$ACCOUNT\"" > backend.hcl   # gitignore 됨 — 커밋 금지
export TF_VAR_image_bucket_name="piki-images-$ACCOUNT"
export TF_VAR_db_backup_bucket_name="piki-db-backup-$ACCOUNT"

terraform init -backend-config=backend.hcl     # 첫 실행 시 또는 backend 설정 변경 시. provider 다운 + S3 backend 연결
terraform plan   # state 읽고 AWS 와 비교, 변경 미리 보기
```

역할 세션은 1시간이다. 그 뒤엔 `aws login` 부터 다시 한다.

### 2. 적용 (admin 만)

```bash
terraform apply  # 락 잠그고 실제 AWS 변경 + state 업데이트
```

### 3. 작업 종료

```bash
unset AWS_PROFILE
```

## 마이그레이션 / 버킷 설정 검증

처음 backend 설정한 직후 또는 신규 합류자가 환경 점검할 때 돌리는 sanity check 모음.

### state 객체가 S3 에 실제로 존재하는지

```bash
# 객체 단건 확인
aws s3api head-object \
  --bucket piki-tfstate-<ACCOUNT_ID> \
  --key terraform.tfstate

# 또는 버킷의 객체 목록
aws s3 ls s3://piki-tfstate-<ACCOUNT_ID>/
```

`head-object` 가 200 응답 + `ContentLength` 가 0 이 아닌 값이면 정상.

### 버킷 versioning 이 켜져 있는지 (반드시 Enabled)

```bash
aws s3api get-bucket-versioning --bucket piki-tfstate-<ACCOUNT_ID>
```
기대 출력:
```json
{ "Status": "Enabled" }
```

`MFA Delete` 는 우리 환경엔 미사용. `Status` 가 `Enabled` 만 확인되면 충분.

### 객체의 버전 히스토리 확인

```bash
aws s3api list-object-versions \
  --bucket piki-tfstate-<ACCOUNT_ID> \
  --prefix terraform.tfstate
```

apply 횟수만큼 버전이 쌓여야 한다. 신규 부트스트랩 직후엔 1 개.

### 퍼블릭 차단 / 암호화 확인

```bash
aws s3api get-public-access-block --bucket piki-tfstate-<ACCOUNT_ID>
# 4 개 항목 다 true
aws s3api get-bucket-encryption --bucket piki-tfstate-<ACCOUNT_ID>
# AES256 (SSE-S3) 또는 aws:kms
```

### 기능 검증 — terraform plan 무변경

```bash
terraform plan
# Expected: "No changes. Your infrastructure matches the configuration."
```

`No changes` 가 떠야 state 가 S3 에서 정상 로드되고 AWS 실 리소스와 일치한다는 뜻. 변경이 잡히면 drift — 별도 이슈로 분석.

## 동시성 / 사고 처리

### 락 (자동)

두 사람이 동시에 apply 시도하면, S3 의 `.tflock` conditional PUT 으로 두 번째 apply 가 즉시 실패. 슬랙으로 "나 apply 한다" 외치지 않아도 자동 직렬화.

### 락 풀기 (드물게)

terraform 이 비정상 종료되어 락이 안 풀린 채 남으면:

```bash
terraform force-unlock <LOCK-ID>
```

상황 파악한 뒤에만 사용. 진짜 누가 작업 중인 거면 절대 풀지 말 것.

### 롤백

state 가 망가지거나 잘못된 apply 가 일어났을 때 — S3 versioning 으로 복구:

```bash
aws s3api list-object-versions --bucket piki-tfstate-<ACCOUNT_ID> --prefix terraform.tfstate
aws s3api get-object --bucket piki-tfstate-<ACCOUNT_ID> --key terraform.tfstate \
  --version-id <VERSION_ID> terraform.tfstate.recovery
terraform state push terraform.tfstate.recovery
```

S3 기본 30 일+ 보존이라 시간 거꾸로 돌릴 수 있음.

## 왜 state 만 gitignore 인가

| 파일 | git? | 이유 |
|---|---|---|
| `*.tf` | ✅ | 코드 — 팀 공유 |
| `.terraform.lock.hcl` | ✅ | provider 버전 락. 팀 일관성 |
| `terraform.tfstate` | ❌ → S3 | 비밀값 포함 + apply 마다 변경 + 진실 원천 1 개여야 |
| `.terraform/` | ❌ | provider 플러그인 (~150MB, OS/arch별). `init` 으로 재생성 |
| `*.tfvars` | ❌ | secret. 전용 저장소 (env var / Secrets Manager) |
| `backend.hcl` | ❌ | 계정번호 포함 backend 설정. 자격증명에서 파생 생성 ("일상 워크플로우" 참고) |
| `*.tfplan` | ❌ | 일회성 |

→ ignore 된 것 중 **state 만이 "공유는 필요하지만 git 에 두면 안 되는" 유일한 파일**. 그래서 별도 저장소 (S3) 가 필요.

## 절대 하지 말 것

- ❌ S3 객체 직접 편집 / 삭제 (콘솔, CLI 다)
- ❌ 로컬 `terraform.tfstate` 파일을 커밋
- ❌ 본인 `~/.aws/credentials` 에 액세스 키 영구 저장
- ❌ 검토 없이 `terraform apply` (반드시 plan 먼저)
- ❌ drift 발견 시 무작정 apply (별도 이슈로 검토)
