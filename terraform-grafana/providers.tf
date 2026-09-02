terraform {
  required_version = ">= 1.14"

  required_providers {
    grafana = {
      source  = "grafana/grafana"
      version = "~> 4.45"
    }
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }

  backend "s3" {
    # terraform/ 과 같은 버킷을 쓰되 key 를 분리해 상태를 격리한다 — 알림 구성 apply 가
    # AWS 인프라 state 를 절대 건드리지 않게. bucket 은 계정번호를 포함하므로 코드에 넣지 않고
    # terraform/ 과 동일한 backend.hcl 을 재사용한다: terraform init -backend-config=backend.hcl
    key          = "grafana.tfstate"
    region       = "ap-northeast-2"
    encrypt      = true
    use_lockfile = true
  }
}

# 인증은 GRAFANA_AUTH 환경변수(서비스 계정 terraform 의 토큰, SSM /piki/observability/grafana-terraform-token)로
# 주입한다. 변수로 받지 않는 이유: tfvars 에 적혀 커밋될 여지를 없앤다. 실행 방법은 README.
provider "grafana" {
  url = "https://piki.grafana.net"
}

# 디스코드 웹훅 URL 을 SSM 에서 읽기 위한 최소 구성 (state 백엔드와 같은 계정).
provider "aws" {
  region = "ap-northeast-2"
}
