# 알림 룰 그룹 - 라이브에서 import 한 현행 형상 (이슈 #1008).
# 룰 그룹이 배포 단위다: 한 그룹의 룰 전체가 이 리소스 하나로 관리된다.
# disable_provenance = true 라 UI 에서도 편집 가능하지만, 다음 apply 가 코드 형상으로 되돌린다 (정본은 코드).

# prod DB 백업 침묵 감지 (#909). GrafanaCloud 기본 폴더에 있어 폴더는 소유하지 않는다
resource "grafana_rule_group" "piki_db" {
  name               = "piki-db"
  folder_uid         = local.grafanacloud_folder_uid
  interval_seconds   = 60
  disable_provenance = true

  rule {
    name           = "prod DB 백업이 24시간 넘게 성공하지 않음"
    condition      = "B"
    for            = "10m"
    no_data_state  = "Alerting"
    exec_err_state = "Alerting"
    is_paused      = false
    annotations = {
      description = "마지막 성공 이후 경과 시간이 24시간을 넘었습니다. 백업은 유일한 복구 경로이므로 즉시 확인이 필요합니다. 박스에서 `journalctl -t piki-db-backup --since \"2 days ago\"` 로 원인을 봅니다. 지표가 아예 없어 NoData 로 발화한 경우라면 백업 박스나 alloy 수집기가 멈춘 것입니다."
      summary     = "prod DB 백업이 24시간 넘게 성공하지 않았습니다."
    }
    labels = {
      service  = "piki-db"
      severity = "critical"
    }
    data {
      ref_id         = "A"
      query_type     = ""
      datasource_uid = "grafanacloud-prom"
      relative_time_range {
        from = 600
        to   = 0
      }
      model = <<-EOT
      {
        "expr": "time() - piki_db_backup_last_success_timestamp_seconds",
        "instant": true,
        "intervalMs": 1000,
        "maxDataPoints": 43200,
        "refId": "A"
      }
      EOT
    }
    data {
      ref_id         = "B"
      query_type     = ""
      datasource_uid = "__expr__"
      relative_time_range {
        from = 0
        to   = 0
      }
      model = <<-EOT
      {
        "conditions": [
          {
            "evaluator": {
              "params": [
                86400
              ],
              "type": "gt"
            }
          }
        ],
        "expression": "A",
        "intervalMs": 1000,
        "maxDataPoints": 43200,
        "refId": "B",
        "type": "threshold"
      }
      EOT
    }
  }
}

locals {
  # 기동마다 고정으로 찍히는 무해한 WARN. ERROR 는 가리지 않는다. 근본 해소는 #1111, 해소된 logger 는 여기서 지운다.
  startup_noise_loggers = [
    "org.flywaydb.core.internal.database.base.Database",
    "org.flywaydb.core.internal.command.DbMigrate",
    "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration",
    "org.hibernate.orm.deprecation",
    "org.springdoc.core.events.SpringDocAppInitializer",
  ]
}

# 등록한 패턴만 보는 다른 룰과 달리 전 서비스 WARN·ERROR 를 전부 잡는다 (#1105).
# trace_id 를 묶음에 넣으면 건별 알림으로 되돌아간다. 파싱 실패는 parsing 룰이 건별로 알려서 뺀다.
# logs_url 은 없는 라벨을 넣으면 "[no value]" 가, Tomcat logger 는 대괄호가 Discord 링크 문법을 깨서 조건부·인코딩 처리한다 (#1114).
resource "grafana_rule_group" "logs" {
  name               = "logs"
  folder_uid         = grafana_folder.piki_alerts.uid
  interval_seconds   = 60
  disable_provenance = true

  rule {
    name           = "서버 로그 (전 서비스, WARN·ERROR)"
    condition      = "B"
    for            = "0s"
    no_data_state  = "OK"
    exec_err_state = "Error"
    is_paused      = false
    annotations = {
      logs_url = "https://piki.grafana.net/explore?schemaVersion=1&panes=%7B%22lg%22%3A%7B%22datasource%22%3A%22grafanacloud-logs%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22%7Bservice%3D%5C%22{{ $labels.service }}%5C%22%2C%20environment%3D%5C%22{{ $labels.environment }}%5C%22%2C%20level%3D%5C%22{{ $labels.level }}%5C%22%7D{{ if $labels.error_type }}%20%7C%20error_type%3D%5C%22{{ $labels.error_type }}%5C%22{{ end }}{{ if $labels.logger }}%20%7C%20logger%3D%5C%22{{ reReplaceAll \"[]]\" \"%5D\" (reReplaceAll \"[[]\" \"%5B\" $labels.logger) }}%5C%22{{ end }}%22%7D%5D%2C%22range%22%3A%7B%22from%22%3A%22__FROM__%22%2C%22to%22%3A%22__TO__%22%7D%7D%7D"
      summary  = "{{ $labels.service }} {{ $labels.level }}{{ if $labels.error_type }} - {{ $labels.error_type }}{{ end }}{{ if $labels.logger }} ({{ $labels.logger }}){{ end }}"
    }
    data {
      ref_id         = "A"
      query_type     = "instant"
      datasource_uid = "grafanacloud-logs"
      relative_time_range {
        from = 600
        to   = 0
      }
      model = <<-EOT
      {
        "expr": "sum by (service, environment, level, logger, error_type) (count_over_time({service=~\"piki-.+\", level=~\"(?i)(error|warn(ing)?|critical)\"} != \"item.parse.result\" | level=~\"(?i)(error|critical)\" or logger!~\"${join("|", local.startup_noise_loggers)}\" [10m]))",
        "intervalMs": 1000,
        "maxDataPoints": 43200,
        "queryType": "instant",
        "refId": "A"
      }
      EOT
    }
    data {
      ref_id         = "B"
      query_type     = ""
      datasource_uid = "__expr__"
      relative_time_range {
        from = 0
        to   = 0
      }
      model = <<-EOT
      {
        "conditions": [
          {
            "evaluator": {
              "params": [
                0
              ],
              "type": "gt"
            },
            "operator": {
              "type": "and"
            },
            "query": {
              "params": [
                "A"
              ]
            },
            "reducer": {
              "params": [],
              "type": "last"
            }
          }
        ],
        "datasource": {
          "type": "__expr__",
          "uid": "__expr__"
        },
        "expression": "A",
        "intervalMs": 1000,
        "maxDataPoints": 43200,
        "refId": "B",
        "type": "threshold"
      }
      EOT
    }
  }
}

