package com.depromeet.piki.notification.service

// OS 아이콘 badge(안읽음 수) 도출 규칙의 단일 소스(#487).
// getHistory·NotificationReadResponse 의 unreadCount 와 같은 출처에서 도출해, 앱 내부 badge(REST)와
// OS 아이콘 badge(푸시 payload)가 같은 수를 가리키게 한다(두 수치 drift 방지).
// iOS aps.badge·Android setNotificationCount 가 Int 라 toInt() — 안읽음 수는 Int 범위를 넘지 않는다.
fun Long.toBadgeCount(): Int = toInt()
