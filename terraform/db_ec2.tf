# -----------------------------------------------------------------------------
# 자체 관리 MySQL 을 얹는 DB 전용 EC2 (#898) — RDS(rds.tf) 대체
#
# 왜 관리형을 버리는가: 신규 AWS Free Plan 이 RDS 의 backup_retention_period > 0 을 거부해
# 자동 백업·PITR 을 못 쓴다(rds.tf 참고). 관리형 비용을 내면서 관리형의 핵심 이점인 백업을
# 못 받는 상태라, 백업을 우리가 직접 통제하는 편이 낫다는 판단이다. 비용도 월 $20.87 →
# 약 $13 으로 내려간다.
#
# 이 파일은 rds.tf 와 대칭을 이루도록 DB 한 기능의 리소스(SG·인스턴스·백업 버킷·IAM)를 한곳에
# 모았다. 이관이 끝나 RDS 를 걷어낼 때 rds.tf 만 지우면 되고, 그때 무엇이 그 자리를 대신하는지
# 이 파일 하나로 드러난다.
#
# dev 는 이미 앱 박스 안 docker MySQL 로 돌고 있다(infra/scripts/provision-runtime.sh 3절).
# prod 는 앱 박스 여유 메모리가 563MB 뿐이고 앱 컨테이너가 캡에 100% 붙어 있어 동거가 불가능해,
# 별도 박스로 분리한다.
# -----------------------------------------------------------------------------

# -----------------------------------------------------------------------------
# DB 박스 Security Group
#
# 3306 은 앱 EC2 SG 에서만 받는다(rds.tf 의 aws_security_group.rds 와 같은 규율) — DB 를
# 인터넷에 직접 노출하지 않는다. SSH 는 EC2 Instance Connect·팀원 접속을 위해 열되
# var.ssh_ingress_cidr 로 좁힌다.
#
# egress 는 열어 둔다. RDS 와 달리 이 박스는 스스로 바깥에 나가야 한다 — SSM 에서 DB 자격증명을
# 읽고, 백업을 S3 에 올리고, docker 이미지를 받는다. 사설 서브넷 + NAT Gateway 로 가리는 선택지는
# NAT 만 월 $40 이 넘어 이 이관의 절감액을 통째로 삼킨다.
# -----------------------------------------------------------------------------
resource "aws_security_group" "db_ec2" {
  name        = "piki-prod-db-sg"
  description = "Allow MySQL from app EC2 SG only, SSH from team CIDR"
  vpc_id      = aws_vpc.main.id

  ingress {
    description     = "MySQL from app EC2"
    from_port       = 3306
    to_port         = 3306
    protocol        = "tcp"
    security_groups = [aws_security_group.ec2.id]
  }

  ingress {
    description = "SSH (EC2 Instance Connect / team)"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = [var.ssh_ingress_cidr]
  }

  egress {
    description = "Allow all outbound (SSM pull, S3 backup upload, docker pull)"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  # 앱 SG(aws_security_group.ec2)와 달리 ingress 에 ignore_changes 를 걸지 않는다.
  # 앱 SG 는 콘솔/CLI 로 추가된 접속 IP 가 이미 쌓여 있어 terraform 이 덮으면 배포·SSH 가 끊기지만,
  # 이 SG 는 방금 만들어 그런 이력이 없고 terraform 이 유일한 관리자다. 여기에 ignore_changes 를
  # 두면 누군가 콘솔에서 3306 을 열어도 apply 가 잡아내지 못한다 — DB 포트에서 그 사각은 크다.
  # (#223 이 기존 SG 의 ignore_changes 를 걷어내는 방향이라, 새 리소스가 역행할 이유도 없다.)

  tags = {
    Name = "piki-prod-db-sg"
  }
}

# -----------------------------------------------------------------------------
# DB 박스 IAM — 앱 role(aws_iam_role.app)을 공유하지 않고 전용으로 둔다.
#
# 앱 role 은 ECR pull·이미지 버킷 RW·/piki-core/* 전 경로 읽기를 갖는데, DB 박스에는 하나도
# 필요 없다. 다른 박스들이 "과권한이지만 blast radius 가 작다"는 트레이드오프로 role 을 공유하는
# 것과 달리, 이 박스는 데이터 원본과 그 백업을 함께 들고 있어 사고 반경이 가장 크다. 여기서만은
# 최소 권한을 지킨다.
# -----------------------------------------------------------------------------
resource "aws_iam_role" "db" {
  name               = "piki-prod-db-role"
  assume_role_policy = data.aws_iam_policy_document.ec2_assume.json
}

data "aws_iam_policy_document" "db_instance" {
  # DB 자격증명만 읽는다 — 앱 role 처럼 /piki-core/* 전체가 아니라 prod/db-* 로 좁힌다.
  # MySQL 컨테이너 최초 생성 시의 초기 자격증명과, 백업 스크립트의 접속 자격이 여기서 나온다.
  statement {
    sid       = "ReadDbCredentials"
    actions   = ["ssm:GetParameter", "ssm:GetParameters"]
    resources = ["arn:aws:ssm:${var.aws_region}:*:parameter/piki-core/prod/db-*"]
  }

  # 관측 수집기(alloy)가 쓰는 Grafana Cloud 자격 공유 경로 (#771).
  statement {
    sid       = "ReadObservabilityCredentials"
    actions   = ["ssm:GetParameter", "ssm:GetParameters"]
    resources = ["arn:aws:ssm:${var.aws_region}:*:parameter/piki/observability/*"]
  }

  # 백업 업로드(Put) + 복구를 위한 조회(Get/List).
  #
  # Get·List 를 함께 주는 이유: 복구는 급할 때 하는 일인데, 이게 없으면 사람이 로컬로 내려받아
  # 다시 박스로 올리는 우회를 타야 한다. 자기가 쓴 백업을 자기가 읽는 것이라 권한 확대 폭도 작다.
  # (ListBucket 없이 Get 만 주면 "어떤 백업이 있는지"를 못 봐서 복원할 파일을 고르지 못한다.)
  #
  # Delete 는 끝까지 주지 않는다 — 보존 만료는 S3 lifecycle 이 하고, 박스가 침해되더라도
  # 이미 올라간 백업은 지우지 못하게 남긴다. 이 버킷이 유일한 복구 경로이기 때문이다.
  statement {
    sid       = "WriteAndReadBackups"
    actions   = ["s3:PutObject", "s3:GetObject"]
    resources = ["${aws_s3_bucket.db_backup.arn}/*"]
  }

  # ListBucket 은 객체(/*)가 아니라 버킷 ARN 에 걸어야 한다(S3 API 사양, iam.tf 의 이미지 버킷과 동일).
  statement {
    sid       = "ListBackups"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.db_backup.arn]
  }
}

