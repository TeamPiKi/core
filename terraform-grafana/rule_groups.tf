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
    name           = "prod DB 백업이 3시간 넘게 성공하지 않음"
    condition      = "B"
    for            = "10m"
    no_data_state  = "Alerting"
    exec_err_state = "Alerting"
    is_paused      = false
    annotations = {
      description = "백업은 매시 돌고, 마지막 성공 이후 경과 시간이 3시간을 넘었습니다. 백업은 유일한 복구 경로이므로 즉시 확인이 필요합니다. 박스에서 `journalctl -t piki-db-backup --since \"6 hours ago\"` 로 원인을 봅니다. 지표가 아예 없어 NoData 로 발화한 경우라면 백업 박스나 alloy 수집기가 멈춘 것입니다."
      summary     = "prod DB 백업이 3시간 넘게 성공하지 않았습니다."
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
                10800
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

  # allkeys-lru 라 축출이 곧 가득 참이다. TTL 만료는 별도 카운터(expired_keys)라 잡히지 않는다 (#1205).
  rule {
    name           = "prod Redis 가 가득 차 키를 축출함"
    condition      = "B"
    for            = "0s"
    no_data_state  = "Alerting"
    exec_err_state = "Alerting"
    is_paused      = false
    annotations = {
      description = "Redis 가 maxmemory(64MB)에 닿아 오래 안 쓴 키부터 지우고 있습니다. 해당 사용자는 다시 로그인하게 되고, 사용자별 세션 인덱스가 지워졌다면 탈퇴·정지 때 그 사용자 세션이 남을 수 있습니다. DB 박스에서 `docker exec piki-prod-redis sh -c 'REDISCLI_AUTH=\"$REDIS_PASSWORD\" redis-cli INFO memory'` 로 사용량을 보고 maxmemory 증설이나 세션 저장소 DB 이관을 결정합니다. 지표가 아예 없어 NoData 로 발화한 경우라면 redis-exporter 나 alloy 수집기가 멈춘 것입니다."
      summary     = "prod Redis 가 메모리 한도에 닿아 키를 축출하고 있습니다."
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
        "expr": "increase(redis_evicted_keys_total{environment=\"prod\"}[10m])",
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
                0
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
  rule {
    name           = "prod MySQL 응답 없음"
    condition      = "B"
    for            = "5m"
    no_data_state  = "Alerting"
    exec_err_state = "Alerting"
    is_paused      = false
    annotations = {
      description = "DB 서버에서 `docker ps -a` 로 piki-prod-mysql 상태를 봅니다. 지표가 아예 없어 NoData 로 발화한 경우라면 mysqld-exporter 나 alloy 수집기가 멈춘 것입니다."
      summary     = "prod MySQL 이 5분 넘게 응답하지 않습니다."
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
        "expr": "min(mysql_up{environment=\"prod\"})",
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
                1
              ],
              "type": "lt"
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

  rule {
    name           = "prod Redis 응답 없음"
    condition      = "B"
    for            = "5m"
    no_data_state  = "Alerting"
    exec_err_state = "Alerting"
    is_paused      = false
    annotations = {
      description = "로그인 세션이 Redis 에 있어 토큰 갱신이 실패합니다. DB 서버에서 `docker ps -a` 로 piki-prod-redis 상태를 봅니다. 지표가 아예 없어 NoData 로 발화한 경우라면 redis-exporter 나 alloy 수집기가 멈춘 것입니다."
      summary     = "prod Redis 가 5분 넘게 응답하지 않습니다."
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
        "expr": "min(redis_up{environment=\"prod\"})",
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
                1
              ],
              "type": "lt"
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

# 등록한 패턴만 보는 다른 룰과 달리 전 서비스 ERROR 를 전부 잡는다 (#1105).
# WARN 은 "비정상이지만 처리가 끝난 것"이라 알림에서 뺐다.
# trace_id 를 묶음에 넣으면 건별 알림으로 되돌아간다. 파싱 실패는 parsing 룰이 건별로 알려서 뺀다.
# logs_url 은 없는 라벨을 넣으면 "[no value]" 가, Tomcat logger 는 대괄호가 Discord 링크 문법을 깨서 조건부·인코딩 처리한다 (#1114).
resource "grafana_rule_group" "logs" {
  name               = "logs"
  folder_uid         = grafana_folder.piki_alerts.uid
  interval_seconds   = 60
  disable_provenance = true

  rule {
    name           = "서버 로그 (전 서비스, ERROR)"
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
        "expr": "sum by (service, environment, level, logger, error_type) (count_over_time({service=~\"piki-.+\", level=~\"(?i)(error|critical)\"} != \"item.parse.result\" [10m]))",
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
  # JVM 이 직접 찍는 줄이라 level 라벨이 없어 ERROR 룰에 안 걸린다. 부팅 때 JVM 옵션 줄에도 OutOfMemoryError 가 있어 종료 문구까지 맞춘다.
  rule {
    name           = "JVM 힙 OOM 종료"
    condition      = "B"
    for            = "0s"
    no_data_state  = "OK"
    exec_err_state = "Error"
    is_paused      = false
    annotations = {
      summary = "{{ $labels.container }} ({{ $labels.environment }}) 가 JVM 힙 부족으로 종료됐습니다."
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
        "expr": "sum by (service, environment, container) (count_over_time({service=~\"piki-.+\"} |= \"Terminating due to java.lang.OutOfMemoryError\" [10m]))",
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

# 서버·앱 생존과 자원 한도 (#1227). 다른 그룹과 달리 이벤트가 아니라 상태를 본다.
resource "grafana_rule_group" "health" {
  name               = "health"
  folder_uid         = grafana_folder.piki_alerts.uid
  interval_seconds   = 60
  disable_provenance = true

  # 서버 목록을 코드에 두지 않으려고 지난 1일엔 있었는데 지금 없는 서버를 뽑는다. 사라진 시계열은 NoData 가 아니라 해제로 처리되기 때문이다.
  rule {
    name           = "서버 신호 끊김"
    condition      = "B"
    for            = "5m"
    no_data_state  = "OK"
    exec_err_state = "Error"
    is_paused      = false
    annotations = {
      description = "서버 정지, Alloy 중단, Grafana Cloud 토큰 문제 중 하나입니다. SSM 으로 접속해 `docker ps` 로 piki-alloy 상태부터 봅니다."
      summary     = "{{ $labels.box }} ({{ $labels.environment }}) 서버의 메트릭이 5분 넘게 들어오지 않습니다."
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
        "expr": "group by (environment, box) (max_over_time(node_memory_MemTotal_bytes[1d])) unless on (environment, box) node_memory_MemTotal_bytes",
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
                0
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

  # 서버 신호가 살아 있을 때만 발화한다. Alloy 가 멈췄으면 서버 신호 끊김 하나만 울리게 하려는 것이다.
  rule {
    name           = "앱 중단"
    condition      = "B"
    for            = "5m"
    no_data_state  = "OK"
    exec_err_state = "Error"
    is_paused      = false
    annotations = {
      description = "블루그린 두 슬롯 모두 수집 대상에서 up 이 1 이 아닙니다. 서버의 `docker ps -a` 와 컨테이너 로그를 봅니다."
      summary     = "{{ $labels.service }} ({{ $labels.environment }}) 가 5분 넘게 응답하지 않습니다."
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
        "expr": "(group by (environment, box, service) (max_over_time(up{job=~\"piki-(core|extractor|renderer)\"}[1d])) unless on (environment, service) (max by (environment, service) (up{job=~\"piki-(core|extractor|renderer)\"}) == 1)) and on (environment, box) node_memory_MemTotal_bytes",
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
                0
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

  rule {
    name           = "서버 디스크 부족"
    condition      = "B"
    for            = "10m"
    no_data_state  = "OK"
    exec_err_state = "Error"
    is_paused      = false
    annotations = {
      description = "디스크가 차면 배포와 DB 쓰기가 실패합니다. `docker system df` 와 `/var/log` 크기를 봅니다."
      summary     = "{{ $labels.box }} ({{ $labels.environment }}) 서버 루트 디스크 여유가 10% 미만입니다."
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
        "expr": "node_filesystem_avail_bytes{mountpoint=\"/\"} / node_filesystem_size_bytes{mountpoint=\"/\"}",
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
                0.1
              ],
              "type": "lt"
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

  # dev 는 의도적으로 빠듯하게 잡혀 있어 뺀다.
  rule {
    name           = "서버 가용 메모리 부족"
    condition      = "B"
    for            = "15m"
    no_data_state  = "OK"
    exec_err_state = "Error"
    is_paused      = false
    annotations = {
      summary = "{{ $labels.box }} 서버 가용 메모리가 10% 미만입니다."
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
        "expr": "node_memory_MemAvailable_bytes{environment=\"prod\"} / node_memory_MemTotal_bytes{environment=\"prod\"}",
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
                0.1
              ],
              "type": "lt"
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

  rule {
    name           = "서버 스왑 과다"
    condition      = "B"
    for            = "15m"
    no_data_state  = "OK"
    exec_err_state = "Error"
    is_paused      = false
    annotations = {
      summary = "{{ $labels.box }} 서버 스왑 사용이 50%를 넘었습니다."
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
        "expr": "(node_memory_SwapTotal_bytes{environment=\"prod\"} - node_memory_SwapFree_bytes{environment=\"prod\"}) / (node_memory_SwapTotal_bytes{environment=\"prod\"} > 0)",
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
                0.5
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

  # 스왑 한도 지표는 스왑 몫만 담아 메모리 한도와 더한다. 한도가 없는 컨테이너는 합이 0 이라 빠진다.
  rule {
    name           = "컨테이너 메모리 한도 근접"
    condition      = "B"
    for            = "10m"
    no_data_state  = "OK"
    exec_err_state = "Error"
    is_paused      = false
    annotations = {
      description = "넘치면 커널이 컨테이너 프로세스를 강제 종료합니다."
      summary     = "{{ $labels.container }} ({{ $labels.box }}) 가 메모리와 스왑을 합친 한도의 90%를 넘었습니다."
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
        "expr": "(container_memory_working_set_bytes{environment=\"prod\"} + container_memory_swap{environment=\"prod\"}) / (container_spec_memory_limit_bytes{environment=\"prod\"} + container_spec_memory_swap_limit_bytes{environment=\"prod\"} > 0)",
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
                0.9
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

  rule {
    name           = "커널 OOM kill"
    condition      = "B"
    for            = "0s"
    no_data_state  = "OK"
    exec_err_state = "Error"
    is_paused      = false
    annotations = {
      description = "어떤 프로세스인지는 서버의 `journalctl -k | grep -i oom` 으로 봅니다. renderer 의 Chrome 은 과부하 때 이렇게 죽도록 설계돼 있습니다."
      summary     = "{{ $labels.box }} ({{ $labels.environment }}) 서버에서 커널이 메모리 부족으로 프로세스를 죽였습니다."
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
        "expr": "increase(node_vmstat_oom_kill[10m])",
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
                0
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
