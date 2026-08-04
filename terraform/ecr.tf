# -----------------------------------------------------------------------------
# core 컨테이너 이미지 저장소 (Docker Hub 대체, #860)
#
# push: GitHub Actions 러너가 OIDC 로 (oidc.tf). pull: EC2 인스턴스 프로파일 (iam.tf).
# 서울 리전이라 EC2 pull 이 VPC 내부망을 타 인터넷 경유 없이 빠르고, Docker Hub rate limit 에서
# 벗어난다. 다만 배포 시간의 지배 요인은 빌드·부팅이라 pull 단축의 체감은 작다 — 이 전환의 주된
# 값은 태그 자동 정리(아래 lifecycle)와 장기 자격증명 제거다.
# -----------------------------------------------------------------------------
resource "aws_ecr_repository" "core" {
  name                 = "piki-core"
  image_tag_mutability = "MUTABLE" # latest 태그를 매 배포 재부착하므로 MUTABLE

  image_scanning_configuration {
    scan_on_push = true # 취약점 스캔(무료). 결과는 콘솔·API 로 확인
  }
}

# 커밋마다 쌓이는 SHA 태그를 최신 N개만 남기고 자동 삭제한다 — Docker Hub 에서 정책이 없어
# 무한 누적되던 문제(이 전환의 핵심 동기)를 여기서 해소한다. 롤백은 최근 몇 버전이면 충분하다.
# latest 태그는 SHA 태그와 별개 이미지가 아니라 같은 이미지의 추가 태그라, SHA 정리로 함께 회수되지 않는다.
resource "aws_ecr_lifecycle_policy" "core" {
  repository = aws_ecr_repository.core.name

  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "최신 30개 이미지만 유지, 초과분 삭제"
        selection = {
          tagStatus   = "any"
          countType   = "imageCountMoreThan"
          countNumber = 30
        }
        action = { type = "expire" }
      }
    ]
  })
}