resource "aws_iam_role_policy" "db_instance" {
  name   = "piki-prod-db-policy"
  role   = aws_iam_role.db.id
  policy = data.aws_iam_policy_document.db_instance.json
}

resource "aws_iam_instance_profile" "db" {
  name = "piki-prod-db-profile"
  role = aws_iam_role.db.name
}

# -----------------------------------------------------------------------------
# 백업 버킷 — mysqldump | gzip 산출물을 날짜별로 쌓는다.
#
# 이미지 버킷(s3.tf)과 정반대로 공개를 전면 차단한다. 객체 이름이 날짜라 덮어쓸 일이 없어
# versioning 은 두지 않고, 보존은 lifecycle 만료로 끝낸다.
# -----------------------------------------------------------------------------
resource "aws_s3_bucket" "db_backup" {
  bucket = var.db_backup_bucket_name

  tags = {
    Name = "piki-prod-db-backup"
  }
}

resource "aws_s3_bucket_public_access_block" "db_backup" {
  bucket = aws_s3_bucket.db_backup.id

  block_public_acls       = true
  ignore_public_acls      = true
  block_public_policy     = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "db_backup" {
  bucket = aws_s3_bucket.db_backup.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "db_backup" {
  bucket = aws_s3_bucket.db_backup.id

  rule {
    id     = "expire-old-backups"
    status = "Enabled"

    filter {}

    expiration {
      days = var.db_backup_retention_days
    }

    # 업로드가 중간에 끊긴 멀티파트 조각이 과금 대상으로 남는 걸 막는다(이미지 버킷과 같은 규율).
    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}

# 백업 스크립트가 "어느 버킷에 올릴지"를 알아야 하는데, 버킷명은 계정번호를 포함해 퍼블릭 repo 의
# 스크립트에 박을 수 없다. 위 db_instance 정책이 이미 /piki-core/prod/db-* 를 읽게 하므로,
# 그 경로에 얹어 스크립트가 다른 자격증명과 같은 방식으로 꺼내 쓰게 한다(인자 전달 대비 자족적).
# 비밀이 아니라 String 으로 둔다.
resource "aws_ssm_parameter" "db_backup_bucket" {
  name        = "/piki-core/prod/db-backup-bucket"
  description = "MySQL 논리 백업 업로드 대상 버킷 (#898, infra/scripts/db-backup.sh 가 읽는다)"
  type        = "String"
  value       = aws_s3_bucket.db_backup.id

  tags = {
    Name = "piki-prod-db-backup-bucket"
  }
}

# -----------------------------------------------------------------------------
# DB 박스 인스턴스
#
# key_name 을 두지 않는다 — extractor·headless 박스와 같이 EC2 Instance Connect 로만 접근한다.
# 키페어 파일을 팀이 나눠 갖는 경로가 없어지고, 접근 권한이 IAM 하나로 모인다.
#
# EIP 를 붙이지 않고 associate_public_ip_address 로 자동 할당 IP 를 받는다. 요금은 EIP 와
# 동일($0.005/h)한데, 앱은 이 박스를 사설 IP 로만 보므로 재기동 시 퍼블릭 IP 가 바뀌어도 무관하다.
# (SSH 할 때만 현재 IP 를 조회하면 된다.)
# -----------------------------------------------------------------------------
resource "aws_instance" "db" {
  ami                         = var.ec2_ami_id != null ? var.ec2_ami_id : data.aws_ami.ubuntu_2404_arm64[0].id
  instance_type               = var.db_ec2_instance_type
  subnet_id                   = aws_subnet.public.id
  availability_zone           = var.azs[0]
  vpc_security_group_ids      = [aws_security_group.db_ec2.id]
  iam_instance_profile        = aws_iam_instance_profile.db.name
  associate_public_ip_address = true

  # 첫 부팅 부트스트랩은 docker 와 swap 까지만 한다. MySQL 기동·백업 cron 설치는
  # infra/scripts/provision-db.sh 가 멱등으로 맡는다 — user_data 는 첫 부팅에만 돌아
  # 스크립트를 고쳐도 반영되지 않고, 반영하려면 인스턴스 교체가 필요한데 이 박스는 교체가
  # 곧 데이터 손실이기 때문이다. 아래 lifecycle 이 그 교체를 막는다.
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

    # swap — 1GiB 박스라 완충이 필수다. extractor·headless 박스는 swap 이 없어 메모리가
    # 모자라면 곧장 OOM kill 인데, DB 에서 그 일이 나면 데이터 정합이 걸린다.
    # swappiness=10 은 앱 박스와 같은 값 — 완충으로만 쓰고 평시엔 RAM 에 머물게 한다.
    fallocate -l 2G /swapfile || dd if=/dev/zero of=/swapfile bs=1M count=2048
    chmod 600 /swapfile
    mkswap /swapfile
    swapon /swapfile
    echo '/swapfile none swap sw 0 0' >> /etc/fstab
    echo 'vm.swappiness=10' > /etc/sysctl.d/99-swappiness.conf
    sysctl -w vm.swappiness=10
  EOF

  metadata_options {
    http_endpoint = "enabled"
    http_tokens   = "required"
    # 2 — bridge 네트워크 컨테이너가 IMDS 에 닿아야 한다(앱 박스와 동일).
    http_put_response_hop_limit = 2
  }

  # AWS 층의 종료 보호. 아래 lifecycle 의 prevent_destroy 는 terraform 이 파괴 계획을 거부하게
  # 할 뿐이라, 콘솔·CLI·API 로 직접 종료하는 경로는 그대로 열려 있다. 두 층을 함께 둬야
  # "실수로 지워지지 않는다"가 성립한다. 의도적으로 종료할 때는 이 값을 false 로 바꿔 apply 한 뒤 진행한다.
  disable_api_termination = true

  root_block_device {
    volume_size = var.db_ec2_volume_size
    volume_type = "gp3"
    encrypted   = true
    # 이 박스의 루트 볼륨이 곧 DB 데이터다(docker named volume 이 여기 있다).
    # 인스턴스가 종료돼도 볼륨은 남긴다.
    #
    # 다만 이것은 자동 복구 장치가 아니다 — 남은 볼륨은 새 인스턴스에 자동으로 붙지 않는다.
    # 박스를 다시 만들면 새 루트 볼륨으로 뜨고, provision-db.sh 는 빈 MySQL 을 기동한다.
    # 즉 "데이터가 사라지지 않는다"까지가 이 설정의 보장이고, 되살리려면 사람이 옛 볼륨을
    # 연결하는 수동 절차가 필요하다.
    #
    # 그래서 주 복구 경로는 이 볼륨이 아니라 S3 백업(db-backup.sh)이다. 볼륨 보존은 백업까지
    # 실패했을 때 남는 마지막 회수 수단으로 둔다.
    delete_on_termination = false
  }

  # ami·user_data 변경은 terraform 이 인스턴스를 파괴·재생성하는 사유인데, 이 박스에서는
  # 그게 DB 소멸이다. 둘 다 무시해 사고 경로를 끊는다. AMI 를 의도적으로 올릴 때는
  # 새 박스를 띄워 덤프로 옮기는 절차를 밟는다(이 이관과 같은 방식).
  #
  # prevent_destroy 는 그 사고 경로의 마지막 층이다. ignore_changes 가 막는 것은 "속성 변경으로
  # 인한 재생성"뿐이라, destroy 를 직접 부르거나 다른 사유로 replace 가 잡히면 그대로 지워진다.
  # 이 플래그가 있으면 apply 가 거부되고, 정말 지워야 할 때는 코드에서 이 줄을 먼저 지워야 한다.
  # RDS 의 deletion_protection 과 같은 결의 의도된 마찰이다.
  lifecycle {
    prevent_destroy = true
    ignore_changes  = [ami, user_data]
  }

  tags = {
    Name = "piki-prod-db"
  }
}
