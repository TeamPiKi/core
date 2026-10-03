package com.depromeet.piki.support

import com.depromeet.piki.auth.infrastructure.oauth.OAuthClient
import com.depromeet.piki.auth.infrastructure.oauth.OAuthProvider
import com.depromeet.piki.auth.infrastructure.oauth.OAuthUserInfo

class StubOAuthClient(
    override val provider: OAuthProvider,
) : OAuthClient {
    var fetchByCodeStub: (String, String) -> OAuthUserInfo = { _, _ -> notStubbed("fetchByCodeStub") }
    var fetchByAccessTokenStub: (String) -> OAuthUserInfo = { notStubbed("fetchByAccessTokenStub") }
    var buildAuthUrlStub: (String, String?) -> String = { state, redirectUri ->
        "https://stub-auth.example.com/oauth/authorize?provider=${provider.name.lowercase()}&state=$state" +
            (redirectUri?.let { "&redirect_uri=$it" } ?: "")
    }
    override fun buildAuthUrl(state: String, redirectUri: String?): String = buildAuthUrlStub(state, redirectUri)

    override fun fetchUserInfoByCode(
        code: String,
        redirectUri: String,
    ): OAuthUserInfo = fetchByCodeStub(code, redirectUri)

    override fun fetchUserInfoByAccessToken(accessToken: String): OAuthUserInfo = fetchByAccessTokenStub(accessToken)
}
