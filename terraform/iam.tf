# -----------------------------------------------------------------------------
# EC2 instance role — 앱이 이미지 버킷에 업로드할 수 있는 최소 권한 (#144)
#
# 앱은 AWS SDK DefaultCredentialsProvider 로 자격증명을 얻는다. EC2 에서는 이 instance role 이
# 자동 적용되어 access key 를 코드/환경변수에 박지 않아도 된다. 권한은 이미지 버킷의
# 객체 Put/Get/Delete + 버킷 ListBucket 으로 한정한다 (state 버킷 등 다른 리소스와 분리).
# -----------------------------------------------------------------------------
data "aws_iam_policy_document" "ec2_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "app" {
  name               = "${local.name_prefix}-app-role"
  assume_role_policy = data.aws_iam_policy_document.ec2_assume.json
}

# dev/prod EC2 가 같은 instance role 을 공유한다(naming 충돌 회피 + 좁은 권한이라 분리 실익 작음).
# 따라서 prod·dev 두 이미지 버킷 모두에 Put/Get 을 부여한다. 각 EC2 는 자기 환경의 S3_BUCKET
# 만 실제로 쓰므로, 상대 버킷 권한은 미사용 과권한이지만 blast radius 가 작다(public-read 저민감 이미지).
data "aws_iam_policy_document" "image_bucket_rw" {
  # 객체 Put/Get/Delete — 업로드, 다운로드(이미지 파싱 워커가 raw 원본 재읽기), 삭제(파싱 후 raw 회수·프로필 삭제).
  statement {
    actions = ["s3:PutObject", "s3:GetObject", "s3:DeleteObject"]
    resources = [
      "${aws_s3_bucket.images.arn}/*",
      "${aws_s3_bucket.dev_images.arn}/*",
      "${aws_s3_bucket.staging_images.arn}/*",
    ]
  }

  # deleteByPrefix(ListObjectsV2)는 버킷 레벨 s3:ListBucket 이 필요하다 — 객체 ARN(/*)이 아니라 버킷 ARN.
  # (회원 탈퇴 시 프로필 이미지 prefix 삭제에 쓰인다.)
  statement {
    actions = ["s3:ListBucket"]
    resources = [
      aws_s3_bucket.images.arn,
      aws_s3_bucket.dev_images.arn,
      aws_s3_bucket.staging_images.arn,
    ]
  }
}

resource "aws_iam_role_policy" "app_image_bucket" {
  name   = "${local.name_prefix}-image-bucket-rw"
  role   = aws_iam_role.app.id
  policy = data.aws_iam_policy_document.image_bucket_rw.json
}

resource "aws_iam_instance_profile" "app" {
  name = "${local.name_prefix}-app-profile"
  role = aws_iam_role.app.name
}

# -----------------------------------------------------------------------------
# 앱 런타임 시크릿을 SSM Parameter Store(/piki-core/<env>/*, SecureString)에서 읽는 권한.
#
# GH secrets → SSM 단일화(배포 공통화 등급 C)로, deploy.yml 의 앱 컨테이너 기동부가 박스에서
# 이 경로를 직접 pull 해 -e 로 주입한다(get-parameters-by-path). 마이그레이션 워크플로
# (migrate-secrets-to-ssm.yml)가 값을 심고, 이 정책이 읽기를 허가한다.
#
# dev/staging/prod EC2 가 이 role 하나를 공유하므로(위 app_image_bucket 과 동일 구조), 세 환경
# 프리픽스를 /piki-core/* 로 한 번에 부여한다. 각 박스는 자기 환경 경로(/piki-core/<env>/)만 실제로
# 읽지만, 공유 role 이라 타 환경 경로도 읽을 수 있는 과권한이 생긴다 — 이미지 버킷과 같은 트레이드오프
# (blast radius 는 작으나, 환경별 엄격 격리가 필요해지면 role 을 환경별로 분리해야 하고 이는 EC2
# instance profile 재배선을 수반한다).
#
# 계정번호는 퍼블릭 repo 라 코드에 박지 않고 ARN region 만 명시, account 는 와일드카드(*)로 둔다
# (extractor infra 와 동일 규율). 경로 리터럴 piki-core 는 워크플로/deploy 의 /piki-core/ 와 짝이며
# terraform 의 name_prefix(team3-*/piki-*)와는 별개다.
#
# SecureString 은 기본 aws/ssm 관리키로 암호화된다. 그 키 정책이 SSM 경유 복호를 계정 주체에
# 허용하므로 별도 kms:Decrypt IAM 문구는 불필요하다(GEMINI 키를 SecureString 으로 읽는 extractor
# 운영 구성과 동일). 고객관리 KMS 키로 바꾸면 그때 kms:Decrypt 를 그 키 ARN 으로 추가한다.
# -----------------------------------------------------------------------------
data "aws_iam_policy_document" "app_ssm_read" {
  statement {
    actions = ["ssm:GetParameter", "ssm:GetParameters", "ssm:GetParametersByPath"]
    # /piki/observability/* : Grafana Cloud 자격 공유 경로 — 세 서비스 박스가 같은 경로를 읽어
    # 토큰 회전이 1곳 put-parameter 로 끝난다 (#771, extractor·renderer 도 자기 role 에 동일 확장).
    resources = [
      "arn:aws:ssm:${var.aws_region}:*:parameter/piki-core/*",
      "arn:aws:ssm:${var.aws_region}:*:parameter/piki/observability/*",
    ]
  }
}

resource "aws_iam_role_policy" "app_ssm_read" {
  name   = "${local.name_prefix}-ssm-read"
  role   = aws_iam_role.app.id
  policy = data.aws_iam_policy_document.app_ssm_read.json
}
