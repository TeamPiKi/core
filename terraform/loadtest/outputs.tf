output "loadtest_app_public_ip" {
  description = "가비아에 lt.api.piki.day A 레코드로 등록할 IP. GitHub loadtest environment 의 EC2_HOST 값이기도 하다."
  value       = aws_eip.loadtest_app.public_ip
}

output "loadtest_db_private_ip" {
  description = "SSM /piki-core/loadtest/db-host 와 loadtest env EXTRACTOR_PROD_ADDRESS(<이 IP>:8090)에 넣을 값"
  value       = aws_instance.loadtest_db.private_ip
}

output "loadtest_db_public_ip" {
  description = "DB 박스 SSH 접속용 (EC2 Instance Connect)"
  value       = aws_instance.loadtest_db.public_ip
}

output "loadtest_app_instance_id" {
  description = "EC2 Instance Connect 로 앱 박스에 붙을 때 쓰는 인스턴스 ID"
  value       = aws_instance.loadtest_app.id
}

output "loadtest_db_instance_id" {
  description = "EC2 Instance Connect 로 DB 박스에 붙을 때 쓰는 인스턴스 ID"
  value       = aws_instance.loadtest_db.id
}
