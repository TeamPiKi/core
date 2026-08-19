# -----------------------------------------------------------------------------
# 부하테스트 스택 (#911) — 앱 박스 1 + DB 박스 1, 둘 다 prod 미러
#
# 왜 dev 박스를 안 쓰고 새로 세우나:
#   - dev 앱 박스는 mysql 이 동거해 prod 앱 박스와 조건이 다르다. 전용 박스면 동거가 없어
#     "동거분을 감수하고 보수적 하한으로 읽는다"는 타협 자체가 사라진다.
#   - dev 를 안 건드리니 SSM db-host 스왑·env secret override·박스 nginx sed 같은 배선이
#     전부 불필요해진다. 배선이 없으면 배선 사고도 없다(이전 시도에서 nginx reload 조용한
#     실패로 dev 가 502 전멸한 전력).
#   - 원복이 destroy 하나다. dev 재배포를 기다릴 필요가 없다.
#
# 환경 이름은 loadtest 다. deploy.yml 이 이 브랜치를 environment=loadtest 로 resolve 하고,
# provision-runtime.sh 는 ENVIRONMENT=dev 일 때만 mysql 을 띄우므로 앱 박스에 mysql 이 안 뜬다
# (= prod 앱 박스와 같은 구성). Spring 프로파일은 dev 라 /api/v1/dev/** 토큰 발급이 살아 있어
# k6 가 시나리오를 돌릴 수 있다.
# -----------------------------------------------------------------------------

# -----------------------------------------------------------------------------
# 앱 박스 SG — dev/prod 앱 SG(team3-dev-ec2-sg)와 같은 표면(22·80·443).
#
# 22 를 0.0.0.0/0 으로 두는 이유: 배포가 GitHub Actions 러너에서 SSH 로 들어온다
# (appleboy/ssh-action). 러너 공인 IP 는 고정이 아니라 CIDR 로 좁힐 수 없다. 운영 중인
# team3-dev-ec2-sg 도 실제로 22 가 0.0.0.0/0 이다(코드의 ssh_ingress_cidr 과 drift 상태 —
# 별건이지만 메인 apply 시 그 값이 반영되면 dev/prod 배포가 끊긴다는 뜻이라 확인이 필요하다).
# 키 인증만 허용되고 비밀번호 인증은 꺼져 있어 노출 표면은 키 자체다.
# -----------------------------------------------------------------------------
resource "aws_security_group" "loadtest_app" {
  name        = "piki-loadtest-app-sg"
  description = "Loadtest app box - SSH from anywhere (GitHub Actions deploy), HTTP/HTTPS public"
  vpc_id      = data.aws_vpc.main.id

  ingress {
    description = "SSH (GitHub Actions deploy runner - IP not fixed)"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "HTTP (certbot HTTP-01 challenge + redirect to HTTPS)"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "HTTPS (k6 target)"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    description = "Allow all outbound (docker pull, ECR, SSM, S3)"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "piki-loadtest-app-sg"
  }
}

# -----------------------------------------------------------------------------
# DB 박스 SG — prod DB 박스와 같은 규율: 3306 은 앱 SG 에서만, 인터넷 직접 노출 금지.
# 8090(extractor stub)도 앱 SG 에서만 — 앱의 EXTRACT_REMOTE_BASE_URL 대상이다.
# -----------------------------------------------------------------------------
resource "aws_security_group" "loadtest_db" {
  name        = "piki-loadtest-db-sg"
  description = "Loadtest MySQL and extractor stub - allow 3306/8090 from loadtest app SG only, SSH from team CIDR"
  vpc_id      = data.aws_vpc.main.id

  ingress {
    description     = "MySQL from loadtest app box"
    from_port       = 3306
    to_port         = 3306
    protocol        = "tcp"
    security_groups = [aws_security_group.loadtest_app.id]
  }

  ingress {
    description     = "extractor stub from loadtest app box (EXTRACT_REMOTE_BASE_URL target)"
    from_port       = 8090
    to_port         = 8090
    protocol        = "tcp"
    security_groups = [aws_security_group.loadtest_app.id]
  }

  ingress {
    description = "SSH (EC2 Instance Connect / team)"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = [var.ssh_ingress_cidr]
  }

  egress {
    description = "Allow all outbound (docker pull)"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "piki-loadtest-db-sg"
  }
}

