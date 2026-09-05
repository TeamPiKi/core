package com.depromeet.piki.wishlist.repository

import java.util.UUID

// 이 버전(snapshot)을 담은 위시의 (주인, 위시 id, 새로고침 여부) — 파싱 알림의 수신자별 wishId 딥링크 역조회용(#933).
// Spring Data interface projection — JPQL 의 별칭(AS userId / AS wishId / AS refreshed)이 getter 에 매핑된다.
// 한 유저는 한 snapshot 을 위시로 한 번만 담으므로 userId → wishId 가 유일하다.
interface WishOwnerView {
    val userId: UUID
    val wishId: Long

    // 이 위시가 그 버전으로 **새로고침해** 도달했는가(#1036) = 위시가 버전보다 먼저 만들어졌는가. 위시는 늘 이미 있는 버전을
    // 가리키며 태어나므로(등록·공유 합류 모두 snapshot 저장 뒤 wish 저장) 위시가 먼저라는 것은 생성 후 포인터가 그 버전으로
    // 스왑됐다는 뜻이고, 파싱 대상 버전으로의 스왑은 새로고침(진행 중 합류 #826 포함)뿐이다. 등록/새로고침을 적는 컬럼 대신
    // 이 시각 비교(created_at 은 DATETIME(6))로 판정한다. 알려진 한계: 정체성 병합으로 옛 버전이 다른 item 으로 옮겨진 뒤
    // 그 진행 중 버전에 새로고침으로 합류하면 위시가 더 늦어 등록으로 보인다(드문 경합, 감수).
    val refreshed: Boolean
}
