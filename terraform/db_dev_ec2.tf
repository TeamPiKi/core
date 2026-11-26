# dev DB 서버. 운영 DB 서버(db_ec2.tf)와 같은 부트스트랩·같은 compose 로 MySQL 과 Redis 를 띄운다(#1321).
# 운영과 다른 점은 백업 버킷과 종료 보호가 없다는 것뿐이다. dev 데이터는 다시 만들 수 있다.

resource "aws_security_group" "dev_db" {
  name        = "piki-dev-db-sg"
  description = "dev DB server: MySQL and Redis from dev app, Redis from extractor"
  vpc_id      = aws_vpc.main.id

  ingress {
    description     = "MySQL from dev app EC2"
    from_port       = 3306
    to_port         = 3306
    protocol        = "tcp"
    security_groups = [aws_security_group.dev_app.id]
  }

  ingress {
    description     = "Redis from dev app EC2"
    from_port       = 6379
    to_port         = 6379
    protocol        = "tcp"
    security_groups = [aws_security_group.dev_app.id]
  }

  # extractor 서버는 환경마다 따로 두지 않아 한 대가 운영과 dev 의 Redis 를 둘 다 읽는다.
  ingress {
    description     = "Redis from extractor (restricted accounts)"
    from_port       = 6379
    to_port         = 6379
    protocol        = "tcp"
    security_groups = [data.aws_security_group.extractor.id]
  }

  egress {
    description = "Allow all outbound (SSM pull, docker pull)"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "piki-dev-db-sg"
  }
}

resource "aws_iam_role" "dev_db" {
  name               = "piki-dev-db-role"
  assume_role_policy = data.aws_iam_policy_document.ec2_assume.json
}

data "aws_iam_policy_document" "dev_db_instance" {
  statement {
    sid       = "ReadDbCredentials"
    actions   = ["ssm:GetParameter", "ssm:GetParameters"]
    resources = ["arn:aws:ssm:${var.aws_region}:*:parameter/piki-core/dev/db-*"]
  }

  statement {
    sid       = "ReadRedisPassword"
    actions   = ["ssm:GetParameter"]
    resources = ["arn:aws:ssm:${var.aws_region}:*:parameter/piki-core/dev/redis-password"]
  }

  statement {
    sid       = "ReadObservabilityCredentials"
    actions   = ["ssm:GetParameter", "ssm:GetParameters"]
    resources = ["arn:aws:ssm:${var.aws_region}:*:parameter/piki/observability/*"]
  }

  statement {
    sid       = "ReadMigrationProvisionScripts"
    actions   = ["s3:GetObject"]
    resources = ["arn:aws:s3:::piki-tfstate-${data.aws_caller_identity.current.account_id}/provision/*"]
  }
}

resource "aws_iam_role_policy" "dev_db_instance" {
  name   = "piki-dev-db-policy"
  role   = aws_iam_role.dev_db.id
  policy = data.aws_iam_policy_document.dev_db_instance.json
}

resource "aws_iam_role_policy_attachment" "dev_db_ssm_core" {
  role       = aws_iam_role.dev_db.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_instance_profile" "dev_db" {
  name = "piki-dev-db-profile"
  role = aws_iam_role.dev_db.name
}

resource "aws_instance" "dev_db" {
  ami                         = var.ec2_ami_id != null ? var.ec2_ami_id : data.aws_ami.ubuntu_2404_arm64[0].id
  instance_type               = var.db_ec2_instance_type
  subnet_id                   = aws_subnet.public.id
  availability_zone           = var.azs[0]
  vpc_security_group_ids      = [aws_security_group.dev_db.id]
  iam_instance_profile        = aws_iam_instance_profile.dev_db.name
  associate_public_ip_address = true
  user_data                   = local.db_user_data

  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 2
  }

  root_block_device {
    volume_size = var.db_ec2_volume_size
    volume_type = "gp3"
    encrypted   = true
  }

  # 교체는 데이터가 사라지는 일이라 AMI·부트스트랩 변경으로는 다시 만들지 않는다.
  lifecycle {
    ignore_changes = [ami, user_data]
  }

  tags = {
    Name = "piki-dev-db"
  }
}