# -----------------------------------------------------------------------------
# 앱 박스 — prod 앱 박스(team3-prod-app)와 같은 타입·볼륨. 부트스트랩은 dev_app 과 동일하게
# docker·nginx·certbot 까지만 깔고, 그 위의 런타임(swap·redis·alloy·앱 컨테이너)은
# deploy.yml 의 provision-runtime.sh 와 blue-green 이 맡는다.
#
# cert 는 여기서 발급하지 않는다 — 부팅 시점엔 EIP 연결·DNS 전파가 끝났다는 보장이 없어
# HTTP-01 이 실패한다. deploy.yml 의 idempotent "Ensure TLS cert" 스텝이 발급한다.
# -----------------------------------------------------------------------------
resource "aws_instance" "loadtest_app" {
  ami                    = var.ec2_ami_id
  instance_type          = var.app_instance_type
  subnet_id              = data.aws_subnet.public.id
  vpc_security_group_ids = [aws_security_group.loadtest_app.id]
  key_name               = var.ssh_key_name
  iam_instance_profile   = data.aws_iam_instance_profile.app.name

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
    # prod 앱 박스와 동일한 20GB (앱 이미지·로그 여유).
    volume_size           = 20
    volume_type           = "gp3"
    encrypted             = true
    delete_on_termination = true
  }

  tags = {
    Name = "piki-loadtest-app"
  }
}

# -----------------------------------------------------------------------------
# 앱 박스 EIP — lt.api.piki.day A 레코드 대상.
#
# 도메인이 필요한 이유: deploy.yml 의 nginx·certbot 스텝이 $DOMAIN 으로 cert 경로와
# sites-enabled 이름을 정한다. DNS 는 가비아에서 수동 관리(Route53 hosted zone 없음)라
# terraform 이 A 레코드를 만들 수 없다 — apply 후 이 IP 를 사람이 등록해야 한다.
# -----------------------------------------------------------------------------
resource "aws_eip" "loadtest_app" {
  domain = "vpc"

  tags = {
    Name = "piki-loadtest-app-eip"
  }
}

resource "aws_eip_association" "loadtest_app" {
  instance_id   = aws_instance.loadtest_app.id
  allocation_id = aws_eip.loadtest_app.id
}

# -----------------------------------------------------------------------------
# DB 박스 — prod DB 박스(piki-prod-db) 완전 미러 + extractor stub 동거(8090).
#
# stub 을 여기 얹는 이유: extractor 는 prod 공유 박스에 파싱 스케줄러를 끄는 스위치가 없어
# 격리 수단이 주소 돌리기뿐인데, 죽은 주소는 배포 가드가 막는다. health 200 을 주는 stub 이
# 필요하고, 앱이 사설망으로 닿아야 하므로 로컬 머신에는 못 둔다. sleep 위주의 초경량
# 서버(메모리 캡 128m)라 DB 측정에 대한 간섭은 무시 수준이다.
#
# db_ec2.tf(piki-prod-db)와 의도적으로 다른 점 — 이 박스는 하루짜리 소모품이다:
#   - prevent_destroy·disable_api_termination 없음, 루트 볼륨 delete_on_termination = true
#   - 백업 버킷·cron 없음 (데이터가 합성 시드라 소실이 손해가 아니다)
#   - IAM instance profile 없음 (SSM·S3 접근이 필요 없다 — 최소 권한의 극단)
# 그 외(인스턴스 타입·볼륨 10GB·swap·MySQL 기동 인자)는 전부 prod DB 박스와 동일하다.
# credit 설정도 미러(미지정 = 계정 기본값) — 지속 부하에서 크레딧이 소진되면 그것까지가
# prod 의 실력이다.
# -----------------------------------------------------------------------------
resource "aws_instance" "loadtest_db" {
  ami                         = var.ec2_ami_id
  instance_type               = var.db_instance_type
  subnet_id                   = data.aws_subnet.public.id
  vpc_security_group_ids      = [aws_security_group.loadtest_db.id]
  associate_public_ip_address = true

  user_data = <<-EOF
    #!/bin/bash
    set -eux
    export DEBIAN_FRONTEND=noninteractive
    apt-get update
    apt-get install -y ca-certificates curl
    install -m 0755 -d /etc/apt/keyrings
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
    chmod a+r /etc/apt/keyrings/docker.asc
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" > /etc/apt/sources.list.d/docker.list
    apt-get update
    apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
    usermod -aG docker ubuntu
    systemctl enable --now docker

    # swap — prod DB 박스(db_ec2.tf)와 동일. micro(1GiB) 미러에서 완충이 필수다.
    fallocate -l 2G /swapfile || dd if=/dev/zero of=/swapfile bs=1M count=2048
    chmod 600 /swapfile
    mkswap /swapfile
    swapon /swapfile
    echo '/swapfile none swap sw 0 0' >> /etc/fstab
    echo 'vm.swappiness=10' > /etc/sysctl.d/99-swappiness.conf
    sysctl -w vm.swappiness=10
  EOF

  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 2
  }

  root_block_device {
    # prod DB 박스와 동일한 10GB — 시드 데이터(약 수백 MB)를 넣어도 절반 이상 남는다.
    volume_size           = 10
    volume_type           = "gp3"
    encrypted             = true
    delete_on_termination = true
  }

  tags = {
    Name = "piki-loadtest-db"
  }
}
