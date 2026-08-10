package com.depromeet.piki.notification.service

import com.depromeet.piki.notification.domain.Notification
import java.util.UUID

// 알림 전달 채널. 새 채널 추가 = 이 인터페이스 구현 빈 1개 (SSE/FCM/…).
// Dispatcher 는 채널 목록을 순회만 하고, 전달 수단(로컬 write / Redis publish / FCM HTTP)은 구현이 숨긴다.
// 어떤 타입을 다룰지(예: FCM 은 push 대상 타입만)는 각 구현이 send 안에서 자기-적용 판단한다.
interface NotificationChannel {
    /**
     * 알림을 이 채널로 전달하고 **실시간 인앱 전달 건수**를 반환한다.
     *
     * 반환값은 "사용자가 보고 있는 화면에 지금 즉시 반영시킨 연결 수" 다. Dispatcher 가 이 값으로
     * 자동읽음(#812) 여부를 판단하므로, 화면 실시간 반영이 아닌 채널은 전달에 성공해도 0 을 돌려준다 —
     * OS 트레이 푸시는 "도착"이지 "봄"이 아니라서 읽음 근거가 될 수 없다.
     */
    fun send(
        userId: UUID,
        notification: Notification,
    ): Int
}
