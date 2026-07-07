package com.depromeet.piki.image.domain

import com.depromeet.piki.common.domain.LongBaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.LocalDateTime
import java.util.UUID

// 이미지 등록 v2(presigned) 발급~등록 대기 매핑(outbox). 발급 시 raw key 와 맥락(요청자·경로)을 커밋하고,
// S3 에 실제 업로드가 확인되면(confirm 의 HEAD 또는 폴링 백스톱) 이 행을 claim(삭제)하며 PENDING 아이템으로 등록한다.
// context 별 필드 정합(WISH↔tournamentId 없음, TOURNAMENT↔tournamentId 필수)은 팩토리가 시그니처로 강제한다 —
// 생성자를 private 으로 막아 잘못된 조합 자체를 만들 수 없게 한다.
@Entity
@Table(name = "pending_uploads")
class PendingUpload private constructor(
    @Column(name = "image_key", nullable = false, length = 255)
    val imageKey: String,
    @Column(name = "user_id", nullable = false, columnDefinition = "BINARY(16)")
    val userId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(name = "context", nullable = false, length = 16)
    val context: PendingUploadContext,
    @Column(name = "tournament_id")
    val tournamentId: Long?,
    @Column(name = "expires_at", nullable = false)
    val expiresAt: LocalDateTime,
) : LongBaseEntity() {
    companion object {
        // 위시 등록 대기 — tournamentId 는 없다(팩토리가 null 로 고정해 맥락 정합을 시그니처로 보장).
        fun wish(
            imageKey: String,
            userId: UUID,
            expiresAt: LocalDateTime,
        ): PendingUpload = PendingUpload(imageKey, userId, PendingUploadContext.WISH, null, expiresAt)

        // 토너먼트 아이템 등록 대기 — 대상 tournamentId 필수.
        fun tournament(
            imageKey: String,
            userId: UUID,
            tournamentId: Long,
            expiresAt: LocalDateTime,
        ): PendingUpload = PendingUpload(imageKey, userId, PendingUploadContext.TOURNAMENT, tournamentId, expiresAt)
    }
}
