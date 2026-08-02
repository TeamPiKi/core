variable "project" {
  description = "프로젝트 식별자 (태그/리소스 이름 접두사)"
  type        = string
  default     = "team3"
}

variable "environment" {
  description = "배포 환경 (dev / stg / prod)"
  type        = string
  default     = "dev"
}

variable "aws_region" {
  description = "AWS 리전"
  type        = string
  default     = "ap-northeast-2"
}

variable "image_bucket_name" {
  # 계정번호를 포함하므로 default 를 두지 않는다(퍼블릭 repo). TF_VAR_image_bucket_name 또는 terraform.tfvars 로 주입.
  description = "크롭 상품 이미지 저장 버킷명. state 버킷(piki-tfstate-*)과 일관되게 piki-images-{account} 사용."
  type        = string

  # placeholder(<ACCOUNT_ID>)·형식 오류가 plan/apply 까지 가면 엉뚱한 버킷 생성·교체를 유발하므로 plan 시점에 차단
  validation {
    condition     = can(regex("^piki-images-[0-9]{12}$", var.image_bucket_name))
    error_message = "image_bucket_name 은 piki-images-<12자리 AWS 계정번호> 형식이어야 합니다."
  }
}

variable "vpc_cidr" {
  description = "VPC CIDR 블록"
  type        = string
  default     = "10.0.0.0/16"
}

variable "public_subnet_cidr" {
  description = "EC2 가 위치할 퍼블릭 서브넷 CIDR"
  type        = string
  default     = "10.0.1.0/24"
}

variable "private_subnet_cidrs" {
  description = "RDS DB Subnet Group 용 프라이빗 서브넷 CIDR 목록 (최소 2개 AZ 필요)"
  type        = list(string)
  default     = ["10.0.11.0/24", "10.0.12.0/24"]
}

variable "azs" {
  description = "사용할 가용영역 목록 (private subnet 과 1:1 매핑)"
  type        = list(string)
  default     = ["ap-northeast-2a", "ap-northeast-2c"]
}

# ----- EC2 -----

variable "ec2_instance_type_dev" {
  description = <<-EOT
    개발(dev) EC2 인스턴스 타입 (ARM / Graviton). micro(1GiB)로는 앱·mysql·redis·alloy 를 얹으면
    만석이 된다(2026-06-06 freeze 전력). small(2GiB)로 올린다.
    instance_type 변경은 in-place(stop → 변경 → start)이며 AMI 고정이라 인스턴스 교체 없이 적용되지만, 짧은 다운타임이 있다.
  EOT
  type        = string
  default     = "t4g.small"
}

variable "ec2_instance_type_prod" {
  description = <<-EOT
    운영(prod) EC2 인스턴스 타입 (ARM / Graviton). dev 와 분리해 prod 만 독립적으로
    사이징한다 — 운영 트래픽/메모리 여유를 위해 small 로 상향.
    micro(1GiB) → small(2GiB). instance_type 변경은 in-place(stop → 변경 → start)이며
    AMI 가 고정돼 있어 인스턴스 교체 없이 적용되지만, 적용 중 짧은 다운타임이 발생한다.
  EOT
  type        = string
  default     = "t4g.small"
}

variable "ec2_ami_id" {
  description = <<-EOT
    EC2 에 사용할 고정 AMI ID. 값을 지정하면 data.aws_ami 조회 대신 이 ID 를 그대로 사용한다.
    null 이면 data source 로 최신 Ubuntu 24.04 arm64 AMI 를 조회하지만,
    새 AMI 가 공개될 때마다 apply 시점에 인스턴스가 교체될 수 있으므로
    운영 단계에서는 반드시 조회된 AMI ID 를 이 변수로 고정할 것.
  EOT
  type        = string
  # 현재 운영 인스턴스(i-019866a91d6822a4c)의 AMI 로 고정 — null 로 두면 apply 시 최신 AMI 로
  # 교체돼 인스턴스가 파괴+재생성된다. 인스턴스를 의도적으로 교체할 때만 새 AMI 로 바꾼다.
  default = "ami-0f5ddb19e2fbe4cc4"
}

variable "ssh_ingress_cidr" {
  description = "SSH 접속을 허용할 CIDR. 실제로는 본인/팀원 공인 IP/32 로 좁힐 것"
  type        = string
  # TODO: 팀원 IP 확보 후 terraform.tfvars 에서 실제 값으로 override 할 것.
  # 아래는 RFC 5737 문서화용 예약 대역(TEST-NET-3) 의 placeholder 이며 실제 라우팅되지 않음.
  default = "203.0.113.1/32"
}

# ----- RDS -----

variable "db_instance_class" {
  description = "RDS 인스턴스 클래스"
  type        = string
  default     = "db.t4g.micro"
}

variable "db_engine_version" {
  description = <<-EOT
    MySQL 엔진 버전. 8.4 는 Innovation Release 라인이므로 AWS RDS 의
    표준 지원 종료일을 주기적으로 확인하고 수명주기가 충분히 남은 마이너 버전으로 유지할 것.
    참고: https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/MySQL.Concepts.VersionMgmt.html
  EOT
  type        = string
  # 8.4.3 은 2026-05-31 에 RDS 표준 지원 종료 예정이므로 8.4.8 (~2027-02-03) 로 승격
  default = "8.4.8"
}

variable "db_name" {
  description = "초기 생성할 데이터베이스 이름"
  type        = string
  default     = "team3"
}

variable "db_username" {
  description = "RDS 마스터 사용자 이름"
  type        = string
  default     = "admin"
}

variable "db_allocated_storage" {
  description = "RDS 초기 스토리지 (GB)"
  type        = number
  default     = 20
}
