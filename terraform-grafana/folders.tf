# 알림 룰이 들어가는 폴더. 대시보드 폴더와 무관하게 알림 전용이다.
resource "grafana_folder" "piki_alerts" {
  uid   = "piki-alerts"
  title = "PiKi Alerts"

  # 룰이 남아 있는 채로 폴더가 destroy 되는 사고 방지 (Grafana 10.2+).
  prevent_destroy_if_not_empty = true
}

locals {
  # Grafana Cloud 가 스택마다 만들어 주는 기본 폴더 "GrafanaCloud" 의 uid.
  # 우리가 만든 폴더가 아니라 리소스로 소유하지 않고 uid 만 참조한다 (piki-db 룰 그룹이 여기 있다).
  grafanacloud_folder_uid = "bfnixpw9f8bnkb"
}
