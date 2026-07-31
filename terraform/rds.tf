# -----------------------------------------------------------------------------
# DB Subnet Group — RDS 는 최소 2개 AZ 의 서브넷이 필요하다
# -----------------------------------------------------------------------------
resource "aws_db_subnet_group" "main" {
  name       = "${local.name_prefix}-db-subnet-group"
  subnet_ids = aws_subnet.private[*].id

  tags = {
    Name = "${local.name_prefix}-db-subnet-group"
  }
}

# -----------------------------------------------------------------------------
# RDS MySQL 8.4 (LTS) — db.t4g.micro
# -----------------------------------------------------------------------------

# RDS 마스터 비밀번호는 SSM 의 앱 DB 비밀번호를 소스로 읽는다 (앱 시크릿 SSM 단일화의 후속).
# prod 앱이 마스터 계정(admin)으로 이 RDS 에 접속하므로 /piki-core/prod/db-password 가 곧 마스터 비밀번호다.
# 아래 lifecycle.ignore_changes=[password] 로 이 값은 인스턴스 "생성 시점"에만 쓰이며,
# 이후 SSM 값과 실제 비밀번호가 어긋나도 apply 가 비밀번호를 건드리지 않는다.
data "aws_ssm_parameter" "db_password" {
  name            = "/piki-core/prod/db-password"
  with_decryption = true
}

resource "aws_db_instance" "mysql" {
  identifier     = "${local.name_prefix}-mysql"
  engine         = "mysql"
  engine_version = var.db_engine_version
  instance_class = var.db_instance_class

  allocated_storage     = var.db_allocated_storage
  max_allocated_storage = 100
  storage_type          = "gp3"
  storage_encrypted     = true

  db_name  = var.db_name
  username = var.db_username
  password = data.aws_ssm_parameter.db_password.value
  port     = 3306

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.rds.id]
  publicly_accessible    = false
  multi_az               = false

  # EC2 와 동일 AZ 에 고정해서 cross-AZ 데이터 전송 요금을 피한다.
  # DB Subnet Group 은 2개 AZ 가 필요하지만 실제 인스턴스는 여기 한 곳만 사용.
  availability_zone = var.azs[0]

  # AWS 신규 Free Plan(2025-07-15 이후 가입) 은 retention > 0 을 거부한다(FreeTierRestrictionError).
  # 자동 백업·PITR 을 쓸 수 없으므로 복구 지점은 수동 스냅샷이 전담한다(#813).
  # Paid plan 승격 시 7 로 복구할 것.
  backup_retention_period = 0
  backup_window           = "17:00-18:00" # KST 02:00-03:00, retention=0 이면 무시됨
  maintenance_window      = "sun:18:00-sun:19:00"

  # identifier 는 dev 지만 운영 트래픽을 받는 유일한 DB 다(#813 실측). 자동 백업이 없는 상태라
  # 실수로 지우면 복구 경로가 없어, 아래 값들로 "지우기 어렵게" 만든다.
  #
  # 둘이 적용되는 층이 다르다. 헷갈리면 삭제 보호를 푸는 절차를 잘못 이해하게 된다.
  #   deletion_protection: RDS 인스턴스 속성이다. apply 가 ModifyDBInstance 로 AWS 에 실제 전송하며,
  #     콘솔·CLI·SDK 등 경로를 가리지 않고 삭제를 막는다.
  #   skip_final_snapshot / final_snapshot_identifier: terraform 이 DeleteDBInstance 를 호출할 때만
  #     쓰는 인자다. AWS 에 저장되는 상태가 아니라서 terraform 을 거치지 않는 삭제에는 영향이 없다.
  auto_minor_version_upgrade = true
  deletion_protection        = true  # 명시적으로 false 로 바꿔 apply 해야만 삭제된다. 의도된 마찰이다.
  skip_final_snapshot        = false # terraform 으로 지울 때는 최종 스냅샷을 반드시 남긴다.

  # 위 skip_final_snapshot = false 가 provider 레벨에서 요구하는 짝. 이름을 고정값으로 둬서
  # 같은 이름의 스냅샷이 이미 있으면 삭제가 실패한다. 이 역시 마찰로 남겨 둔다.
  final_snapshot_identifier = "${local.name_prefix}-mysql-final"

  # 실제 비밀번호는 콘솔/운영에서 바뀔 수 있어 state·SSM 값과 어긋날 수 있다.
  # terraform 이 apply 마다 비번을 SSM 값으로 강제 변경해 앱 DB 연결이 끊기는 사고를 막기 위해
  # password 변경은 무시한다. 비번을 의도적으로 바꿀 때는 콘솔/CLI 로 직접 수행하고 SSM 파라미터도 함께 갱신한다.
  lifecycle {
    ignore_changes = [password]
  }

  tags = {
    Name = "${local.name_prefix}-mysql"
  }
}
