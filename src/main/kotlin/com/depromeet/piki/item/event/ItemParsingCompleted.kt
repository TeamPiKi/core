package com.depromeet.piki.item.event

import com.depromeet.piki.common.event.NotificationEvent
import com.depromeet.piki.item.domain.ParseRequester
import java.util.UUID

data class ItemParsingCompleted(
    val requester: ParseRequester,
    val owner: UUID,
    val itemId: Long,
    val snapshotId: Long,
    val refresh: Boolean,
) : NotificationEvent
