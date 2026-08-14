package com.depromeet.piki.item.service

// 파싱 워커 전용의 좁은 포획 — `Exception` 만 잡고 `Error` 는 통과시킨다. 표준 `runCatching` 의 대체다.
//
// **왜 표준 runCatching 을 쓰지 않나**: 그것은 `Throwable` 을 전부 잡아 `Error` 까지 삼킨다. `Error`
// (OutOfMemoryError · StackOverflowError · LinkageError 등)는 "이 요청이 실패했다"가 아니라 **"이 프로세스로는
// 더 진행할 수 없다"** 는 신호다. 그 신호를 받아 처리하는 층은 이미 우리 바깥에 있다 — JVM 의
// `-XX:+ExitOnOutOfMemoryError`(즉시 종료), 컨테이너 재시작, 배포의 헬스체크·blue-green 전환.
// 워커가 삼키면 그 층들이 아무것도 하지 못하고, 반쯤 망가진 프로세스가 다음 작업을 계속 집어 간다(#941).
//
// **왜 실패로도 세면 안 되나**: 두 워커는 재시도 불가 예외를 "확정 실패"로 분류해 종결하는데, 이미지 경로에서
// 확정 실패는 **raw 원본 회수(삭제)** 를 동반한다. `Error` 를 확정 실패로 오분류하면 서버 사정으로 죽는 순간
// 사용자가 올린 원본까지 지워져 재실행할 입력이 사라진다.
//
// **전파하면 어디까지 가나** (실측): `@Async` 워커라 Error 는 Spring 의 async 예외 핸들러까지 올라간다
// (AsyncConfig 에 커스텀 핸들러가 없어 기본 구현이 ERROR 로그를 남긴다). 힙 OOM 만 그 전에 JVM 이 스스로 종료한다.
// 즉 모든 Error 가 프로세스를 죽이지는 않는다 — 그러나 **확정 실패로 오분류되지 않는 것**이 핵심이다.
// 그 결과 상태 전이·raw 회수·메트릭 오염이 일어나지 않고, 행은 PROCESSING 으로 남아 stale 회수(#461)가 되살린다.
// 박동 레지스트리는 ParsingHeartbeat.guarded 의 finally 가 Error 가 지나가도 정리한다.
//
// 반환 타입을 `Result` 로 맞춰 기존 `onSuccess`/`onFailure` 체인을 그대로 쓴다 — 호출부의 모양은 바뀌지 않고
// 포획 범위만 좁아진다.
internal inline fun <T> runCatchingException(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: Exception) {
        Result.failure(e)
    }
