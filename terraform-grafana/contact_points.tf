locals {
  # summary 기반 공용 메시지 (2026-08-29 일반화). 각 룰의 summary annotation 이 본문이 되므로
  # 새 룰은 summary 만 잘 쓰면 이 템플릿을 만질 필요가 없다.
  # 규율: summary 에 [dev]/[prod] 프리픽스를 넣지 않는다 — 환경 구분은 발신자명(DEV/PROD GRAFANA)이 담당한다.
  # 딥링크 시간 앵커 (#1014): trace_url·logs_url 어노테이션의 __FROM__/__TO__ 를 알림별 StartsAt
  # 기준 절대 시각(발화 15분 전부터 5분 후까지, epoch millis)으로 치환한다 — 클릭 시점과 무관하게
  # 같은 창이 열린다. 플레이스홀더가 없는 URL 은 치환이 no-op 라 그대로 나간다.
  discord_message = "{{ range .Alerts.Firing }}{{ $from := printf \"%d\" (.StartsAt.Add -900000000000).UnixMilli }}{{ $to := printf \"%d\" (.StartsAt.Add 300000000000).UnixMilli }}{{ .Annotations.summary }}{{ if .Labels.url }}\n상품: {{ .Labels.url }}{{ end }}\n{{ if .Annotations.trace_url }}[트레이스 열기]({{ reReplaceAll \"__TO__\" $to (reReplaceAll \"__FROM__\" $from .Annotations.trace_url) }}){{ else if .Annotations.logs_url }}[로그 열기]({{ reReplaceAll \"__TO__\" $to (reReplaceAll \"__FROM__\" $from .Annotations.logs_url) }}){{ end }}\n{{ end }}"
}

# dev·prod 웹훅은 같은 디스코드 채널을 가리키고, 발신자명(웹훅 이름)으로만 환경을 구분한다.
resource "grafana_contact_point" "discord_dev" {
  name               = "discord-dev"
  disable_provenance = true

  discord {
    url                     = data.aws_ssm_parameter.discord_webhook_dev.value
    message                 = local.discord_message
    use_discord_username    = true
    disable_resolve_message = true
  }
}

resource "grafana_contact_point" "discord_prod" {
  name               = "discord-prod"
  disable_provenance = true

  discord {
    url                     = data.aws_ssm_parameter.discord_webhook_prod.value
    message                 = local.discord_message
    use_discord_username    = true
    disable_resolve_message = true
  }
}

# 기본 템플릿 그대로 쓰는 범용 수신자 (현재 어느 정책에도 연결되지 않음, 수동 테스트 용도로 유지).
resource "grafana_contact_point" "piki_ops_discord" {
  name               = "piki-ops-discord"
  disable_provenance = true

  discord {
    url                     = data.aws_ssm_parameter.discord_webhook_ops.value
    disable_resolve_message = false
  }
}
