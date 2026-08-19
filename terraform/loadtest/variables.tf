variable "aws_region" {
  description = "AWS 리전"
  type        = string
  default     = "ap-northeast-2"
}

variable "ec2_ami_id" {
  description = <<-EOT
    앱·DB 박스에 쓸 AMI. 메인 terraform/variables.tf 의 ec2_ami_id 와 같은 값을 기본으로 둔다 —
    prod 앱 박스와 같은 OS 이미지여야 미러가 성립한다.
  EOT
  type        = string
  default     = "ami-0f5ddb19e2fbe4cc4"
}

variable "app_instance_type" {
  description = <<-EOT
    부하테스트 앱 박스 타입. prod 앱 박스(team3-prod-app)와 동일하게 t4g.small.
    측정한 한계가 "prod 구성 그대로의 한계"여야 결과가 prod 로 이전된다.
  EOT
  type        = string
  default     = "t4g.small"
}

variable "db_instance_type" {
  description = <<-EOT
    부하테스트 DB 박스 타입. prod DB 박스(piki-prod-db)와 동일하게 t4g.micro.
    MySQL 기동 인자(메모리 캡 384m·버퍼풀 64M·max-connections 60)도 prod provision-db.sh 와
    같게 맞춘다(loadtest/provision-loadtest-db.sh).
  EOT
  type        = string
  default     = "t4g.micro"
}

variable "ssh_key_name" {
  description = <<-EOT
    앱 박스에 붙일 기존 키페어 이름. GitHub Actions 배포(appleboy/ssh-action)가 이 키의 개인키를
    loadtest environment 의 EC2_SSH_KEY secret 으로 받아 접속한다. dev 키페어를 재사용하면
    사용자가 이미 가진 개인키를 그대로 쓸 수 있어 새 키 발급·배포가 필요 없다.

    DB 박스는 키페어 없이 EC2 Instance Connect 로 붙는다(키 푸시 60초 유효) — 소모품이라
    영구 키를 붙일 이유가 없다.
  EOT
  type        = string
  default     = "team3-dev-SE-1"
}

variable "vpc_name_tag" {
  description = "메인 스택이 소유한 VPC 의 Name 태그. data source 로 읽기만 한다."
  type        = string
  default     = "team3-dev-vpc"
}

variable "public_subnet_name_tag" {
  description = "메인 스택이 소유한 퍼블릭 서브넷의 Name 태그. 앱·DB 박스가 여기 들어간다."
  type        = string
  default     = "team3-dev-public-ap-northeast-2a"
}

variable "app_instance_profile_name" {
  description = <<-EOT
    앱 박스에 붙일 기존 instance profile. SSM(/piki-core/*) 읽기·ECR pull·이미지 버킷 권한이
    이미 들어 있고 정책 범위가 piki-core/* 전체라 loadtest 경로도 그대로 커버한다.
    새로 만들지 않고 재사용한다 — 권한이 같아야 앱이 dev·prod 와 같은 방식으로 뜬다.
  EOT
  type        = string
  default     = "team3-dev-app-profile"
}

variable "ssh_ingress_cidr" {
  description = <<-EOT
    DB 박스 SSH(22) 를 허용할 CIDR. 사람이 EC2 Instance Connect 로 붙는 경로다 —
    본인 공인 IP/32 로 좁혀 terraform.tfvars 에 둔다(gitignore 대상).

    앱 박스 22 는 이 값을 쓰지 않는다(아래 main.tf 주석 참고) — GitHub Actions 러너가
    붙어야 하는데 러너 IP 가 고정이 아니라서다.
  EOT
  type        = string
  # RFC 5737 TEST-NET-3 placeholder — 실제 라우팅되지 않는다. tfvars 로 override 할 것.
  default = "203.0.113.1/32"
}
