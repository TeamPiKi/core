package com.depromeet.piki.image.domain

// 발급된 raw 이미지가 어느 도메인으로 등록될지 — confirm/폴링이 이 값으로 위시/토너먼트 영속화를 분기한다.
enum class PendingUploadContext {
    WISH,
    TOURNAMENT,
}
