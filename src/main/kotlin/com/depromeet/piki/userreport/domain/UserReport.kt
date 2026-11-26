package com.depromeet.piki.userreport.domain

import com.depromeet.piki.common.domain.LongBaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.util.UUID

// 신고 한 건(#1291). 운영 판단의 근거라 지우지 않고 쌓는다 — 신고된 사람이 탈퇴해도 남는다.
@Entity
@Table(name = "user_reports")
class UserReport(
    @Column(name = "reporter_id", nullable = false, updatable = false, columnDefinition = "BINARY(16)")
    val reporterId: UUID,
    @Column(name = "reported_id", nullable = false, updatable = false, columnDefinition = "BINARY(16)")
    val reportedId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 50)
    val reason: ReportReason,
    @Column(updatable = false, length = MAX_DETAIL_LENGTH)
    val detail: String? = null,
) : LongBaseEntity() {
    init {
        // 둘 다 입력 경계(서비스·요청 DTO)가 먼저 거른다. 여기 닿았다면 그 경계가 빠뜨린 것이다
        require(reporterId != reportedId) { "자기 자신은 신고할 수 없다 — reporterId=$reporterId" }
        detail?.let {
            require(reason.allowsDetail()) { "기타가 아닌 사유($reason)에는 기타 내용을 담을 수 없다" }
            require(it.length <= MAX_DETAIL_LENGTH) { "기타 내용은 ${MAX_DETAIL_LENGTH}자까지다: ${it.length}자" }
        }
    }

    companion object {
        const val MAX_DETAIL_LENGTH = 300
    }
}
