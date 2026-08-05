package com.depromeet.piki.notification.service

// OS 아이콘 badge 로 실을 안읽음 수 — iOS aps.badge·Android setNotificationCount 가 Int 라 좁힌다.
// 보존기간 자동삭제(N일)가 도는 한 유저의 안읽음이 Int 범위(21억)에 닿을 일은 없다. 그래도 clamp 를 둔다 —
// toInt() 는 범위를 넘으면 예외 없이 잘려 **음수 badge** 가 되고, 그 값이 FCM 까지 그대로 나가 조용히 틀린다.
// clamp 면 최악이 "21억으로 포화" 라 눈에 띄기라도 한다. 비용이 0 이라 가정이 깨질 때를 대비해 둔다.
// (안읽음 수의 단일 소스는 countUnread 쿼리다. 여기선 폭만 맞춘다.)
fun badgeCountOf(unreadCount: Long): Int = unreadCount.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
