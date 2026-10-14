# state 가 비어도(계정 이관으로 버킷이 새로 생김) plan 이 기존 자원을 생성하지 않고 state 로 들인다 (#1200).
# 이미 state 에 있으면 아무 일도 하지 않는다. 새 grafana 자원을 추가하면 여기에도 블록을 추가한다(migrate-lint 가 검사).

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
