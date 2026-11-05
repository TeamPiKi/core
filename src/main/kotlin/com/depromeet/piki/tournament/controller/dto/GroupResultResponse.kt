package com.depromeet.piki.tournament.controller.dto

import com.depromeet.piki.item.domain.DiscountRate
import com.depromeet.piki.tournament.service.dto.GroupResult
import com.fasterxml.jackson.annotation.JsonInclude
import java.util.UUID

@JsonInclude(JsonInclude.Include.NON_NULL)
data class GroupResultResponse(
    val items: List<GroupResultItemResponse>,
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class GroupResultItemResponse(
        val rank: Int,
        val itemId: Long,
        val name: String?,
        val price: Int?,
        val originalPrice: Int?,
        val discountRate: Int?,
        val currency: String?,
        val imageUrl: String?,
        val chosenBy: List<ParticipantSummaryResponse>,
    )

    // NON_NULL 을 붙이지 않는다 — 가려진 참여자의 userId=null 이 필드째 사라지면 FE 가 "안 내려온 것" 과
    // "가려진 것" 을 구분하지 못한다. isMasked 와 짝지어 항상 명시적으로 내린다.
    data class ParticipantSummaryResponse(
        val userId: UUID?,
        val nickname: String,
        val profileImage: String,
        // 탈퇴 유저면 true. 닉네임·프로필이 익명값이라 FE 가 이 플래그로 "유저 알수없음" 을 렌더한다.
        val isWithdrawn: Boolean,
        // 주최자면 true. 프로필 우측 하단에 HostBadge 를 그린다. 가려진 참여자는 배지가 신원을 지목하므로 항상 false 다.
        val isHost: Boolean,
        // 게스트에게 신원이 가려진 참여자면 true. userId=null, 닉네임·프로필은 물음표 값이다.
        val isMasked: Boolean,
    )

    companion object {
        fun from(result: GroupResult): GroupResultResponse =
            GroupResultResponse(
                items = result.items.map { item ->
                    GroupResultItemResponse(
                        rank = item.rank,
                        itemId = item.itemId,
                        name = item.name,
                        price = item.price,
                        originalPrice = item.originalPrice,
                        discountRate = DiscountRate.of(item.price, item.originalPrice),
                        currency = item.currency,
                        imageUrl = item.imageUrl,
                        chosenBy = item.chosenBy.map { p ->
                            ParticipantSummaryResponse(
                                userId = p.userId,
                                nickname = p.nickname,
                                profileImage = p.profileImage,
                                isWithdrawn = p.isWithdrawn,
                                isHost = p.isHost,
                                isMasked = p.isMasked,
                            )
                        },
                    )
                },
            )
    }
}
