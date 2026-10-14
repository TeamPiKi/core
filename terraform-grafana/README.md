# Grafana 구성 (Terraform)

**Grafana Cloud(piki.grafana.net) 의 알림·대시보드 구성 정본은 이 디렉터리다** (#1008, #1011).
알림 룰 그룹 3개 · contact point 3개 · 루트 알림 정책 · 알림 폴더 · 대시보드 5개를 관리한다.
UI 에서 고칠 수는 있지만(잠금 없음) 다음 `apply` 가 코드 형상으로 되돌린다 —
UI 수정은 실험, 확정은 PR.

## 대시보드

정본은 `dashboards/*.json`, 반영은 `dashboards.tf` (#1011). 구조는 두 층이다:

| 파일 | uid | 무엇 |
|---|---|---|
| `glance.json` | `piki-glance` | 한눈 — 전 서비스·전 박스의 살았나/아프나/백업만 빠르게 |
| `core.json` | `piki-core` | 백엔드 API 상세 (환경 드롭다운 dev/prod) |
| `extractor.json` | `piki-extractor` | 추출 서비스 상세 (박스 1대, 환경 구분 없음) |
| `renderer.json` | `piki-renderer` | 렌더링 서비스 상세 (박스 1대, 환경 구분 없음) |
| `db.json` | `piki-db` | prod MySQL 박스 상세 (백업 관측 포함) |

서비스 4개는 같은 row 골격을 공유한다: **한눈 상태(stat) → 처리량·실패 → 지연 → 런타임 →
박스(호스트) → 고유 관측 → 로그** (db 는 앱 로그가 없어 로그 row 생략). 고유 관측만 서비스마다
다르다 — core 는 파싱·커넥션풀, extractor 는 추출 방법·escalation, renderer 는 동시성·용량,
db 는 백업.

패널 규율:

- **새 시계열을 만들지 않는다.** 무료티어 Metrics 한도 도달 상태라 계측 추가(히스토그램 버킷 등)
  금지 — 지연은 이미 존재하는 트레이스 파생 `traces_spanmetrics_latency`(native histogram,
  `span_kind="SPAN_KIND_SERVER"` 필터)로 계산한다. renderer 만 자체 버킷이 있다.
- **파싱 결과 집계는 로그 기반.** 카운터는 재배포마다 리셋되어 창집계가 깨진다 (#506).
- 수집 쪽 계약(라벨 유래·로그 필드)의 정본은 TeamPiKi/infra `contracts/observability.md`.
- JSON 을 손으로 만들지 않아도 된다 — UI 에서 고친 뒤 JSON Model 을 복사해 커밋하거나,
  JSON 을 고쳐 apply 한다. 어느 쪽이든 plan 이 드리프트를 잡는다.

## 기존 `terraform/` 과의 관계

| | `terraform/` (AWS) | `terraform-grafana/` (여기) |
|---|---|---|
| 대상 | EC2·VPC·S3·IAM 등 AWS 인프라 | Grafana Cloud 구성 (알림·대시보드) |
| state | 같은 버킷, `terraform.tfstate` | 같은 버킷, `grafana.tfstate` |
| 격리 | 서로의 apply 가 상대 state 를 건드리지 않는다 (루트 모듈·state 분리) | |

`backend.hcl` 은 `terraform/` 과 같은 값이다. 버킷명에 계정번호가 있어 gitignore 하고, 자격증명에서 파생해 만든다.

## 일상 워크플로우

AWS 자격은 state 버킷이 있는 계정으로 잡는다(준비는 `terraform/README.md`, 프로필을 쓰면 `AWS_PROFILE` 지정).
웹훅 SSM 조회와 state 접근에 필요하다.

```bash
cd terraform-grafana

# 1) backend 설정. 버킷명이 계정번호 파생이라 자격증명에서 도출한다
ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
echo "bucket = \"piki-tfstate-$ACCOUNT\"" > backend.hcl   # gitignore 됨, 커밋 금지

# 2) Grafana 인증 토큰 주입 (서비스 계정 terraform, Editor)
export GRAFANA_AUTH=$(aws ssm get-parameter \
  --name /piki/observability/grafana-terraform-token \
  --with-decryption --query Parameter.Value --output text)

# 3) init / plan / apply
terraform init -backend-config=backend.hcl
terraform plan    # 드리프트 감지: 변경 없음이어야 정상
terraform apply
```

plan 출력의 contact point 블록은 웹훅 URL 을 가린다. `terraform show -json` 결과에는 평문으로 들어 있어 공유하지 않는다.

## state 와 import 블록

state 는 AWS 계정의 버킷에 있고 Grafana 자원은 계정 밖에 있다. 계정 이관으로 버킷이 바뀌면 자원은
그대로인데 state 만 빈다. 2차 이관에서 실제로 state 가 구 계정 버킷과 함께 사라졌다 (#1200).

`restore/imports.tf` 가 자원 전부의 import 블록을 갖는다. 루트 모듈이 읽지 않는 폴더라 평소 plan 에는 끼지 않는다.
루트에 상시 두면 아직 Grafana 에 없는 새 자원의 import 가 plan 을 실패시킨다.

- **새 grafana 자원을 추가하면 `restore/imports.tf` 에 블록도 추가한다.** 자원과 블록이 1:1 이 아니면 이관 워크플로 정적 검증(migrate-lint)이 실패한다.
- 이관 때는 `migrate.yml` 의 `infra_grafana` job 이 이 파일을 루트로 복사해 재구성한다. plan 에 생성·교체 대상이 나오면 apply 전에 멈춘다.
  이 job 만 다시 돌리려면 `only=grafana`.
- state 가 비었는데 이관이 아닌 경우(버킷 사고 등)는 로컬에서 같은 일을 한다. 위 1)·2) 뒤:

```bash
cp restore/imports.tf restore_imports.tf   # gitignore 됨
terraform init -backend-config=backend.hcl
terraform plan    # N to import, 0 to add, 0 to destroy 이어야 한다
terraform apply
rm restore_imports.tf
terraform plan    # No changes
```

빈 state 에서 import 한 직후의 plan 은 변경 4건을 보인다. contact point 3개의 웹훅 URL(import 가 보안 필드를 비워 옴)과
폴더의 `prevent_destroy_if_not_empty`(Grafana 에 저장되지 않는 provider 속성)다. apply 하면 수렴한다.

## 시크릿

코드·tfvars 에 시크릿을 두지 않는다. 전부 SSM(`/piki/observability/`):

| 파라미터 | 용도 |
|---|---|
| `grafana-terraform-token` | provider 인증 (`GRAFANA_AUTH` 로 주입) |
| `discord-webhook-dev` / `-prod` / `-ops` | contact point 웹훅 (data source 로 조회) |

한계: data source 로 읽은 웹훅 URL 은 tfstate 에 남는다. state 버킷이 PIKI-Infra 한정 +
암호화 + 퍼블릭 차단이라 SSM 과 같은 접근 등급으로 본다.

## 규율

- **알림·대시보드 변경은 PR -> 리뷰 -> apply.** 평소 apply 는 수동(로컬)이다. 계정 이관 때만 `infra_grafana` job 이 apply 한다.
- **apply 전 `plan` 으로 드리프트를 확인한다.** 예상 밖 diff 는 누가 UI 에서 고쳤다는 뜻 —
  되돌릴지(그냥 apply) 코드에 흡수할지 PR 로 정한다.
- **디스코드 메시지 규율**: 본문은 각 룰의 `summary` annotation 이 정본이고, summary 에
  `[dev]`/`[prod]` 프리픽스를 넣지 않는다 (발신자명 DEV/PROD GRAFANA 가 환경 구분).
- 새 알림 룰: 기존 룰 그룹 형상을 참고해 `rule_groups.tf` 에 추가. Loki 건별 패턴은
  `sum by (..., trace_id) (count_over_time(...[10m])) > 0` + `for = "0s"`.
- **대시보드 read 403 플레이크**: Grafana Cloud 대시보드 GET 이 간헐적으로
  `dashboards:read` 403 을 뱉는다 (권한은 정상, 실측 2026-08-30). apply 의 read-back 에서
  나면 리소스가 tainted 로 남아 다음 plan 이 전량 replace 를 제안한다 — 라이브 실물이
  멀쩡하면 `terraform untaint grafana_dashboard.<name>` 후 재-plan 으로 수렴을 확인한다.
