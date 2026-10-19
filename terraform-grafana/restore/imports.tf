# state 가 비었을 때(계정 이관으로 버킷이 새로 생김) 루트로 복사해 기존 자원을 state 로 들인다 (#1200).
# 루트에 상시 두지 않는 이유: 아직 Grafana 에 없는 자원의 import 블록은 plan 을 실패시킨다.
# 새 자원을 추가하면 여기에도 블록을 추가한다(migrate-lint 가 검사). 복사는 migrate.yml infra_grafana 와 README 복구 절차.

import {
  to = grafana_folder.piki_alerts
  id = "piki-alerts"
}

import {
  to = grafana_dashboard.glance
  id = jsondecode(file("${path.module}/dashboards/glance.json")).uid
}

import {
  to = grafana_dashboard.core
  id = jsondecode(file("${path.module}/dashboards/core.json")).uid
}

import {
  to = grafana_dashboard.extractor
  id = jsondecode(file("${path.module}/dashboards/extractor.json")).uid
}

import {
  to = grafana_dashboard.renderer
  id = jsondecode(file("${path.module}/dashboards/renderer.json")).uid
}

import {
  to = grafana_dashboard.db
  id = jsondecode(file("${path.module}/dashboards/db.json")).uid
}

import {
  to = grafana_contact_point.discord_dev
  id = "discord-dev"
}

import {
  to = grafana_contact_point.discord_prod
  id = "discord-prod"
}

import {
  to = grafana_contact_point.piki_ops_discord
  id = "piki-ops-discord"
}

# 조직에 하나뿐인 싱글턴이라 provider 가 id 를 보지 않는다.
import {
  to = grafana_notification_policy.root
  id = "policy"
}

import {
  to = grafana_rule_group.piki_db
  id = "${local.grafanacloud_folder_uid}:piki-db"
}

import {
  to = grafana_rule_group.logs
  id = "piki-alerts:logs"
}

import {
  to = grafana_rule_group.parsing
  id = "piki-alerts:parsing"
}

import {
  to = grafana_rule_group.health
  id = "piki-alerts:health"
}
