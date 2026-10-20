# -----------------------------------------------------------------------------
# 사람 IAM 권한 세 층 (#1194): 조회 그룹 · 백엔드 평소 그룹 · 관리자 역할.
# 그룹 소속은 CLI 로 넣는다. 사람 이메일(IAM 사용자 이름)을 공개 repo 에 두지 않기 위해서다.
# 로그인·서버 접속 절차는 TeamPiKi/infra conventions/infra.md 5절.
# -----------------------------------------------------------------------------

locals {
  # aws login 의 15분 자격 갱신은 MFA 시각을 새로 찍지 않아, 이 값이 "Touch ID 뒤 몇 분" 으로 그대로 동작한다(2026-10-14 실측).
  admin_mfa_max_age_seconds = 1200
}

# 계정 루트 신뢰 + MFA 조건. 누가 전환할 수 있는지는 백엔드 그룹의 sts:AssumeRole 허용이 정한다.
data "aws_iam_policy_document" "admin_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "AWS"
      identifiers = ["arn:aws:iam::${data.aws_caller_identity.current.account_id}:root"]
    }
    condition {
      test     = "Bool"
      variable = "aws:MultiFactorAuthPresent"
      values   = ["true"]
    }
    condition {
      test     = "NumericLessThan"
      variable = "aws:MultiFactorAuthAge"
      values   = [tostring(local.admin_mfa_max_age_seconds)]
    }
  }
}

resource "aws_iam_role" "admin" {
  name                 = "piki-admin"
  assume_role_policy   = data.aws_iam_policy_document.admin_assume.json
  max_session_duration = 3600
}

resource "aws_iam_role_policy_attachment" "admin" {
  role       = aws_iam_role.admin.name
  policy_arn = "arn:aws:iam::aws:policy/AdministratorAccess"
}

# 관리자 권한을 잃은 사람도 자기 비밀번호·MFA(패스키)는 스스로 관리해야 한다.
data "aws_iam_policy_document" "self_service" {
  statement {
    actions   = ["iam:GetAccountPasswordPolicy"]
    resources = ["*"]
  }
  statement {
    actions = [
      "iam:ChangePassword",
      "iam:GetUser",
      "iam:CreateVirtualMFADevice",
      "iam:DeleteVirtualMFADevice",
      "iam:EnableMFADevice",
      "iam:ResyncMFADevice",
      "iam:DeactivateMFADevice",
      "iam:ListMFADevices",
    ]
    resources = [
      "arn:aws:iam::${data.aws_caller_identity.current.account_id}:user/$${aws:username}",
      "arn:aws:iam::${data.aws_caller_identity.current.account_id}:mfa/*",
    ]
  }
}

resource "aws_iam_policy" "self_service" {
  name   = "piki-people-self-service"
  policy = data.aws_iam_policy_document.self_service.json
}

# ReadOnlyAccess 는 S3 객체·SSM 시크릿까지 읽어(DB 백업·자격) 조회 층에 쓰지 않는다.
resource "aws_iam_group" "viewer" {
  name = "PiKi-Viewer"
}

resource "aws_iam_group_policy_attachment" "viewer" {
  for_each = {
    view_only    = "arn:aws:iam::aws:policy/job-function/ViewOnlyAccess"
    self_service = aws_iam_policy.self_service.arn
  }
  group      = aws_iam_group.viewer.name
  policy_arn = each.value
}

resource "aws_iam_group" "backend" {
  name = "PiKi-Backend"
}

data "aws_iam_policy_document" "backend_daily" {
  statement {
    sid     = "SessionManager"
    actions = ["ssm:StartSession"]
    resources = [
      "arn:aws:ec2:*:${data.aws_caller_identity.current.account_id}:instance/*",
      "arn:aws:ssm:*::document/AWS-StartSSHSession",
      "arn:aws:ssm:*::document/AWS-StartPortForwardingSession",
      "arn:aws:ssm:*:${data.aws_caller_identity.current.account_id}:document/SSM-SessionManagerRunShell",
    ]
  }
  # 세션 id 는 IAM 사용자 이름으로 시작한다(실측). OpenDataChannel 이 없으면 세션이 열리자마자 끊긴다.
  statement {
    sid       = "OwnSessions"
    actions   = ["ssm:TerminateSession", "ssm:ResumeSession", "ssmmessages:OpenDataChannel"]
    resources = ["arn:aws:ssm:*:${data.aws_caller_identity.current.account_id}:session/$${aws:username}-*"]
  }
  statement {
    sid     = "RunCommand"
    actions = ["ssm:SendCommand"]
    resources = [
      "arn:aws:ec2:*:${data.aws_caller_identity.current.account_id}:instance/*",
      "arn:aws:ssm:*::document/AWS-RunShellScript",
    ]
  }
  statement {
    sid = "SsmRead"
    actions = [
      "ssm:DescribeInstanceInformation",
      "ssm:DescribeSessions",
      "ssm:GetConnectionStatus",
      "ssm:GetCommandInvocation",
      "ssm:ListCommands",
      "ssm:ListCommandInvocations",
    ]
    resources = ["*"]
  }
  # 노트북 ssh 는 접속마다 공개키를 60초만 올린다. 박스 로그인 사용자는 Ubuntu AMI 의 ubuntu 하나.
  statement {
    sid       = "InstanceConnect"
    actions   = ["ec2-instance-connect:SendSSHPublicKey"]
    resources = ["arn:aws:ec2:*:${data.aws_caller_identity.current.account_id}:instance/*"]
    condition {
      test     = "StringEquals"
      variable = "ec2:osuser"
      values   = ["ubuntu"]
    }
  }
  statement {
    sid = "AppSecrets"
    actions = [
      "ssm:GetParameter",
      "ssm:GetParameters",
      "ssm:GetParametersByPath",
    ]
    resources = ["arn:aws:ssm:*:${data.aws_caller_identity.current.account_id}:parameter/piki*"]
  }
  statement {
    sid       = "AppSecretsDecrypt"
    actions   = ["kms:Decrypt"]
    resources = ["*"]
    condition {
      test     = "StringLike"
      variable = "kms:ViaService"
      values   = ["ssm.*.amazonaws.com"]
    }
  }
  statement {
    sid = "CostRead"
    actions = [
      "ce:Get*",
      "ce:Describe*",
      "ce:List*",
      "budgets:ViewBudget",
      "pricing:GetProducts",
      "pricing:DescribeServices",
      "pricing:GetAttributeValues",
    ]
    resources = ["*"]
  }
  statement {
    sid       = "ImageTags"
    actions   = ["ecr:DescribeImages"]
    resources = ["*"]
  }
  statement {
    sid       = "AssumeAdmin"
    actions   = ["sts:AssumeRole"]
    resources = [aws_iam_role.admin.arn]
  }
}

resource "aws_iam_policy" "backend_daily" {
  name   = "piki-backend-daily"
  policy = data.aws_iam_policy_document.backend_daily.json
}

resource "aws_iam_group_policy_attachment" "backend" {
  for_each = {
    view_only = "arn:aws:iam::aws:policy/job-function/ViewOnlyAccess"
    aws_login = "arn:aws:iam::aws:policy/SignInLocalDevelopmentAccess"
    # CloudShell 은 쓰는 사람의 권한으로 돈다. 백엔드가 콘솔에서 쓰던 셸을 유지하려고 둔다.
    cloudshell   = "arn:aws:iam::aws:policy/AWSCloudShellFullAccess"
    self_service = aws_iam_policy.self_service.arn
    daily        = aws_iam_policy.backend_daily.arn
  }
  group      = aws_iam_group.backend.name
  policy_arn = each.value
}
