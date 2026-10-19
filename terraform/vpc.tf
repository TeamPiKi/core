locals {
  name_prefix = "${var.project}-${var.environment}"
}

# -----------------------------------------------------------------------------
# VPC
# -----------------------------------------------------------------------------
resource "aws_vpc" "main" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = {
    Name = "${local.name_prefix}-vpc"
  }
}

# -----------------------------------------------------------------------------
# Internet Gateway (퍼블릭 서브넷의 EC2 가 인터넷으로 나가는 통로)
# -----------------------------------------------------------------------------
resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id

  tags = {
    Name = "${local.name_prefix}-igw"
  }
}

# -----------------------------------------------------------------------------
# Public Subnet (EC2 전용)
# -----------------------------------------------------------------------------
resource "aws_subnet" "public" {
  vpc_id            = aws_vpc.main.id
  cidr_block        = var.public_subnet_cidr
  availability_zone = var.azs[0]

  # EIP 를 별도로 부여하므로 auto-assign 을 끈다.
  # 켜두면 EIP 와 자동 할당 IP 가 중복으로 생성되어 이중 과금 위험.
  map_public_ip_on_launch = false

  tags = {
    Name = "${local.name_prefix}-public-${var.azs[0]}"
    Tier = "public"
  }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }

  tags = {
    Name = "${local.name_prefix}-public-rt"
  }
}

resource "aws_route_table_association" "public" {
  subnet_id      = aws_subnet.public.id
  route_table_id = aws_route_table.public.id
}

# -----------------------------------------------------------------------------
# Private Subnets (RDS 전용, 최소 2개 AZ)
#
# 이 서브넷의 유일한 리소스는 RDS 이며 RDS 는 외부 인터넷 접근이 필요 없다.
# (OS 패치·백업은 모두 AWS 내부망 경유) 따라서 NAT Gateway 를 둘 이유가 없다.
# -----------------------------------------------------------------------------
resource "aws_subnet" "private" {
  count             = length(var.private_subnet_cidrs)
  vpc_id            = aws_vpc.main.id
  cidr_block        = var.private_subnet_cidrs[count.index]
  availability_zone = var.azs[count.index]

  tags = {
    Name = "${local.name_prefix}-private-${var.azs[count.index]}"
    Tier = "private"
  }
}

resource "aws_route_table" "private" {
  vpc_id = aws_vpc.main.id

  tags = {
    Name = "${local.name_prefix}-private-rt"
  }
}

resource "aws_route_table_association" "private" {
  # length(aws_subnet.private) 대신 그 subnet 을 만드는 것과 같은 변수를 센다. 전자는 아직 만들어지지
  # 않은 리소스를 참조해, apply 전 단계(terraform import 등)가 "count depends on resource attributes
  # that cannot be determined until apply" 로 통째로 실패한다(빈 계정 이관에서 실측).
  # 개수는 동일하다 — aws_subnet.private 자신이 count = length(var.private_subnet_cidrs) 다.
  count          = length(var.private_subnet_cidrs)
  subnet_id      = aws_subnet.private[count.index].id
  route_table_id = aws_route_table.private.id
}
