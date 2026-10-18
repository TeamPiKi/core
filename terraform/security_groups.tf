# -----------------------------------------------------------------------------
# prod 앱 EC2 Security Group — HTTP(80) + HTTPS(443). SSH 는 SSM 경유라 22 번이 없다(#1196).
# 이름의 team3-dev 는 옛 이름이다. 바꾸면 SG 가 새로 만들어져 prod DB·extractor·renderer 의 참조가 함께 바뀌어야 한다.
#
# 앱 포트는 외부에 노출하지 않는다. 박스의 nginx 가 TLS 를 종료하고 127.0.0.1 에 바인딩된
# blue·green 슬롯으로 forward 한다(포트 정본: TeamPiKi/infra contracts/ports.json).
# -----------------------------------------------------------------------------
resource "aws_security_group" "ec2" {
  name = "${local.name_prefix}-ec2-sg"
  # description 은 바꾸면 SG 가 새로 만들어져 옛 문구 그대로 둔다.
  description = "Allow SSH and HTTP/HTTPS to EC2"
  vpc_id      = aws_vpc.main.id

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

  # ingress 는 콘솔/CLI 가 권위를 가진다(TeamPiKi/infra conventions/infra.md 3절). 위 블록은 신규 환경의 초기 생성값이다.
  lifecycle {
    ignore_changes = [ingress]
  }

  tags = {
    Name = "${local.name_prefix}-ec2-sg"
  }
}

# -----------------------------------------------------------------------------
# dev 앱 EC2 Security Group — prod 앱 SG 와 같은 입구(80·443)를 갖되 그룹을 나눈다.
# prod DB SG 는 prod 앱 SG 만 참조하므로, 그룹을 나눈 것만으로 dev 박스에서 prod DB 로 가는 길이 닫힌다(#1213).
# dev 는 자기 박스의 MySQL·Redis 를 쓴다. 이 그룹을 참조하는 쪽은 extractor 인바운드뿐이다.
# -----------------------------------------------------------------------------
resource "aws_security_group" "dev_app" {
  name        = "piki-dev-app-sg"
  description = "dev app EC2: HTTP/HTTPS in, all out"
  vpc_id      = aws_vpc.main.id

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

  tags = {
    Name = "piki-dev-app-sg"
  }
}

# RDS Security Group 은 #898 로 RDS 자체를 폐기하면서 함께 제거했다.
# 그 자리를 대신하는 것은 db_ec2.tf 의 aws_security_group.db_ec2 다.
