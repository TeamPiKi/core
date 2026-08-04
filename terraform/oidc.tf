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
    # 이 repo 의 어느 브랜치·환경에서 실행되든 push 허용(sub = repo:org/repo:*).
    condition {
      test     = "StringLike"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_repo}:*"]
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
