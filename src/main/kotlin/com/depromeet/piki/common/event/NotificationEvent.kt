package com.depromeet.piki.common.event

// 알림으로 나가는 도메인 사실(fact)의 마커. 이 인터페이스를 구현한 이벤트는 발행만 하면
// NotificationEventListener 가 **타입 하나로** 전부 받아 디스패처로 넘긴다.
//
// 왜 마커를 두나 — 예전엔 리스너가 이벤트 타입마다 `fun on(event: X)` 를 하나씩 들고 있었다. 그래서 새 알림을
// 붙일 때 이벤트·핸들러·리스너 세 곳을 손으로 맞춰야 했고, 리스너 한 줄만 빠뜨리면 Spring 이 구독자 없는
// 이벤트를 조용히 버려 알림이 로그 한 줄 없이 사라졌다 (#961 — 플레이·완료 알림 3종이 그렇게 죽어 있었다).
// 마커를 구현하는 것만으로 구독이 성립하므로 그 누락이 구조적으로 불가능해진다.
//
// 왜 notification 이 아니라 common 에 두나 — 알림 이벤트는 item·tournament 등 **도메인** 패키지가 소유한다.
// 마커를 notification 패키지에 두면 도메인이 알림을 import 하게 되어 "알림 → 도메인 단방향" 결합이 뒤집힌다.
// common 은 양쪽이 함께 딛는 중립 지대라 그 방향을 지킨 채 마커를 공유할 수 있다.
//
// 붙이는 기준: 그 사실이 **누군가에게 알림으로 전달돼야 하는가**. 알림 대상이 아닌 순수 도메인 사실은 붙이지
// 않는다 — 붙이면 디스패처가 핸들러 미등록으로 fail-fast 한다(그 fail-fast 는 의도된 것이다. 아래 참조).
//
// 짝: 마커를 붙였으면 대응하는 NotificationEventHandler 빈이 있어야 한다. 한쪽만 있으면
// NotificationEventSubscriptionIntegrationTest 가 CI 에서 잡는다.
interface NotificationEvent
