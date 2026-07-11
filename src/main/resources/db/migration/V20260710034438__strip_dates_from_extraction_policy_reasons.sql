-- 사유 메모에서 날짜 접두사를 벗긴다. 정책이 마지막으로 바뀐 시각은 updated_at 이 이미 들고 있어, 날짜를 사유
-- 문자열에도 적으면 같은 사실이 두 곳에 존재하고(SSOT 위반) 한쪽만 고쳐질 때 조용히 어긋난다. 사유는 "무엇을
-- 관찰했나"만 남기고, "언제"는 updated_at 하나가 답한다 (백오피스 보드·상세가 그 값을 보여준다).
--
-- WHERE 에 reason 원문을 함께 건다 — 운영자가 백오피스에서 이미 손본 행은 건드리지 않고, 시드 원문 그대로인
-- 행만 대상으로 삼는다. 같은 이유로 멱등하며(재적용 시 0행) 다른 마이그레이션과 순서 의존이 없다.
--
-- updated_at 은 일부러 갱신하지 않는다. 갱신하면 "정책이 마지막으로 바뀐 시각"이 이 정리 작업 시각으로 덮여,
-- 사유에서 막 지운 실측 시점 정보를 updated_at 마저 잃는다.
UPDATE extraction_platform_policies SET reason = '직접 GET 500(no body), 봇 차단'
 WHERE domain = 'kream.co.kr' AND reason = '2026-06-16 실측: 직접 GET 500(no body), 봇 차단';

UPDATE extraction_platform_policies SET reason = '403 봇 차단'
 WHERE domain = 'coupang.com' AND reason = '2026-06-16 실측: 403 봇 차단';

UPDATE extraction_platform_policies SET reason = '쇼핑 418·스토어 CAPTCHA(429/490), 차단 변동적'
 WHERE domain = 'naver.com' AND reason = '2026-06-16 실측: 쇼핑 418·스토어 CAPTCHA(429/490), 차단 변동적';

UPDATE extraction_platform_policies SET reason = '전 UA 403 JS 챌린지, 상품 데이터 0'
 WHERE domain = 'oliveyoung.co.kr' AND reason = '2026-06-18 실측: 전 UA 403 JS 챌린지, 상품 데이터 0';

UPDATE extraction_platform_policies SET reason = '실페이지 전 UA 403, 가격 OG 부재 (헤드리스로 이름·이미지는 가능)'
 WHERE domain = 'a-bly.com' AND reason = '2026-06-18 실측: 실페이지 전 UA 403, 가격 OG 부재 (헤드리스로 이름·이미지는 가능)';
