# 운영 Redis 8 전환 절차

#1306 의 변경을 운영 Redis 에 올리는 순서다. 코드 머지만으로는 운영이 바뀌지 않는다.

## 무엇이 언제 반영되나

| 대상 | 반영되는 때 |
|---|---|
| 운영 Redis (이미지·설정·extractor 계정) | DB 서버에서 `provision-db.sh` 를 돌릴 때 |
| 방화벽 규칙의 설명 | `terraform apply` |
| 축출 알림 문구 | `terraform-grafana` 의 apply |

dev Redis 는 이 변경의 대상이 아니다. dev 를 운영과 같은 구조(DB 서버 분리, 같은 compose)로 옮기는 작업에서 함께 올린다.

## 실행

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

## 확인

교체 전에 `dbsize` 를 적어 둔다. 교체 뒤 0 에 가까우면 데이터가 이어지지 않은 것이다.

```bash
R='docker exec piki-prod-redis sh -c'
$R 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli info server | grep redis_version'
$R 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli dbsize'
$R 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli config get maxmemory-policy'
$R 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli config get appendonly'
$R 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli acl list'
```

- 앱에서 로그인이 유지되는지 본다.

## 되돌리기

| 상황 | 방법 | 잃는 것 |
|---|---|---|
| 설정만 되돌림 | 8.10 은 두고 `CONFIG SET` 으로 값만 바꿈 | 없음 |
| 7.4 로 되돌림 | 볼륨을 비우고 옛 정의로 다시 올림 | 세션 전부. 전원 재로그인 |

- 7.4 는 8.10 이 쓴 데이터 파일을 읽지 못한다. 버전을 되돌리는 것은 곧 빈 Redis 로 시작하는 것이다.

## 이 뒤에 남는 것

- extractor 의 스트림 소비자 배포(TeamPiKi/extractor#163). 스트림을 켜는 스위치는 extractor SSM 의 스트림 비밀번호 키다.
- dev 를 운영과 같은 구조로 옮기는 작업. 그 뒤에 #1313(파싱의 스트림 전환)을 머지한다.
  순서가 뒤집히면 파싱이 전부 10분 뒤 실패로 닫힌다.
