package com.depromeet.piki.tournament.service.dto

import java.util.UUID

data class GroupResult(
    val items: List<GroupResultItem>,
) {
    // 게스트 요청자 관점으로 다시 쓴 결과. 본인은 그대로 두고 맨 앞으로 올리고, 나머지 참여자는 신원을 지운다.
    // 상품·순위는 건드리지 않는다 — 결과를 통째로 가리면 플레이를 끝내고도 결과를 못 봐 이탈하므로,
    // "사람만 가려 궁금하게 만들고 회원가입으로 유도" 가 결정된 정책이다.
    fun maskedFor(
        requesterId: UUID,
        maskedProfileImage: String,
    ): GroupResult = GroupResult(items = items.map { it.maskedFor(requesterId, maskedProfileImage) })
}

data class GroupResultItem(
    val rank: Int,
    val itemId: Long,
    val name: String?,
    val price: Int?,
    val originalPrice: Int?,
    val discountRate: Int?,
    val currency: String?,
    val imageUrl: String?,
    val chosenBy: List<ParticipantSummary>,
) {
    fun maskedFor(
        requesterId: UUID,
        maskedProfileImage: String,
    ): GroupResultItem {
        // partition 은 원래 순서를 보존하므로, 본인을 앞으로 뽑아내도 나머지 집계 순서는 그대로 남는다.
        val (mine, others) = chosenBy.partition { it.userId == requesterId }
        return copy(chosenBy = mine + others.map { it.masked(maskedProfileImage) })
    }
}

data class ParticipantSummary(
    // 가려진 참여자는 null. UUID 는 토너먼트를 넘나들며 "같은 사람" 을 잇는 단서라, 마스킹 대상에겐 내리지 않는다.
    val userId: UUID?,
    val nickname: String,
    val profileImage: String,
    // 탈퇴 유저 여부. 닉네임·프로필은 익명값으로 가려지므로, FE 가 "유저 알수없음" 을 깔끔히 렌더하도록 명시 플래그를 내린다.
    val isWithdrawn: Boolean,
    // 이 토너먼트를 만든 주최자면 true. FE 가 프로필 우측 하단에 HostBadge 를 그린다(#1062).
    val isHost: Boolean = false,
    // 게스트에게 신원이 가려진 참여자면 true. FE 가 이 플래그로 물음표 처리와 "로그인해야 볼 수 있어요" 유도를 렌더한다.
    val isMasked: Boolean = false,
) {
    // 신원 관련 필드를 전부 비식별 값으로 덮는다. isWithdrawn·isHost 까지 false 로 고정하는 건, 가려진 사람이
    // 탈퇴자인지 주최자인지도 게스트가 알 필요가 없어서다 — 남기면 마스킹이 그만큼 샌다.
    // 특히 주최자 배지는 치명적이다. 게스트는 자기를 초대한 사람이 주최자임을 이미 아니까, 배지 하나가 그 한 명의
    // 신원을 그대로 지목한다.
    fun masked(maskedProfileImage: String): ParticipantSummary =
        ParticipantSummary(
            userId = null,
            nickname = MASKED_NICKNAME,
            profileImage = maskedProfileImage,
            isWithdrawn = false,
            isHost = false,
            isMasked = true,
        )

    companion object {
        // 가려진 참여자의 표시 닉. 실제 닉 길이·글자를 유추할 단서를 남기지 않도록 길이와 무관한 고정값을 쓴다.
        const val MASKED_NICKNAME = "?"
    }
}
