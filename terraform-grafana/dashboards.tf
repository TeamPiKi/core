# 대시보드 5개 (#1011). 정본은 dashboards/*.json 이고 이 리소스가 그대로 반영한다.
# 구조: 한눈(전체 요약) 1 + 같은 골격을 공유하는 서비스별 4 (core / extractor / renderer / db).
# 라이브(UI)에서 고친 것은 plan 이 드리프트로 잡는다 - JSON 으로 가져와 커밋하거나 apply 로 되돌린다.

resource "grafana_dashboard" "glance" {
  config_json = file("${path.module}/dashboards/glance.json")
}

resource "grafana_dashboard" "core" {
  config_json = file("${path.module}/dashboards/core.json")
}

resource "grafana_dashboard" "extractor" {
  config_json = file("${path.module}/dashboards/extractor.json")
}

resource "grafana_dashboard" "renderer" {
  config_json = file("${path.module}/dashboards/renderer.json")
}

resource "grafana_dashboard" "db" {
  config_json = file("${path.module}/dashboards/db.json")
}
