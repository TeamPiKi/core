package com.depromeet.piki.item.event

import com.depromeet.piki.common.event.NotificationEvent
import com.depromeet.piki.item.domain.ParseRequester
import java.util.UUID

// 실패가 아니라 사용자가 나머지를 채우면 완성되는 상태라 완료·실패와 별개 사실로 둔다(#944).
data class ItemParsingIncomplete(
    val requester: ParseRequester,
    val owner: UUID,
    val itemId: Long,
    val snapshotId: Long,
    val refresh: Boolean,
) : NotificationEvent
