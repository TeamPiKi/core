output "vpc_id" {
  description = "생성된 VPC ID"
  value       = aws_vpc.main.id
}

output "public_subnet_id" {
  description = "EC2 가 위치한 퍼블릭 서브넷 ID"
  value       = aws_subnet.public.id
}

output "private_subnet_ids" {
  description = "RDS DB Subnet Group 에 사용된 프라이빗 서브넷 ID 목록"
  value       = aws_subnet.private[*].id
}

output "ec2_public_ip" {
  description = "운영(prod) EC2 Elastic IP — 가비아 루트 도메인 A 레코드 대상"
  value       = aws_eip.app.public_ip
}

output "ec2_public_dns" {
  description = "운영(prod) EC2 퍼블릭 DNS (EIP 기준)"
  value       = aws_eip.app.public_dns
}

output "dev_ec2_public_ip" {
  description = "개발(dev) EC2 Elastic IP — 가비아 dev.* 서브도메인 A 레코드에 등록할 것"
  value       = aws_eip.dev_app.public_ip
}

output "dev_ec2_public_dns" {
  description = "개발(dev) EC2 퍼블릭 DNS (EIP 기준)"
  value       = aws_eip.dev_app.public_dns
}

output "rds_endpoint" {
  description = "RDS 엔드포인트 (host:port)"
  value       = aws_db_instance.mysql.endpoint
}

output "rds_address" {
  description = "RDS 호스트"
  value       = aws_db_instance.mysql.address
}

output "image_bucket_name" {
  description = "운영(prod) 이미지 버킷명 (prod S3_BUCKET)"
  value       = aws_s3_bucket.images.id
}

output "image_public_base_url" {
  description = "운영(prod) 이미지 버킷 공개 베이스 URL (prod S3_PUBLIC_BASE_URL)"
  value       = "https://${aws_s3_bucket.images.bucket_regional_domain_name}"
}

output "dev_image_bucket_name" {
  description = "개발(dev) 이미지 버킷명 — dev env S3_BUCKET 에 넣을 값"
  value       = aws_s3_bucket.dev_images.id
}

output "dev_image_public_base_url" {
  description = "개발(dev) 이미지 버킷 공개 베이스 URL — dev env S3_PUBLIC_BASE_URL 에 넣을 값"
  value       = "https://${aws_s3_bucket.dev_images.bucket_regional_domain_name}"
}
