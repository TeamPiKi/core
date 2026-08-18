package com.depromeet.piki.wishlist.repository

import java.util.UUID

// 이 버전(snapshot)을 담은 위시의 (주인, 위시 id) — 파싱 알림의 수신자별 wishId 딥링크 역조회용(#933).
// Spring Data interface projection — JPQL 의 별칭(AS userId / AS wishId)이 getter 에 매핑된다.
// 한 유저는 한 snapshot 을 위시로 한 번만 담으므로 userId → wishId 가 유일하다.
interface WishOwnerView {
    val userId: UUID
    val wishId: Long
}
