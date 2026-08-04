# -----------------------------------------------------------------------------
# 크롭한 상품 이미지 저장 버킷 (#144)
#
# 위시 상품 이미지는 민감도가 낮아 public-read 로 둔다 — item.imageUrl 에 영구 공개 URL 을
# 그대로 내려준다 (presigned/CloudFront 는 범위 밖). 공개는 bucket policy(s3:GetObject)로만
# 열고, ACL 기반 공개는 계속 차단한다(BlockPublicAcls 유지). 업로드는 EC2 instance role 만 가능.
# -----------------------------------------------------------------------------
resource "aws_s3_bucket" "images" {
  bucket = var.image_bucket_name

  tags = {
    Name = "${local.name_prefix}-images"
  }
}

# 객체 공개 읽기를 위해 BlockPublicPolicy 만 해제하고, ACL 경로 공개는 차단을 유지한다.
resource "aws_s3_bucket_public_access_block" "images" {
  bucket = aws_s3_bucket.images.id

  block_public_acls       = true
  ignore_public_acls      = true
  block_public_policy     = false
  restrict_public_buckets = false
}

# 누구나 객체를 GET 할 수 있게 한다 (PUT/DELETE 는 instance role 만 — iam.tf).
resource "aws_s3_bucket_policy" "images_public_read" {
  bucket     = aws_s3_bucket.images.id
  depends_on = [aws_s3_bucket_public_access_block.images]

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "PublicReadGetObject"
      Effect    = "Allow"
      Principal = "*"
      Action    = "s3:GetObject"
      Resource  = "${aws_s3_bucket.images.arn}/*"
    }]
  })
}

resource "aws_s3_bucket_server_side_encryption_configuration" "images" {
  bucket = aws_s3_bucket.images.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# -----------------------------------------------------------------------------
# 개발(dev) 전용 이미지 버킷 — prod 버킷(var.image_bucket_name)과 분리해 dev 업로드가
# 운영 버킷을 더럽히지 않게 한다. 이름은 dev-<기존 버킷명>. 위 prod 버킷의 4개 리소스를 그대로 미러한다.
# 앱은 dev env 의 S3_BUCKET / S3_PUBLIC_BASE_URL 로 이 버킷을 가리킨다.
# -----------------------------------------------------------------------------
resource "aws_s3_bucket" "dev_images" {
  bucket = "dev-${var.image_bucket_name}"

  tags = {
    Name = "dev-${var.image_bucket_name}"
  }
}

resource "aws_s3_bucket_public_access_block" "dev_images" {
  bucket = aws_s3_bucket.dev_images.id

  block_public_acls       = true
  ignore_public_acls      = true
  block_public_policy     = false
  restrict_public_buckets = false
}

resource "aws_s3_bucket_policy" "dev_images_public_read" {
  bucket     = aws_s3_bucket.dev_images.id
  depends_on = [aws_s3_bucket_public_access_block.dev_images]

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "PublicReadGetObject"
      Effect    = "Allow"
      Principal = "*"
      Action    = "s3:GetObject"
      Resource  = "${aws_s3_bucket.dev_images.arn}/*"
    }]
  })
}

resource "aws_s3_bucket_server_side_encryption_configuration" "dev_images" {
  bucket = aws_s3_bucket.dev_images.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}


# -----------------------------------------------------------------------------
# CORS (#영수증 캡처) — html-to-image 가 이미지를 canvas 에 그린 뒤 export 하려면, 브라우저가
# cross-origin 이미지의 픽셀을 읽도록 S3 가 Access-Control-Allow-Origin 헤더를 내려줘야 한다.
# CORS 는 접근 제어가 아니다(버킷은 이미 public-read) — 브라우저 JS 의 read 허용 헤더일 뿐이라
# 노출 표면이 늘지 않는다. GET/HEAD 만(읽기 전용, 업로드는 instance role 유지), origin 은
# piki.day + 로컬 개발로 한정한다.
# 외부 쇼핑몰 CDN 이미지(msscdn·pstatic·kakaocdn 등)는 우리 버킷이 아니라 여기서 못 푼다 —
# 그건 별도 이미지 프록시 API 로 처리한다.
# -----------------------------------------------------------------------------
locals {
  image_cors_origins = [
    "https://piki.day",
    "https://www.piki.day",
    "https://*.piki.day",
    "http://localhost:3000",
    "http://localhost:3001",
  ]
}

resource "aws_s3_bucket_cors_configuration" "images" {
  bucket = aws_s3_bucket.images.id

  cors_rule {
    allowed_methods = ["GET", "HEAD"]
    allowed_origins = local.image_cors_origins
    allowed_headers = ["*"]
    max_age_seconds = 3600
  }
}

resource "aws_s3_bucket_cors_configuration" "dev_images" {
  bucket = aws_s3_bucket.dev_images.id

  cors_rule {
    allowed_methods = ["GET", "HEAD"]
    allowed_origins = local.image_cors_origins
    allowed_headers = ["*"]
    max_age_seconds = 3600
  }
}


# -----------------------------------------------------------------------------
# raw 원본(items/raw/) 만료 — 이미지 등록 시 durable 적재한 raw 입력은 파싱이 끝나면 워커가 즉시 삭제하지만,
# 회수 못 한 잔여(recover 상한 FAILED·일시 장애로 delete 누락)는 lifecycle 로 만료해 누적을 막는다.
# 결과 이미지(items/{uuid}.png 등)는 영구 보존이라 prefix 를 raw 로 한정한다. raw 는 재사용 없는 임시 입력이라 1일이면 충분하다.
# -----------------------------------------------------------------------------
resource "aws_s3_bucket_lifecycle_configuration" "images" {
  bucket = aws_s3_bucket.images.id

  rule {
    id     = "expire-raw-originals"
    status = "Enabled"

    filter {
      prefix = "items/raw/"
    }

    expiration {
      days = 1
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "dev_images" {
  bucket = aws_s3_bucket.dev_images.id

  rule {
    id     = "expire-raw-originals"
    status = "Enabled"

    filter {
      prefix = "items/raw/"
    }

    expiration {
      days = 1
    }
  }
}

