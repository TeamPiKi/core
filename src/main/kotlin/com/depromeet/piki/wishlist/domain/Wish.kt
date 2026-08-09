package com.depromeet.piki.wishlist.domain

import com.depromeet.piki.common.domain.LongBaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "wishes")
class Wish(
    @Column(name = "user_id", nullable = false, columnDefinition = "BINARY(16)")
    val userId: UUID,
    snapshotId: Long,
) : LongBaseEntity() {
    // 활성 snapshot(현재 보여줄 버전) 참조. raw Long(FK 없음). item 정체성은 snapshot.itemId 단일 출처 —
    // wish 는 itemId 를 따로 들지 않고 snapshot 으로 도달한다. setter 직접 노출 대신 swapSnapshot 명령으로만 바꾼다
    // (ItemSnapshot 추출 필드와 같은 캡슐화). 5단계 갱신(수동 새로고침)이 이 포인터를 새 버전으로 스왑한다.
    @Column(name = "snapshot_id", nullable = false)
    var snapshotId: Long = snapshotId
        protected set

    // 엔티티 불변식 — 0·음수는 존재할 수 없는 참조다. 정상 흐름에선 닿지 않고, 닿으면 코드 버그.
    init {
        require(snapshotId > 0) { "snapshotId 는 양수여야 한다: $snapshotId" }
    }

    // 개인 메모 — item·snapshot 은 여러 사용자가 공유하므로 개인 기록은 user 소유인 wish 행이 든다.
    @Column(name = "memo", length = MEMO_MAX_LENGTH)
    var memo: String? = null
        protected set

    // 소유자가 아니면 거부. 도메인이 자기방어해 어느 통로로 호출되든 같은 결과를 낸다.
    fun verifyOwnedBy(userId: UUID) {
        if (this.userId != userId) throw WishException.forbiddenWishItems()
    }

    // 빈 문자열(공백 포함)은 삭제로 정규화한다. 길이 계약은 입력 경계(@Size)가 거르고 여기는 불변식(500)이다.
    fun updateMemo(rawMemo: String) {
        val normalized = rawMemo.trim().ifEmpty { null }
        normalized?.let {
            require(it.length <= MEMO_MAX_LENGTH) { "메모는 ${MEMO_MAX_LENGTH}자 이하여야 한다: ${it.length}자" }
        }
        memo = normalized
    }

    // 활성 포인터를 새 추출 버전으로 교체한다(수동 새로고침). 옛 snapshot 행은 유지돼 토너먼트 출전 격리를 지킨다 —
    // 갱신은 같은 item 의 새 버전을 가리킬 뿐이고, 같은 item 보장은 호출부(서비스)가 진다(wish 는 itemId 를 모른다).
    fun swapSnapshot(newSnapshotId: Long) {
        require(newSnapshotId > 0) { "snapshotId 는 양수여야 한다: $newSnapshotId" }
        snapshotId = newSnapshotId
    }

    // soft delete — 행을 지우지 않고 deletedAt 으로 마킹한다. 조회는 deletedAt IS NULL 만 본다.
    fun delete() {
        deletedAt = LocalDateTime.now()
    }

    companion object {
        const val MEMO_MAX_LENGTH = 100
    }
}
