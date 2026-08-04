-- 추출 버전의 출처(provenance, #825 결정 4). SERVER(구조화 파서) / SERVER_LLM(LLM 추출) / MANUAL(사용자 수기).
-- 카드·가격 추적은 항상 마지막 SERVER* READY 버전을 향하고, MANUAL 은 이력에 남되 기본 뷰에서 접힌다 —
-- 그 구분의 근거가 이 컬럼이다. SERVER 와 SERVER_LLM 을 가르는 이유: LLM 경로는 같은 페이지를 같은 날
-- 재추출해도 값이 달라질 수 있어(비결정성 실측 2026-07-30), 이후 "LLM 추출분은 가격 변동 알림 제외" 같은
-- 정책의 바닥이 된다.
--
-- 기존 행은 기계 추출과 수기 복구(recover)가 같은 자리에 저장돼 와 소급 구분이 불가능하므로 NULL("모름")로
-- 남긴다(forward-only). edited_by 는 MANUAL 행의 편집자 userId — "타인이 고친 값" 표시의 근거. SERVER* 행은 NULL.
--
-- additive·commutative: 컬럼 추가만. 조회 필터는 (item_id, source, status) 접근인데 기존 idx 가 item_id 를
-- 선두로 갖고 행 수가 버전 단위라, 별도 인덱스는 실측 후 필요 시 추가한다.
ALTER TABLE item_snapshots
    ADD COLUMN source    VARCHAR(16) NULL,
    ADD COLUMN edited_by BINARY(16)  NULL;
