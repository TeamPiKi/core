package com.depromeet.piki.support

import com.depromeet.piki.auth.infrastructure.oauth.apple.AppleNotificationEvent
import com.depromeet.piki.auth.infrastructure.oauth.apple.AppleNotificationVerifier

class StubAppleNotificationVerifier : AppleNotificationVerifier {
    var verifyStub: (String) -> AppleNotificationEvent = { notStubbed("verifyStub") }

    override fun verify(payloadJwt: String): AppleNotificationEvent = verifyStub(payloadJwt)
}
