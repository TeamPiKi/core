package com.depromeet.piki.product.service.remote

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

// 추출 경로별 LLM 모델 지정 행. target(LINK · IMAGE)이 자연키(PK)라 경로당 모델은 정확히 하나다.
// 백오피스가 배포 없이 교체하고, 행이 없는 경로는 extractor 의 기본 모델로 동작한다.
@Entity
@Table(name = "extraction_models")
class ExtractionModelEntity(
    // ExtractionTarget 의 이름 문자열. @Enumerated 로 두지 않는 이유는 ExtractionPlatformPolicyEntity.route 와
    // 같다 — 구버전 바이너리가 모르는 target 행 하나가 findAll 하이드레이션을 깨 부팅(@PostConstruct)을 죽인다.
    // enum 변환은 읽는 쪽(ExtractionModelSettings)이 tolerant 하게 진다.
    @Id
    @Column(name = "target", length = 16)
    val target: String,
    @Column(name = "model", nullable = false, length = 100)
    val model: String,
) {
    init {
        // 생성 경로(admin 서비스·테스트)의 불변식. 정규화·길이 검증은 경계(AdminExtractionModelService)가 지고,
        // 여기는 새 생성 경로가 그것을 빠뜨리는 코드 버그를 잡는 층이다.
        require(model.isNotBlank()) { "model 이 비어 있습니다." }
        require(model.length <= MODEL_MAX_LENGTH) { "model 이 너무 깁니다." }
    }

    // 수정은 upsert(같은 PK 로 새 인스턴스 save = 교체)라 새 인스턴스의 생성 시각이 곧 마지막 변경 시각이다.
    @Column(name = "updated_at", nullable = false)
    val updatedAt: LocalDateTime = LocalDateTime.now()

    companion object {
        // DDL 의 VARCHAR(100) 과 같은 값. 경계 검증(AdminExtractionModelService)도 이 상수를 참조해,
        // 화면 에러로 걸러야 할 입력이 DB 제약 위반(500)으로 새지 않게 한다.
        const val MODEL_MAX_LENGTH = 100
    }
}
