package com.depromeet.piki.item.service

import com.depromeet.piki.item.domain.ItemParseOutbox
import com.depromeet.piki.item.domain.ParseInput
import com.depromeet.piki.item.domain.ParsePurpose
import com.depromeet.piki.item.domain.ParseRequester
import com.depromeet.piki.item.repository.ItemParseOutboxRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Component
class ParsingEnqueuer(
    private val itemParseOutboxRepository: ItemParseOutboxRepository,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun enqueue(
        requester: ParseRequester,
        purpose: ParsePurpose,
        input: ParseInput,
    ): ItemParseOutbox = itemParseOutboxRepository.save(ItemParseOutbox.of(requester, purpose, input))
}
