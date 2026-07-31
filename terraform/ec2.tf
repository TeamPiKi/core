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
# -----------------------------------------------------------------------------
resource "aws_instance" "app" {
  ami                    = var.ec2_ami_id != null ? var.ec2_ami_id : data.aws_ami.ubuntu_2404_arm64[0].id
  instance_type          = var.ec2_instance_type_prod
  subnet_id              = aws_subnet.public.id
  availability_zone      = var.azs[0]
  vpc_security_group_ids = [aws_security_group.ec2.id]
  key_name               = "team3-SE-1"
  iam_instance_profile   = aws_iam_instance_profile.app.name

  # cloud-init 부트스트랩 — dev_app · staging_app 과 동일하다.
  # 원래 prod 는 "과거 수동 설치한 인스턴스" 라 이 블록이 없었는데, 그 전제가 계정 이전(#808)에서 깨졌다.
  # 새 계정에 apply 하니 nginx · docker · certbot 이 하나도 없는 빈 우분투가 떴고, 컷오버 창에서 배포가
  # certbot → nginx 단계에서 연달아 실패했다(수동 설치로 복구). 인스턴스를 재생성하는 어떤 상황에서도
  # 같은 일이 반복되지 않도록 세 EC2 의 부트스트랩을 동일하게 맞춘다.
  #
  # cert 발급은 여기서 하지 않는다(부팅 시점엔 EIP 연결·DNS 전파 보장이 없어 HTTP-01 이 실패할 수 있음).
  # deploy.yml 의 idempotent "ensure cert" 스텝이 인스턴스가 확실히 닿을 때 발급한다.
  # user_data 는 첫 부팅에만 실행되므로, 이미 부팅된 인스턴스엔 `terraform apply -replace=aws_instance.app` 로 적용한다
  # (운영 중 실행하면 인스턴스가 교체되어 다운타임이 발생하므로 창을 잡고 할 것).
  user_data = <<-EOF
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

  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 2
  }

  root_block_device {
    volume_size           = 20
    volume_type           = "gp3"
    encrypted             = true
    delete_on_termination = true
  }

  tags = {
    Name = "team3-prod-app"
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

  # cloud-init 부트스트랩 — 빈 우분투에 배포에 필요한 base 소프트웨어(docker·nginx·certbot)를
  # 첫 부팅 때 자동 설치한다. prod(aws_instance.app) · staging 도 동일한 블록을 갖는다
  # (계정 이전 #808 에서 prod 만 이 블록이 없어 빈 박스가 떴던 사고 이후 세 EC2 를 정렬했다).
  # cert 발급은 여기 안 함(부팅 시점엔 EIP 연결·DNS 전파가 끝났다는 보장이 없어 HTTP-01 이 실패할 수 있음).
  # cert 는 deploy.yml 의 idempotent "ensure cert" 스텝이 인스턴스가 확실히 닿을 때 발급한다.
  # user_data 는 첫 부팅에만 실행되므로, 기존(이미 부팅된) 인스턴스엔 `terraform apply -replace=aws_instance.dev_app` 로 적용한다.
  user_data = <<-EOF
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

  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 2
  }

  root_block_device {
    volume_size           = 20
    volume_type           = "gp3"
    encrypted             = true
    delete_on_termination = true
  }

  tags = {
    Name = "team3-dev-app"
  }
}

# -----------------------------------------------------------------------------
# 스테이징(staging) EC2 — dev 와 동일 경량 구성(로컬 MySQL/Redis), prod 프로파일로 동작.
# 동일 VPC/서브넷/SG/instance role 공유. 별도 EIP·도메인(staging.api.piki.day).
# 신규 인스턴스라 cloud-init 로 docker·nginx·certbot 설치(dev_app 과 동일).
# user_data 는 첫 부팅에만 실행되므로, 기존 인스턴스엔 -replace 로 적용한다.
# -----------------------------------------------------------------------------
resource "aws_instance" "staging_app" {
  ami                    = var.ec2_ami_id != null ? var.ec2_ami_id : data.aws_ami.ubuntu_2404_arm64[0].id
  instance_type          = var.ec2_instance_type
  subnet_id              = aws_subnet.public.id
  availability_zone      = var.azs[0]
  vpc_security_group_ids = [aws_security_group.ec2.id]
  # staging 전용 키페어 — AWS 콘솔에서 미리 생성(.pem). 개인키는 staging env EC2_SSH_KEY 로 주입.
  key_name             = "team3-staging-SE-1"
  iam_instance_profile = aws_iam_instance_profile.app.name

  user_data = <<-EOF
    #!/bin/bash
    set -eux
    export DEBIAN_FRONTEND=noninteractive
    apt-get update
    apt-get install -y ca-certificates curl nginx certbot python3-certbot-nginx
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

  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 2
  }

  root_block_device {
    volume_size           = 20
    volume_type           = "gp3"
    encrypted             = true
    delete_on_termination = true
  }

  tags = {
    Name = "team3-staging-app"
  }
}
