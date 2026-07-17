package com.depromeet.piki.auth.service

import com.depromeet.piki.auth.infrastructure.oauth.OAuthUserInfo
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.user.domain.User
import com.depromeet.piki.user.domain.UserDetail
import com.depromeet.piki.user.repository.UserDetailRepository
import com.depromeet.piki.user.service.UserService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

// 소셜 연결 mutation 을 한 트랜잭션으로 묶는 별도 빈.
//
// 왜 별도 빈 + 기본 REQUIRED 인가:
// - SocialAccountService.resolveUser 는 비트랜잭션이라, 그 안에서 @Transactional 메서드를 self-invocation
//   하면 proxy 를 안 거쳐 트랜잭션이 안 걸린다. 그래서 별도 빈으로 빼 proxy 경계를 만든다.
// - 운영(상위 tx 없음): REQUIRED 가 새 tx 를 열어 커밋한다. 동시 첫 로그인 충돌
//   (uk_user_details_provider_social)이 나면 이 tx 가 롤백되고 호출자(resolveUser, 비트랜잭션)가
//   DataIntegrityViolationException 으로 받아 재조회·합류한다 — 비트랜잭션이라 catch 후 재조회가 깨지지 않는다.
// - 통합 테스트(@Transactional 롤백 격리): REQUIRED 는 테스트 트랜잭션에 join 되어 끝에서 함께 롤백된다.
//   REQUIRES_NEW 였다면 별도 커밋이라 테스트가 행을 남겨 격리가 깨졌을 것이다.
@Service
class SocialAccountWriter(
    private val userService: UserService,
    private val userDetailRepository: UserDetailRepository,
) {
    // 순수 신규 가입: MEMBER 생성 + 소셜 연결을 한 트랜잭션으로 (충돌 시 둘 다 롤백).
    @Transactional
    fun createSocialUserAndLink(userInfo: OAuthUserInfo): User {
        val user = userService.createSocialUser(userInfo.profileImage)
        link(user.id, userInfo)
        return user
    }

    // 게스트 연결: 미탈퇴 GUEST 면 소셜 연결 + 승격을 한 트랜잭션으로, 아니면 null(호출자가 신규 생성으로 진행).
    @Transactional
    fun linkGuestAndPromote(
        guestId: UUID,
        userInfo: OAuthUserInfo,
    ): User? {
        val guest = userService.findById(guestId)
        guest.deletedAt?.let { return null } // 탈퇴 게스트 → 연결 대상 아님
        if (guest.identityType != IdentityType.GUEST) return null // 이미 MEMBER → 게스트-연결 케이스 아님
        link(guestId, userInfo)
        // 가입(승격) 시점에만 provider 프사로 전환한다. provider 가 프사를 안 주거나 빈 값이면(null/blank)
        // 게스트가 갖고 있던 랜덤 기본 아바타를 그대로 유지한다. (기존 유저 재로그인은 loginExisting 경로라
        // 여기 닿지 않으므로, 사용자가 직접 바꾼 프사는 보존된다 — 매 로그인 동기화하지 않는다.)
        userInfo.profileImage
            ?.takeIf { it.isNotBlank() }
            ?.let { userService.updateProfileImageUrl(guestId, it) }
        return userService.promoteToMember(guestId)
    }

    // 기존 가입자 재로그인 시 email upsert (#442). provider 가 준 email 로 갱신해 backfill·최신 유지한다.
    // email 이 null 이면(미수집·미동의) UserDetail.updateEmail 이 기존 값을 보존한다.
    // resolveUser(비트랜잭션)가 호출하므로 proxy 경계를 위해 별도 빈의 @Transactional 메서드로 둔다.
    @Transactional
    fun updateEmail(
        userId: UUID,
        email: String?,
    ) {
        email ?: return // null 이면 조회·갱신 자체를 생략(불필요한 쿼리 방지)
        val detail = userDetailRepository.findByUserId(userId) ?: return
        detail.updateEmail(email)
        userDetailRepository.save(detail)
    }

    private fun link(
        userId: UUID,
        userInfo: OAuthUserInfo,
    ) {
        userDetailRepository.save(
            UserDetail(
                userId = userId,
                provider = userInfo.provider.name,
                socialId = userInfo.socialId,
                email = userInfo.email,
            ),
        )
    }
}
