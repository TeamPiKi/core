package com.depromeet.piki.item.service

import com.depromeet.piki.item.domain.ParseRequester
import com.depromeet.piki.item.domain.ParseRequesterType
import org.springframework.stereotype.Component
import java.util.UUID

// 요청자(위시·토너먼트 아이템)를 소유한 도메인이 구현한다. item 도메인은 그 도메인을 모른다
interface ParseRequesterPort {
    val type: ParseRequesterType

    // 지워진 요청자도 돌려준다. 요청자가 지워져도 파싱 결과는 아이템에 남는다
    fun find(id: Long): ParseRequesterState?

    fun resolveItem(
        id: Long,
        itemId: Long,
    )
}

data class ParseRequesterState(
    val owner: UUID,
    val itemId: Long?,
)

@Component
class ParseRequesters(
    ports: List<ParseRequesterPort>,
) {
    private val portByType = ports.associateBy { it.type }

    fun find(requester: ParseRequester): ParseRequesterState? = portOf(requester).find(requester.id)

    fun resolveItem(
        requester: ParseRequester,
        itemId: Long,
    ) {
        portOf(requester).resolveItem(requester.id, itemId)
    }

    private fun portOf(requester: ParseRequester): ParseRequesterPort =
        portByType[requester.type] ?: error("요청자 타입 ${requester.type} 을 다루는 도메인이 없다")
}
