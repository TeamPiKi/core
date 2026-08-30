# Grafana 대시보드 - 이관됨

대시보드 정본은 `terraform-grafana/dashboards/*.json` 으로 이관됐고 (#1011),
반영도 수동 API 호출이 아니라 `terraform apply` 다. 절차·패널 규율은
`terraform-grafana/README.md` 를 본다.

이 디렉터리에 있던 `dashboard.json`(piki-app-overview) · `fleet-overview.json`(piki-fleet-overview) 은
한눈 1 + 서비스별 4 (core / extractor / renderer / db) 구조로 재편되며 삭제됐다.
옛 파일이 필요하면 git 히스토리에서 찾는다.

수집 쪽 계약(라벨 유래·로그 필드 경로·label opt-in)의 정본은 변함없이
TeamPiKi/infra `contracts/observability.md` 다.
