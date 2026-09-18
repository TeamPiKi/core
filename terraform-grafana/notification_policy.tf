# 루트 알림 정책 — 조직에 하나뿐인 싱글턴 리소스다.
# 기본은 prod 수신자로 가고, environment=dev 라벨만 dev 수신자로 갈라진다 (둘 다 같은 채널, 발신자명 구분).
# group_by 에 alertname·environment·service·logger: 서버 로그 룰은 종류(logger)마다 메시지 1건이 된다 (#1114).
# service 로만 묶으면 새 종류가 들어올 때마다 그룹 전체가 다시 실려 앞선 종류가 반복됐다. 라벨이 없는 룰은 빈 값으로 묶여 이전과 동일하다.
resource "grafana_notification_policy" "root" {
  disable_provenance = true

  contact_point   = grafana_contact_point.discord_prod.name
  group_by        = ["alertname", "environment", "service", "logger"]
  group_wait      = "30s"
  group_interval  = "5m"
  repeat_interval = "6h"

  policy {
    contact_point = grafana_contact_point.discord_dev.name

    matcher {
      label = "environment"
      match = "="
      value = "dev"
    }
  }
}
