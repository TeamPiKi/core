package com.depromeet.piki.product.routing

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

// 도메인별 접근 정책 행. domain(정규형: 소문자·trailing dot 없음)이 자연키(PK)라 같은 도메인 문자열의 정책은
// 정확히 하나다 — 옛 스키마에서 route 와 허가 boolean 이 갈라져 "브라우저로 가라 + 안 된다" 같은 모순을
// 저장할 수 있던 것을 축 하나로 합쳐 원천 차단한다.
@Entity
@Table(name = "domain_access_policies")
class DomainAccessPolicyEntity(
    @Id
    @Column(name = "domain", length = 255)
    val domain: String,
    // DomainAccess 의 이름 문자열. @Enumerated 로 두지 않는 이유: 구버전 바이너리가 모르는 값 한 행이
    // 하이드레이션조차 못 해 부팅(@PostConstruct findAll)이 죽는다 — 변환은 읽는 쪽이 tolerant 하게 진다.
    @Column(name = "access", nullable = false, length = 16)
    val access: String,
    @Column(name = "reason", length = 255)
    val reason: String? = null,
    // 허락 근거(메일 스레드·수신일·담당자). 허락은 사람이 받아 오는 것이라 근거 없이 켜진 행은 되짚을 수 없다.
    @Column(name = "permission_ref", length = 255)
    val permissionRef: String? = null,
) {
    init {
        // 생성 경로(admin 서비스·테스트)의 불변식 — JPA no-arg 하이드레이션은 init 을 타지 않으므로 DB 의 기존
        // 행을 막지는 못한다. 정규화 자체는 경계(AdminDomainAccessService.normalize)가 책임지고, 여기는 새 생성
        // 경로가 정규화를 빠뜨리는 코드 버그를 잡는 층이다.
        require(domain.isNotBlank()) { "domain 이 비어 있습니다." }
        require(domain == domain.trim().trimEnd('.').lowercase()) { "domain 은 정규형(소문자·trailing dot 없음)이어야 합니다." }
        // 허락은 근거가 있어야 허락이다. 근거 없는 ALLOWED 는 우회 수단을 여는 값이 아무 흔적 없이 켜진 상태라,
        // 몇 달 뒤 "왜 켰지"를 되짚을 수 없고 지워도 되는지도 판단할 수 없다.
        require(access != DomainAccess.ALLOWED.name || !permissionRef.isNullOrBlank()) {
            "허락 근거(permissionRef) 없이 ALLOWED 정책을 만들 수 없습니다."
        }
    }

    // 행 수정은 upsert(같은 PK 로 새 인스턴스 save = 교체)라 새 인스턴스의 생성 시각이 곧 마지막 변경 시각이 된다.
    @Column(name = "updated_at", nullable = false)
    val updatedAt: LocalDateTime = LocalDateTime.now()
}
