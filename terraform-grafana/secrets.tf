# 디스코드 웹훅 URL — SSM 이 단일 보관처(/piki/observability/*). 코드·tfvars 에 평문을 두지 않는다.
# 한계 인지: data source 로 읽은 값은 tfstate 에는 남는다. state 버킷은 PIKI-Infra 그룹 한정 +
# SSE + 퍼블릭 차단이라 SSM 과 같은 접근 등급으로 본다 (terraform/README 의 버킷 보안 참조).

data "aws_ssm_parameter" "discord_webhook_dev" {
  name = "/piki/observability/discord-webhook-dev"
}

data "aws_ssm_parameter" "discord_webhook_prod" {
  name = "/piki/observability/discord-webhook-prod"
}

data "aws_ssm_parameter" "discord_webhook_ops" {
  name = "/piki/observability/discord-webhook-ops"
}
