package com.depromeet.piki.item.event

import com.depromeet.piki.common.event.NotificationEvent
import com.depromeet.piki.item.domain.ParseRequester
import java.util.UUID

data class ItemParsingFailed(
    val requester: ParseRequester,
    val owner: UUID,
    val refresh: Boolean,
) : NotificationEvent
