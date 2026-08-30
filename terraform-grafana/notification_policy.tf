# 루트 알림 정책 — 조직에 하나뿐인 싱글턴 리소스다.
# 기본은 prod 수신자로 가고, environment=dev 라벨만 dev 수신자로 갈라진다 (둘 다 같은 채널, 발신자명 구분).
# group_by 에 alertname·environment: 같은 룰·환경의 인스턴스들이 한 메시지로 묶인다 ([FIRING:N]).
resource "grafana_notification_policy" "root" {
  disable_provenance = true

  contact_point   = grafana_contact_point.discord_prod.name
  group_by        = ["alertname", "environment"]
  group_wait      = "30s"
  group_interval  = "5m"
  repeat_interval = "4h"

  policy {
    contact_point = grafana_contact_point.discord_dev.name

    matcher {
      label = "environment"
      match = "="
      value = "dev"
    }
  }
}
