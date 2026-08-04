package com.depromeet.piki.notification.service

// OS 아이콘 badge 로 실을 안읽음 수 — iOS aps.badge·Android setNotificationCount 가 Int 라 좁힌다.
// 보존기간 자동삭제(N일)가 도는 한 유저의 안읽음이 Int 범위(21억)에 닿을 일은 없다 — 그래서 clamp·검증 없이 그대로 좁힌다.
// (toInt() 는 범위를 넘으면 예외 없이 잘려 음수가 되므로, 이 가정이 깨지면 조용히 잘못된 badge 가 나간다.)
// (안읽음 수의 단일 소스는 countUnread 쿼리다. 여기선 폭만 맞춘다.)
fun badgeCountOf(unreadCount: Long): Int = unreadCount.toInt()
