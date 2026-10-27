package com.depromeet.piki.support

import com.depromeet.piki.notification.domain.Notification
import com.depromeet.piki.notification.fcm.service.FcmMessageSender
import com.depromeet.piki.notification.fcm.service.FcmFailureCode
import com.depromeet.piki.notification.fcm.service.FcmSendResult

// 호출 검증은 각 테스트가 자기 람다로 캡처함. 공유 인스턴스에 카운터를 두면 테스트 사이로 샘
class StubFcmMessageSender : FcmMessageSender {
    // 람다는 죽은 토큰만 돌려줌. 나머지 토큰은 성공으로 셈
    var onSend: (List<String>, Notification, Int) -> List<String> = { _, _, _ -> notStubbed("onSend") }

    var onSendBadgeSync: (List<String>, Int) -> List<String> = { _, _ -> notStubbed("onSendBadgeSync") }

    override fun send(
        tokens: List<String>,
        notification: Notification,
        badge: Int,
    ): FcmSendResult = toResult(tokens, onSend(tokens, notification, badge))

    override fun sendBadgeSync(
        tokens: List<String>,
        badge: Int,
    ): FcmSendResult = toResult(tokens, onSendBadgeSync(tokens, badge))

    private fun toResult(
        tokens: List<String>,
        stale: List<String>,
    ): FcmSendResult {
        val failureByCode = if (stale.isEmpty()) emptyMap() else mapOf(FcmFailureCode.UNREGISTERED to stale.size)
        return FcmSendResult(staleTokens = stale, successCount = tokens.size - stale.size, failureByCode = failureByCode)
    }
}
