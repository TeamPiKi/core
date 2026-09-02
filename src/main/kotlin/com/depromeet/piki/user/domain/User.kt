package com.depromeet.piki.user.domain

import com.depromeet.piki.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "users")
class User(
    id: UUID,
    nickname: String,
    profileImage: String,
    identityType: IdentityType,
) : BaseEntity<UUID>() {
    init {
        validateNickname(nickname)
    }

    @Id
    @Column(name = "id", columnDefinition = "BINARY(16)")
    val id: UUID = id

    override fun getIdOrNull(): UUID = id

    var nickname: String = nickname
        protected set

    @Column(name = "profile_image", nullable = false, length = 2048)
    var profileImage: String = profileImage
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "identity_type", nullable = false, length = 10)
    var identityType: IdentityType = identityType
        protected set

    fun promoteToMember() {
        if (identityType != IdentityType.GUEST) throw UserException.alreadyMember()
        identityType = IdentityType.MEMBER
    }

    fun updateProfileImage(newProfileImage: String) {
        profileImage = newProfileImage
    }

    fun updateNickname(newNickname: String) {
        validateNickname(newNickname)
        nickname = newNickname
    }

    fun softDelete() {
        deletedAt = deletedAt ?: LocalDateTime.now()
    }

    // 탈퇴(soft-delete)되지 않은 활성 유저인지. deletedAt 이 채워졌으면 tombstone 이라 false.
    fun isActive(): Boolean = deletedAt?.let { false } ?: true

    // 회원 탈퇴 — 익명 tombstone 전이. soft-delete 로 접근을 끊되 행은 남겨,
    // FK 없는 참조(tournament_items.userId 등)가 "탈퇴회원" 으로 풀리게 한다(공유 토너먼트 보존).
    // 식별 가능한 닉네임·프로필을 비식별 값으로 덮어 개인정보를 즉시 지운다(소셜 식별자는 서비스가 user_details 를 삭제).
    // 게스트는 보존할 공유 참조가 없어 서비스가 하드삭제하므로, 이 경로는 MEMBER 불변식이다.
    //
    // 탈퇴 아바타 URL 은 env(dev/prod 버킷)마다 달라 엔티티 상수로 둘 수 없어 호출부(DefaultProfileImages.deleted())가
    // 넘긴다. 엔티티는 "무엇으로 덮을지" 를 모르고 "덮는다" 는 사실만 보장한다(updateProfileImage 와 같은 결).
    fun withdraw(deletedProfileImage: String) {
        check(identityType == IdentityType.MEMBER) { "MEMBER 만 익명 tombstone 대상이다. 게스트는 하드삭제 경로." }
        softDelete()
        nickname = anonymizedNickname(id)
        profileImage = deletedProfileImage
    }

    companion object {
        const val NICKNAME_MAX_LENGTH = 10

        // 탈퇴 tombstone 닉네임 예약 prefix. 활성 유저가 이 형태를 선점하면 탈퇴 시 anonymizedNickname 과
        // uq_users_nickname(UNIQUE) 충돌이 날 수 있으므로, 입력 경계(validateNickname)에서 미리 거부해 선점 자체를 막는다.
        const val WITHDRAWN_NICKNAME_PREFIX = "탈퇴"

        // 탈퇴 tombstone 닉네임. uq_users_nickname(UNIQUE) 충돌을 피하려 본인 UUID 에서 파생한다.
        // "탈퇴"(2) + UUID 앞 8 hex = 10자로 NICKNAME_MAX_LENGTH 를 넘지 않는다.
        private fun anonymizedNickname(id: UUID): String =
            WITHDRAWN_NICKNAME_PREFIX + id.toString().replace("-", "").take(8)

        private fun validateNickname(nickname: String) {
            if (nickname.isBlank()) throw UserException.nicknameBlank()
            if (nickname.length > NICKNAME_MAX_LENGTH) throw UserException.nicknameTooLong()
            // 탈퇴 tombstone 예약 prefix 선점 방지 — 활성 유저가 "탈퇴..." 를 쓰면 탈퇴 시 UNIQUE 충돌이 날 수 있다.
            if (nickname.startsWith(WITHDRAWN_NICKNAME_PREFIX)) throw UserException.nicknameReserved()
        }
    }
}
