package com.depromeet.piki.user.event

import java.util.UUID

// 신규 사용자(회원·게스트)가 생성됐다는 도메인 사실. 발행만 하고 관심 있는 리스너가 구독한다.
// NotificationEvent 마커를 붙이지 않는다 — 이 사실은 알림 디스패처가 아니라 별도 관심사(운영 지표 등)가 구독한다.
data class UserCreated(val userId: UUID)
