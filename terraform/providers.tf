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
    # 값은 gitignore 된 backend.hcl 로 주입: terraform init -backend-config=backend.hcl
    # (backend.hcl 은 자격증명에서 파생 생성 — README "일상 워크플로우" 참고.)
    key          = "terraform.tfstate"
    region       = "ap-northeast-2"
    encrypt      = true
    use_lockfile = true
  }
}

# -----------------------------------------------------------------------------
# AWS Provider
#
# 자격증명은 환경변수(AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY)로 주입한다.
# 로컬 ~/.aws/credentials 에 영구 저장하지 않는 정책.
# 키는 작업 시점에 콘솔에서 생성 → export → 작업 종료 후 삭제.
# -----------------------------------------------------------------------------
provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project     = var.project
      Environment = var.environment
      ManagedBy   = "terraform"
    }
  }
}
