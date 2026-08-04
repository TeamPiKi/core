-- 파싱 대기 구조의 명명을 작업 큐(job queue)로 전면 통일하면서, live 스키마에 남은 옛 명명을 함께 교체한다.
-- 적용된 V20260701182909 는 불가침(checksum)이라 새 마이그레이션으로 COMMENT 만 바꾼다. 근거: parsing-claim-ownership.md §2.
ALTER TABLE pending_uploads COMMENT = '이미지 등록 v2 발급~등록 대기 매핑(작업 큐)';
