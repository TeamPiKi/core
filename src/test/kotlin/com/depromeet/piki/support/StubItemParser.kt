package com.depromeet.piki.support

import com.depromeet.piki.item.service.ItemParser

// 끄면 @Transactional 테스트에서 파서가 커밋 안 된 item 을 읽으며 남기는 경고가 사라짐
class StubItemParser(private val delegate: ItemParser) : ItemParser {
    @Volatile var enabled: Boolean = true

    override fun parse(itemParseOutboxId: Long) {
        if (enabled) delegate.parse(itemParseOutboxId)
    }
}
