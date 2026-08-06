-- 추출 경로별 LLM 모델 지정 (#875). 어떤 모델로 추출할지를 백오피스에서 배포 없이 바꾼다
-- (extraction_platform_policies · source_platforms 와 같은 동적 설정 패턴).
--
-- 이 테이블이 없을 때 모델은 extractor 의 코드 상수(GeminiProperties.DEFAULT_MODEL)에 고정돼 있었고,
-- extractor 박스 1대를 dev · prod 가 공유하므로 저쪽 환경변수로 잡으면 환경 분리가 불가능했다.
-- 정책을 이쪽 DB 에 두면 환경마다 DB 가 달라 dev 실험이 prod 를 덮지 않는다.
--
-- target 은 추출 경로(LINK · IMAGE)다. 링크는 텍스트 + JSON 스키마, 이미지는 vision 이라 최적 모델이 다를 수
-- 있어 축을 나눈다. 행이 없는 경로는 요청에 모델을 싣지 않아 extractor 의 기본값으로 동작한다
-- ("행 없음 = 기본" 규약 — extraction_platform_policies 의 route 와 같다).
--
-- model 은 자유 문자열이다. 아는 모델 목록을 코드에 박으면 새 모델이 나올 때마다 배포가 필요해져
-- "배포 없이 바꾼다"는 목적이 무너진다. 오타 방어는 저장 시점의 프로브 실호출(백오피스)이 진다.
CREATE TABLE extraction_models (
    target     VARCHAR(16)  NOT NULL,
    model      VARCHAR(100) NOT NULL,
    updated_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (target)
);
