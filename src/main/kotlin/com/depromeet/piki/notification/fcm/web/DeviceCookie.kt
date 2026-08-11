package com.depromeet.piki.notification.fcm.web

// 기기 식별자를 담는 쿠키(#922). 서버가 심지 않고 클라이언트가 FCM 토큰 등록 시점에 직접 심는다 —
// 그래서 우리가 쓰는 토큰 쿠키(TokenCookieWriter)와 섞지 않고, 기기 개념을 가진 fcm 도메인이 이름을 소유한다.
// 로그아웃이 "이 기기의 푸시도 끊는다"를 하려면 auth 경계에서 이 이름을 읽어야 해서 상수로 노출한다.
object DeviceCookie {
    const val DEVICE_ID = "device_id"
}
