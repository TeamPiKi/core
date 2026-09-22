# -----------------------------------------------------------------------------
# Ubuntu 24.04 LTS (Noble Numbat) arm64 최신 AMI 조회
#
# t4g.* 는 Graviton(ARM) 이므로 반드시 arm64 AMI 를 써야 한다.
# owner 099720109477 은 Canonical 공식 계정.
#
# 이 data source 는 var.ec2_ami_id 가 null 일 때만 실행된다. 새 AMI 가 공개될
# 때마다 apply 시점에 인스턴스가 교체될 수 있으므로 부트스트랩 용도로만 사용하고,
# 최초 apply 이후에는 resolved 된 AMI ID 를 var.ec2_ami_id 로 고정해 교체를 막을 것.
#
# 또한 실제 AWS API 호출이 필요하므로 mock provider 로는 plan 시점에 실패한다.
# 계정 연결 후에만 활성화할 것.
# -----------------------------------------------------------------------------
data "aws_ami" "ubuntu_2404_arm64" {
  count       = var.ec2_ami_id == null ? 1 : 0
  most_recent = true
  owners      = ["099720109477"] # Canonical

  filter {
    name   = "name"
    values = ["ubuntu/images/hvm-ssd-gp3/ubuntu-noble-24.04-arm64-server-*"]
  }

  filter {
    name   = "architecture"
    values = ["arm64"]
  }

  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  }
}

# -----------------------------------------------------------------------------
# 운영(prod) EC2 — 기존 인스턴스. Name 태그만 prod 로 명시.
# var.environment 기본값(dev)이 바뀌어도 이 태그는 영향 받지 않도록 하드코딩.
locals {
  # 앱 박스 첫 부팅 부트스트랩 — 빈 우분투에 배포가 요구하는 base 소프트웨어(docker·nginx·certbot)를 깐다.
  # dev·prod 가 같은 것을 쓴다. 구 계정 prod 박스는 사람이 손으로 깔아 terraform 이 그 사실을 모르는데,
  # 계정 이관으로 재생성하면 빈 우분투가 되어 provision 이 "docker: command not found" 로 죽는다(실측).
  # cert 발급은 여기서 하지 않는다 — 부팅 시점엔 EIP 연결·DNS 전파가 끝났다는 보장이 없어 HTTP-01 이
  # 실패할 수 있다. deploy.yml 의 idempotent "ensure cert" 스텝이 인스턴스가 확실히 닿을 때 발급한다.
  app_bootstrap_user_data = <<-EOF
    #!/bin/bash
    set -eux
    export DEBIAN_FRONTEND=noninteractive
    apt-get update
    apt-get install -y ca-certificates curl nginx certbot python3-certbot-nginx
    # Docker 공식 저장소 (arm64/t4g)
    install -m 0755 -d /etc/apt/keyrings
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
    chmod a+r /etc/apt/keyrings/docker.asc
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" > /etc/apt/sources.list.d/docker.list
    apt-get update
    apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
    usermod -aG docker ubuntu
    systemctl enable --now docker
    systemctl enable --now nginx
  EOF
}

# -----------------------------------------------------------------------------
resource "aws_instance" "app" {
  ami                    = var.ec2_ami_id != null ? var.ec2_ami_id : data.aws_ami.ubuntu_2404_arm64[0].id
  instance_type          = var.ec2_instance_type_prod
  subnet_id              = aws_subnet.public.id
  availability_zone      = var.azs[0]
  vpc_security_group_ids = [aws_security_group.ec2.id]
  key_name               = "team3-SE-1"
  iam_instance_profile   = aws_iam_instance_profile.app.name

  # dev 와 같은 부트스트랩을 쓴다. 이 박스는 원래 사람이 손으로 docker·nginx 를 깔아 terraform 이
  # 그 사실을 모르는 상태였는데, 계정 이관으로 재생성되는 순간 그 수작업이 사라진다(실측 —
  # provision 이 "docker: command not found" 로 중단). user_data 는 첫 부팅에만 도므로 이미 떠 있는
  # 인스턴스에는 영향이 없고, 재생성될 때만 적용된다.
  user_data = local.app_bootstrap_user_data

  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 2
  }

  root_block_device {
    # 크기 근거는 TeamPiKi/infra#94. 배포 게이트가 요구하는 여유 10GB + 박스 실사용 최대.
    volume_size           = 25
    volume_type           = "gp3"
    encrypted             = true
    delete_on_termination = true
  }

  tags = {
    Name = "piki-prod-app"
  }
}

# -----------------------------------------------------------------------------
# 개발(dev) EC2 — 신규 인스턴스.
# 동일 VPC/서브넷/SG 를 공유하며, 별도 EIP 와 도메인(dev.*)으로 분리.
# -----------------------------------------------------------------------------
resource "aws_instance" "dev_app" {
  ami                    = var.ec2_ami_id != null ? var.ec2_ami_id : data.aws_ami.ubuntu_2404_arm64[0].id
  instance_type          = var.ec2_instance_type_dev
  subnet_id              = aws_subnet.public.id
  availability_zone      = var.azs[0]
  vpc_security_group_ids = [aws_security_group.ec2.id]
  # dev 전용 키페어 — prod(team3-SE-1)와 분리해 dev 키 유출이 prod 접근으로 번지지 않게 한다.
  # AWS 콘솔에서 미리 생성(.pem 다운로드)해 두고 이름으로 참조. 개인키는 dev env EC2_SSH_KEY 로 주입.
  key_name             = "team3-dev-SE-1"
  iam_instance_profile = aws_iam_instance_profile.app.name

  # 부트스트랩은 prod 와 같은 것을 쓴다(위 locals). 첫 부팅에만 실행되므로 이미 떠 있는 인스턴스에
  # 적용하려면 `terraform apply -replace=aws_instance.dev_app` 가 필요하다.
  user_data = local.app_bootstrap_user_data

  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 2
  }

  root_block_device {
    # 크기 근거는 TeamPiKi/infra#94. 배포 게이트가 요구하는 여유 10GB + 박스 실사용 최대.
    volume_size           = 25
    volume_type           = "gp3"
    encrypted             = true
    delete_on_termination = true
  }

  tags = {
    Name = "piki-dev-app"
  }
}

