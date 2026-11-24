#!/usr/bin/env bash
# 이관 동안 신 계정 박스의 자동 업데이트를 멈추고(hold) 다시 켠다(release). 러너에서 SSM Run Command 로 보낸다.
#   사용: migrate-box-apt.sh hold|release <Name 태그, 쉼표 구분>
set -euo pipefail

MODE=${1:?hold|release}
TAGS=${2:?Name 태그}

# unattended-upgrade-shutdown 은 상주 프로세스라 업데이트 본체만 찾는다. Run Command 는 /bin/sh 로 돈다.
# shellcheck disable=SC2016  # 박스에서 펼쳐질 스크립트라 여기서 확장하면 안 된다
HOLD='systemctl stop apt-daily.timer apt-daily-upgrade.timer
for _ in $(seq 1 90); do
  pgrep -f "apt.systemd.daily|/usr/bin/unattended-upgrade$" >/dev/null || exit 0
  sleep 10
done
echo "15분 안에 자동 업데이트가 끝나지 않았다" >&2
exit 1'

case "$MODE" in
  hold) SCRIPT=$HOLD ;;
  release) SCRIPT='systemctl start apt-daily.timer apt-daily-upgrade.timer' ;;
  *) echo "usage: $0 hold|release <tags>" >&2; exit 2 ;;
esac

read -r -a IDS <<< "$(aws ec2 describe-instances \
  --filters "Name=tag:Name,Values=$TAGS" Name=instance-state-name,Values=running \
  --query 'Reservations[].Instances[].InstanceId' --output text)"
[ "${#IDS[@]}" -gt 0 ] || { echo "::error::running 박스 없음 — $TAGS"; exit 1; }

# 등록 전 인스턴스로 send-command 를 보내면 InvalidInstanceId 로 실패한다.
for id in "${IDS[@]}"; do
  for _ in $(seq 1 60); do
    [ "$(aws ssm describe-instance-information --filters "Key=InstanceIds,Values=$id" \
      --query 'InstanceInformationList[0].PingStatus' --output text 2>/dev/null)" = Online ] && break
    sleep 10
  done
done

PARAMS=$(mktemp)
jq -n --arg s "$SCRIPT" '{commands: [$s]}' > "$PARAMS"
CMD_ID=$(aws ssm send-command --instance-ids "${IDS[@]}" --document-name AWS-RunShellScript \
  --comment "migrate apt $MODE" --parameters "file://$PARAMS" --query Command.CommandId --output text)
rm -f "$PARAMS"

for id in "${IDS[@]}"; do
  ST=Pending
  for _ in $(seq 1 120); do
    ST=$(aws ssm get-command-invocation --command-id "$CMD_ID" --instance-id "$id" \
      --query Status --output text 2>/dev/null || echo Pending)
    case "$ST" in Pending|InProgress|Delayed) sleep 10 ;; *) break ;; esac
  done
  if [ "$ST" != Success ]; then
    aws ssm get-command-invocation --command-id "$CMD_ID" --instance-id "$id" \
      --query StandardErrorContent --output text 2>/dev/null || true
    echo "::error::$id 자동 업데이트 $MODE 실패 — Status=$ST"
    exit 1
  fi
  echo "::notice::$id 자동 업데이트 $MODE 완료"
done