# 파싱 실패 건별 알림 (2026-08-10 구축)
resource "grafana_rule_group" "parsing" {
  name               = "parsing"
  folder_uid         = grafana_folder.piki_alerts.uid
  interval_seconds   = 60
  disable_provenance = true

  rule {
    name           = "파싱 실패 (item.parse result=failed)"
    condition      = "B"
    for            = "0s"
    no_data_state  = "OK"
    exec_err_state = "Error"
    is_paused      = false
    annotations = {
      logs_url  = "https://piki.grafana.net/explore?schemaVersion=1&panes=%7B%22lg%22%3A%7B%22datasource%22%3A%22grafanacloud-logs%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22%7Bservice%3D%5C%22piki-core%5C%22%2C%20environment%3D%5C%22{{ $labels.environment }}%5C%22%7D%20%7C~%20%5C%22item.parse%5C%22%22%7D%5D%2C%22range%22%3A%7B%22from%22%3A%22__FROM__%22%2C%22to%22%3A%22__TO__%22%7D%7D%7D"
      summary   = "파싱 실패 - item {{ $labels.item }}, 사유 {{ $labels.reason }}"
      trace_url = "{{ if $labels.trace_id }}https://piki.grafana.net/explore?schemaVersion=1&panes=%7B%22tr%22%3A%7B%22datasource%22%3A%22grafanacloud-traces%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22queryType%22%3A%22traceql%22%2C%22query%22%3A%22{{ $labels.trace_id }}%22%7D%5D%2C%22range%22%3A%7B%22from%22%3A%22__FROM__%22%2C%22to%22%3A%22__TO__%22%7D%7D%7D{{ end }}"
    }
    data {
      ref_id         = "A"
      query_type     = "instant"
      datasource_uid = "grafanacloud-logs"
      relative_time_range {
        from = 600
        to   = 0
      }
      model = <<-EOT
      {
        "expr": "sum by (environment, trace_id, item, reason, url) (count_over_time({service=\"piki-core\", environment=~\"dev|prod\"} |= \"item.parse.result\" | logfmt | __error__=\"\" | result=\"failed\" [10m]))",
        "intervalMs": 1000,
        "maxDataPoints": 43200,
        "queryType": "instant",
        "refId": "A"
      }
      EOT
    }
    data {
      ref_id         = "B"
      query_type     = ""
      datasource_uid = "__expr__"
      relative_time_range {
        from = 0
        to   = 0
      }
      model = <<-EOT
      {
        "conditions": [
          {
            "evaluator": {
              "params": [
                0
              ],
              "type": "gt"
            },
            "operator": {
              "type": "and"
            },
            "query": {
              "params": [
                "A"
              ]
            },
            "reducer": {
              "params": [],
              "type": "last"
            }
          }
        ],
        "datasource": {
          "type": "__expr__",
          "uid": "__expr__"
        },
        "expression": "A",
        "intervalMs": 1000,
        "maxDataPoints": 43200,
        "refId": "B",
        "type": "threshold"
      }
      EOT
    }
  }
}
