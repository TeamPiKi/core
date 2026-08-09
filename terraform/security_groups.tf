# -----------------------------------------------------------------------------
# EC2 Security Group — SSH(22) + HTTP(80) + HTTPS(443)
#
# 8080 (Spring Boot) 은 외부에 노출하지 않는다.
# EC2 내부에서 Nginx 또는 Caddy 를 reverse proxy 로 두고 TLS 를 종료한 뒤
# localhost:8080 (var.app_port) 으로 forward 하는 구조를 전제로 한다.
# Spring Boot 는 application.yml 에서 server.address=127.0.0.1 로 바인딩.
# -----------------------------------------------------------------------------
resource "aws_security_group" "ec2" {
  name        = "${local.name_prefix}-ec2-sg"
  description = "Allow SSH and HTTP/HTTPS to EC2"
  vpc_id      = aws_vpc.main.id

  ingress {
    description = "SSH"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = [var.ssh_ingress_cidr]
  }

  ingress {
    description = "HTTP (redirect to HTTPS at reverse proxy layer)"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "HTTPS"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    description = "Allow all outbound"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  # SSH(22) 등 ingress 규칙은 PIKI-Infra maintainer 들이 콘솔/cli 로 직접 관리한다.
  # github actions 배포용 0.0.0.0/0:22, 팀원 SSH IP 등이 콘솔/cli 로 추가돼 있어,
  # terraform 이 ingress 를 덮어쓰면 그 규칙이 제거돼 배포·팀원 SSH 가 끊긴다.
  # 위 ingress 블록은 신규 환경의 초기 생성값일 뿐, 이후엔 콘솔이 권위를 가지므로 변경을 무시한다.
  lifecycle {
    ignore_changes = [ingress]
  }

  tags = {
    Name = "${local.name_prefix}-ec2-sg"
  }
}

# RDS Security Group 은 #898 로 RDS 자체를 폐기하면서 함께 제거했다.
# 그 자리를 대신하는 것은 db_ec2.tf 의 aws_security_group.db_ec2 다.
