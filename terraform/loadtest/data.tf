# -----------------------------------------------------------------------------
# 메인 스택이 소유한 리소스 — 읽기만 한다.
#
# 이 스택은 네트워크·IAM 을 만들지 않는다. 만들면 부하테스트가 "prod 와 다른 네트워크"에서
# 도는 셈이라 미러가 깨지고, 무엇보다 메인 소유 리소스를 두 state 가 다투게 된다.
# data source 는 조회일 뿐이라 메인 state 에 아무 영향이 없다.
# -----------------------------------------------------------------------------
data "aws_vpc" "main" {
  filter {
    name   = "tag:Name"
    values = [var.vpc_name_tag]
  }
}

data "aws_subnet" "public" {
  filter {
    name   = "tag:Name"
    values = [var.public_subnet_name_tag]
  }
}

data "aws_iam_instance_profile" "app" {
  name = var.app_instance_profile_name
}
