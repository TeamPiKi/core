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

    // 끄면 폴더 설정과 관계없이 다른 사람에게 폴더·위시를 보이지 않는다. 닉네임·프로필 이미지는 늘 보인다(#1285)
    @Column(name = "is_profile_public", nullable = false)
    var isProfilePublic: Boolean = true
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

    // 게스트는 공개할 폴더가 없어 바꿀 수 없다
    fun changeProfileVisibility(isPublic: Boolean) {
        if (identityType != IdentityType.MEMBER) throw UserException.guestCannotUpdateProfileVisibility()
        isProfilePublic = isPublic
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

        // 탈퇴 tombstone 닉네임 예약 prefix.
        //
        // 원래 근거는 유일 제약 충돌 회피였다 — 활성 유저가 이 형태를 선점하면 탈퇴 시 anonymizedNickname 이
        // uq_users_nickname 과 충돌했다. 중복 허용(#1287)으로 그 제약이 사라져 충돌 근거는 소멸했다.
        // 그래도 금지를 유지한다(#1287 에서 확정). "탈퇴xxxxxxxx" 는 서버가 탈퇴 계정에 붙이는 시스템 표식이라,
        // 활성 유저가 그 모양을 쓰면 탈퇴한 계정으로 보인다. 사용자끼리의 닉네임 중복은 이제 설계상 허용이지만
        // 시스템 표식 흉내는 다른 문제다 — 금지의 근거가 제약 충돌에서 이 사칭 방지로 옮겨 간 것이고,
        // 유니크 제약이 사라졌다는 이유로 이 검사를 걷어내면 안 된다.
        const val WITHDRAWN_NICKNAME_PREFIX = "탈퇴"

        // 탈퇴 tombstone 닉네임. uq_users_nickname(UNIQUE) 충돌을 피하려 본인 UUID 에서 파생한다.
        // "탈퇴"(2) + UUID 앞 8 hex = 10자로 NICKNAME_MAX_LENGTH 를 넘지 않는다.
        private fun anonymizedNickname(id: UUID): String =
            WITHDRAWN_NICKNAME_PREFIX + id.toString().replace("-", "").take(8)

        // 형식·예약 접두사 검사. 엔티티 밖에서도 "이 닉네임이 형식으로 가능한가" 를 물을 수 있게 열어 둔다
        // (닉네임 중복 체크 API 가 중복 판정 없이 형식만 보게 된 뒤로 그 판정이 필요해졌다, #1287).
        fun checkNicknameFormat(nickname: String) = validateNickname(nickname)

        private fun validateNickname(nickname: String) {
            if (nickname.isBlank()) throw UserException.nicknameBlank()
            if (nickname.length > NICKNAME_MAX_LENGTH) throw UserException.nicknameTooLong()
            // 탈퇴 tombstone 예약 prefix 선점 방지 — 활성 유저가 "탈퇴..." 를 쓰면 탈퇴 시 UNIQUE 충돌이 날 수 있다.
            if (nickname.startsWith(WITHDRAWN_NICKNAME_PREFIX)) throw UserException.nicknameReserved()
        }
    }
}
