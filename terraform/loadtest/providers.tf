# -----------------------------------------------------------------------------
# 부하테스트 전용 스택 (#911) — 메인 state 와 분리된 별도 state
#
# 왜 분리했나 (실측 사고): 이전에는 loadtest 리소스를 메인 terraform/ 에 두고 -target 으로만
# 다뤘다. 그 리소스가 브랜치에만 있고 dev 코드에는 없어서, 무관한 apply(#954 IAM 추가)의 plan 이
# "설정에 없음 = 삭제"로 잡아 박스를 통째로 파괴했다. 브랜치 전략을 유지하는 한 누가 apply 하든
# 같은 일이 반복된다.
#
# state 를 나누면 그 경로가 구조적으로 막힌다 — 메인 plan 은 이 리소스를 아예 모르고, 이쪽
# destroy 도 메인을 건드리지 않는다. 대신 VPC·서브넷·instance profile 은 메인 소유라
# data source 로 읽기만 한다(생성·수정하지 않는다).
#
# 사용:
#   cd terraform/loadtest
#   terraform init -backend-config=backend.hcl
#   terraform plan   # loadtest 리소스만 나와야 한다
#   terraform apply
#   terraform destroy   # 윈도우 종료 후
# -----------------------------------------------------------------------------
terraform {
  required_version = ">= 1.14"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }

  backend "s3" {
    # bucket 은 계정번호를 포함하므로 코드에 넣지 않는다(퍼블릭 repo).
    # 메인과 같은 state 버킷을 쓰되 key 를 달리해 state 를 분리한다.
    # backend.hcl 은 메인 terraform/backend.hcl 을 그대로 복사해 쓴다(bucket 한 줄).
    key          = "loadtest/terraform.tfstate"
    region       = "ap-northeast-2"
    encrypt      = true
    use_lockfile = true
  }
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project     = "team3"
      Environment = "loadtest"
      ManagedBy   = "terraform"
      # 소모품 표식 — 윈도우 종료 후 destroy 대상임을 콘솔에서도 알아볼 수 있게 한다.
      Ephemeral = "true"
    }
  }
}
