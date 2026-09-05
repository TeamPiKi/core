# -----------------------------------------------------------------------------
# Elastic IP — EC2 재시작 시에도 동일한 퍼블릭 IP 를 유지하기 위한 고정 IP.
#
# 2024-02 이후 AWS 는 모든 퍼블릭 IPv4 주소에 시간당 요금($0.005/h)을 부과한다.
# 연결된 EIP 든 auto-assign IP 든 요금은 동일하며, EIP 로 전환한다고 해서
# 추가 비용이 발생하지는 않는다. 신규 계정은 12개월간 퍼블릭 IPv4 프리티어 적용.
#
# domain = "vpc" 는 VPC 내부용 EIP 를 생성한다는 의미 (classic EC2 가 아님).
# -----------------------------------------------------------------------------
resource "aws_eip" "app" {
  domain = "vpc"

  depends_on = [aws_internet_gateway.main]

  tags = {
    Name = "piki-prod-app-eip"
  }
}

resource "aws_eip_association" "app" {
  instance_id   = aws_instance.app.id
  allocation_id = aws_eip.app.id
}

# -----------------------------------------------------------------------------
# 개발(dev) EC2 용 EIP — prod EIP 와 별도로 할당해 도메인 A 레코드를 분리한다.
# apply 후 표시되는 dev_ec2_public_ip 를 가비아(또는 DNS 콘솔)의
# dev.* 서브도메인 A 레코드에 수동으로 등록할 것.
# -----------------------------------------------------------------------------
resource "aws_eip" "dev_app" {
  domain = "vpc"

  depends_on = [aws_internet_gateway.main]

  tags = {
    Name = "piki-dev-app-eip"
  }
}

resource "aws_eip_association" "dev_app" {
  instance_id   = aws_instance.dev_app.id
  allocation_id = aws_eip.dev_app.id
}

