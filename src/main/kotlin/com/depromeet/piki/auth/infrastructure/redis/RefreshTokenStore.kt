package com.depromeet.piki.auth.infrastructure.redis

import java.util.UUID

// 저장 단위는 (userId, sessionId) 다 — 로그인 1회 = 세션 1개 = 슬롯 1개(#893).
// userId 단위로 두면 나중 로그인이 이전 기기의 토큰을 덮어써, 이전 기기의 갱신이 재사용으로 오인되고
// family invalidation 이 방금 로그인한 기기까지 함께 무효화한다.
interface RefreshTokenStore {
    fun save(
        userId: UUID,
        sessionId: String,
        refreshToken: String,
    )

    fun get(
        userId: UUID,
        sessionId: String,
    ): String?

    // 이 세션만 지운다 (현재 토큰 + grace — 로그아웃은 grace 창 안의 멱등 replay 도 즉시 끊어야 한다).
    // 다른 기기의 세션은 살아남는다.
    fun delete(
        userId: UUID,
        sessionId: String,
    )

    // 그 유저의 전 세션을 지운다. 탈퇴·정지처럼 계정 자체를 끊어야 하는 경로 전용.
    // 키가 세션별로 갈라져 있어 인덱스를 순회해야 한다 (userId 하나로 지우던 시절과 달라진 지점).
    fun deleteAll(userId: UUID)

    // refresh 토큰 회전의 단일 진입점. "GET 현재토큰 → 회전 / grace replay / 거부 / 재사용 무효화" 판정을
    // 하나의 원자 연산(Redis Lua)으로 수행해 동시 요청 race 를 원천 차단한다.
    //
    // - sessionId: 제시된 refresh 토큰의 sid 클레임. 어느 슬롯을 회전할지 가른다.
    // - presented: 클라이언트가 제시한 refresh 토큰
    // - candidateRefreshToken: 호출자가 미리 발급해 둔 새 refresh 토큰 (generate-first). 회전 시 이 값이
    //   새 현재 토큰이 되고, replay 로 끝나면 버려진다 (jti 랜덤이라 충돌 없음).
    //   candidate 는 presented 와 **같은 sid** 를 실어야 한다 — 회전은 세션을 이어가는 것이지 새로 만드는 게 아니다.
    //
    // 반환은 sealed RefreshOutcome — 호출자는 when(is) 로 분기한다.
    fun rotateOrReplay(
        userId: UUID,
        sessionId: String,
        presented: String,
        candidateRefreshToken: String,
    ): RefreshOutcome
}

sealed interface RefreshOutcome {
    // 제시 토큰이 현재 토큰과 일치 → 회전 완료. 호출자가 넘긴 candidate 가 새 현재 토큰이 됐다.
    data object Rotated : RefreshOutcome

    // grace 창 안에 같은 옛 토큰으로 다시 들어온 동시 요청 → 이미 발급된 토큰을 멱등 반환 (회전·무효화 없음).
    data class Replayed(
        val refreshToken: String,
    ) : RefreshOutcome

    // 저장된 토큰이 없음 (이미 소비됐거나 TTL 만료) → 거부.
    data object Expired : RefreshOutcome

    // 회전 후·grace 밖에서 옛 토큰 재사용 감지 → 그 세션만 무효화됨, 거부 (도난 의심).
    // 무효화 범위가 세션 단위인 것이 #893 의 핵심 — 계정 전체를 끊으면 다른 기기가 함께 죽는다.
    data object ReuseDetected : RefreshOutcome
}
