package com.depromeet.piki.tournament.service

// 토너먼트 카드의 대표 썸네일 선택 규칙. 최근 등록 아이템 순으로 이미지가 있는 것만 최대 2장 고른다.
// (카드 case — 0장: 기본/loading, 1장: 앞 카드, 2장 이상: 앞·뒤 카드.) 순수 함수라 단위 테스트로 분기를 망라한다.
object TournamentThumbnails {
    const val MAX = 2

    // recency 가 클수록 최근 등록. imageUrl 은 아직 파싱 안 됐거나(PENDING/PROCESSING) 실패한(FAILED) 스냅샷이면 null.
    data class Candidate(
        val recency: Long,
        val imageUrl: String?,
    )

    fun select(
        candidates: List<Candidate>,
        max: Int = MAX,
    ): List<String> =
        candidates
            .sortedByDescending { it.recency }
            .mapNotNull { it.imageUrl?.takeIf(String::isNotBlank) }
            .take(max)
}
