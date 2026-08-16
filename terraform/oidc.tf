# -----------------------------------------------------------------------------
# GitHub Actions → AWS OIDC 신뢰 (#860)
#
# 러너(AWS 밖)가 ECR push 하려면 자격증명이 필요한데, 장기 액세스 키를 GH secret 에 두는 대신
# OIDC 로 단기 토큰을 발급받는다(Docker Hub 자격증명 제거가 이 전환의 이득 중 하나). provider 는
# 계정당 하나이고 extractor·renderer 도 공유하므로 core 가 소유하되, 확산 시 그쪽은 data 소스로 참조만 한다.
# -----------------------------------------------------------------------------
resource "aws_iam_openid_connect_provider" "github" {
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
  # GitHub 의 OIDC thumbprint. GitHub 이 IdP 라 AWS 가 실제로는 검증에 thumbprint 를 안 쓰지만
  # (2023 이후) API 가 최소 1개를 요구한다. 값은 GitHub 문서의 공개값.
  thumbprint_list = ["6938fd4d98bab03faadb97b34396831e3780aea1"]
}

data "aws_iam_policy_document" "github_push_assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }
    # dev·prod Environment 배포에서만 assume 허용 (브랜치·PR·기타 환경 차단).
    # deploy.yml 의 deploy job 이 environment: dev|prod 를 확정하므로 이 둘로 최소권한화한다.
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values = [
        "repo:${var.github_repo}:environment:dev",
        "repo:${var.github_repo}:environment:prod",
      ]
    }
  }
}

resource "aws_iam_role" "github_ecr_push" {
  name               = "${local.name_prefix}-gha-ecr-push"
  assume_role_policy = data.aws_iam_policy_document.github_push_assume.json
}

# push 최소 권한 — 인증 토큰 + 레이어 업로드 + 이미지 등록. 특정 저장소 ARN 으로 한정한다.
# GetAuthorizationToken 만 리소스 * 를 요구한다(ECR 사양).
data "aws_iam_policy_document" "ecr_push" {
  statement {
    sid       = "AuthToken"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }
  statement {
    sid = "PushToCore"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:InitiateLayerUpload",
      "ecr:UploadLayerPart",
      "ecr:CompleteLayerUpload",
      "ecr:PutImage",
    ]
    resources = [aws_ecr_repository.core.arn]
  }
}

resource "aws_iam_role_policy" "github_ecr_push" {
  name   = "${local.name_prefix}-gha-ecr-push"
  role   = aws_iam_role.github_ecr_push.id
  policy = data.aws_iam_policy_document.ecr_push.json
}

# -----------------------------------------------------------------------------
# GitHub Actions → db 박스 관측(Alloy) 프로비저닝 (#954)
#
# db 박스는 앱이 없어 "배포하는 김에 Alloy 도 갱신"하는 트리거가 없다. 그래서 공용 config 를
# 고쳐도 이 박스만 옛 상태로 남았다(실측: 다른 박스 collector 12개, db 41개). SSH 대신 SSM
# Run Command 로 갱신한다 — 이 박스는 키페어가 없어 EC2 Instance Connect 로 공개키를 밀어 넣어야
# 하는 제약이 있는데, SSM 은 그걸 우회하고 SG 22 개방·IP 관리도 필요 없다.
#
# **ECR push role 에 얹지 않고 전용 role 을 둔다.** 얹으면 이름과 실제가 어긋나고, 모든 배포
# 워크플로가 SSM 실행 권한을 갖게 되어 배포 경로가 오염되면 임의 명령 실행으로 번진다.
# assume 조건을 전용 environment(prod-db)로 가르므로 배포 워크플로는 이 role 을 못 쓰고,
# 반대로 이 워크플로도 ECR 권한을 얻지 못한다.
# -----------------------------------------------------------------------------
data "aws_iam_policy_document" "github_db_provision_assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }
    # 전용 Environment 에서만 assume 허용. dev·prod(앱 배포)와 겹치지 않게 분리한다.
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_repo}:environment:prod-db"]
    }
  }
}

resource "aws_iam_role" "github_db_provision" {
  name               = "${local.name_prefix}-gha-db-provision"
  assume_role_policy = data.aws_iam_policy_document.github_db_provision_assume.json
}

data "aws_iam_policy_document" "db_provision" {
  # 인스턴스 ID 를 태그로 찾는다 — 워크플로에 ID 를 박으면 인스턴스 교체 때 조용히 어긋난다.
  # Describe 계열은 리소스 수준 제한을 지원하지 않아 * 이지만, 읽기 전용이라 노출 위험이 낮다.
  statement {
    sid       = "FindDbInstance"
    actions   = ["ec2:DescribeInstances"]
    resources = ["*"]
  }

  # 이 role 의 핵심 제약. 대상을 db 인스턴스와 셸 실행 문서로 못박아, 자격이 유출돼도
  # 앱·extractor·renderer 박스에는 명령을 쏠 수 없다.
  statement {
    sid     = "RunShellOnDbBoxOnly"
    actions = ["ssm:SendCommand"]
    resources = [
      aws_instance.db.arn,
      "arn:aws:ssm:${var.aws_region}::document/AWS-RunShellScript",
    ]
  }

  # 실행 결과 폴링. SendCommand 가 위에서 막혀 있어 이 권한만으로는 아무것도 실행하지 못한다.
  statement {
    sid       = "ReadCommandResult"
    actions   = ["ssm:GetCommandInvocation", "ssm:ListCommandInvocations"]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "github_db_provision" {
  name   = "${local.name_prefix}-gha-db-provision"
  role   = aws_iam_role.github_db_provision.id
  policy = data.aws_iam_policy_document.db_provision.json
}
