-- edited_by 제거(#1051 마무리). 2단계에서 created_by 에 흡수돼 아무도 읽지 않게 됐고, 3단계(#1065)에서 엔티티 매핑과
-- 쓰기까지 걷어냈다. 그 3단계가 prod 에 promote 된 뒤라(2026-09-09) 이 DROP 은 배포 창에서도 안전하다 —
-- 옛 컨테이너(3단계 코드)가 이 컬럼을 SELECT·INSERT 어디서도 언급하지 않는다.
-- destructive 단계 배포(add → backfill → drop)의 마지막 단계다. 값은 created_by 에 전부 복사돼 있다(V20260907022825).
ALTER TABLE item_snapshots DROP COLUMN edited_by;
