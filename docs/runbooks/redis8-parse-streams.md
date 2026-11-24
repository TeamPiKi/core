# Redis 8 전환과 파싱 스트림 접근 적용 절차

#1306 의 변경을 dev 와 운영에 올리는 순서다. 코드 머지만으로 끝나는 것과 사람이 돌려야 하는 것이 섞여 있다.

## 무엇이 언제 반영되나

| 대상 | 반영되는 때 |
|---|---|
| dev Redis (이미지·설정·비밀번호·extractor 계정) | 이 PR 이 dev 에 머지돼 dev 배포가 돌 때. 배포가 `provision-runtime.sh` 를 실행 |
| 운영 Redis (이미지·설정·extractor 계정) | DB 서버에서 `provision-db.sh` 를 사람이 돌릴 때 |
| 방화벽 (dev 앱 서버의 6379) | `terraform apply` 를 사람이 돌릴 때 |
| 축출 알림 문구 | `terraform-grafana` 의 apply |

## 머지 전에 해 둘 것

순서를 지킨다. 1번 없이 머지하면 dev 배포가 Redis 단계에서 멈춘다.

1. dev Redis 비밀번호를 SSM 에 만든다.

   ```bash
   aws --profile piki ssm put-parameter --name /piki-core/dev/redis-password \
     --type SecureString --value "$(openssl rand -hex 24)"
   ```

   - dev 앱은 배포 때 `/piki-core/dev/` 를 통째로 읽으므로 `REDIS_PASSWORD` 가 따로 손대지 않아도 들어간다.
2. 방화벽을 연다. plan 에 dev 앱 보안그룹의 인바운드 규칙 1개 추가와 DB 보안그룹 규칙의 설명 변경만 있어야 한다.

   ```bash
   terraform -chdir=terraform plan
   terraform -chdir=terraform apply
   ```

## dev

머지하면 배포가 알아서 한다. 배포 로그에서 아래 줄을 본다.

```text
[redis] piki-redis 이미지나 기동 인자가 다름 — 재생성
[redis] piki-redis named 볼륨으로 기동
[redis] ready (attempt 1)
```

- 옛 컨테이너가 이름 있는 볼륨(`piki-redis-data`)을 쓰고 있었으면 세션이 이어진다. 아니면 dev 사용자는 다시 로그인한다.
- 확인.

  ```bash
  docker exec piki-redis sh -c 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli info server | grep redis_version'
  docker exec piki-redis sh -c 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli config get maxmemory-policy'
  docker exec piki-redis sh -c 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli config get appendonly'
  ```

## 운영

DB 서버에서 `provision-db.sh` 를 돌린다. 올리고 실행하는 방법은 그 스크립트 머리의 주석이 정본이다.

스크립트가 하는 일.

1. 떠 있는 Redis 에 AOF 가 꺼져 있으면 `CONFIG SET appendonly yes` 로 켜고 다시 쓰기가 끝나기를 기다린다.
2. compose 가 Redis 컨테이너를 새 이미지와 새 인자로 다시 만든다. 같은 볼륨의 AOF 로 이어받는다.

- 1번이 없으면 전원이 로그아웃된다. AOF 파일 없이 `appendonly yes` 로 뜬 Redis 는 스냅샷을 읽지 않고 빈 상태로 뜬다(로컬 실측).
- Redis 가 내려갔다 올라오는 동안(수 초) 로그인·토큰 갱신이 실패한다. 사용자가 적은 시간에 한다.
- 실행 로그에서 아래 줄을 확인한 뒤에야 컨테이너가 바뀐다. 이 줄 없이 60초가 지나면 스크립트가 교체하지 않고 멈춘다.

  ```text
  [redis] AOF 준비 완료 (attempt N)
  ```

확인.

```bash
R='docker exec piki-prod-redis sh -c'
$R 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli info server | grep redis_version'
$R 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli dbsize'          # 교체 전 값과 비슷해야 한다
$R 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli config get maxmemory-policy'
$R 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli config get appendonly'
$R 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli acl list'
```

- 교체 전에 `dbsize` 를 적어 둔다. 교체 뒤 0 에 가까우면 데이터가 이어지지 않은 것이다.
- 앱에서 로그인이 유지되는지 본다.

## 되돌리기

| 상황 | 방법 | 잃는 것 |
|---|---|---|
| 설정만 되돌림 | 8.10 은 두고 `CONFIG SET` 으로 값만 바꿈 | 없음 |
| 7.4 로 되돌림 | 볼륨을 비우고 옛 정의로 다시 올림 | 세션 전부. 전원 재로그인 |

- 7.4 는 8.10 이 쓴 데이터 파일을 읽지 못한다. 버전을 되돌리는 것은 곧 빈 Redis 로 시작하는 것이다.
- 그래서 운영 교체 전에 dev 에서 며칠 돌려 본다.

## 이 뒤에 남는 것

- extractor 배포가 두 Redis 의 주소와 스트림 계정을 넘기고 서버 방화벽 예외를 연다(TeamPiKi/extractor).
- 그 뒤에 #1313(파싱의 스트림 전환)을 머지한다. 순서가 뒤집히면 파싱이 전부 10분 뒤 실패로 닫힌다.
