package com.depromeet.piki.item.service

import com.depromeet.piki.contracts.extraction.v1.ExtractionProto
import com.depromeet.piki.contracts.extraction.v1.ParseRequest
import com.depromeet.piki.item.event.ItemParseRequested
import com.depromeet.piki.product.domain.ParseInputType
import kotlin.reflect.full.memberProperties
import kotlin.test.Test
import kotlin.test.assertEquals

// 요청 스트림에 쓰는 쪽은 어노테이션 인자와 이벤트의 프로퍼티 이름이라 계약에서 읽지 못함. 어긋나면 extractor 가 조용히 버리므로 여기서 묶음
class ParseStreamContractTest {
    @Test
    fun `요청 스트림 이름은 계약의 값과 같다`() {
        assertEquals(ParseRequest.getDescriptor().options.getExtension(ExtractionProto.redisStream), ItemParseRequested.STREAM)
    }

    @Test
    fun `요청 이벤트의 프로퍼티 이름은 계약의 요청 필드 이름과 같다`() {
        val contractFields = ParseRequest.getDescriptor().fields.map { it.jsonName }.toSet()

        assertEquals(contractFields, ItemParseRequested::class.memberProperties.map { it.name }.toSet())
    }

    @Test
    fun `입력 종류의 이름은 계약의 입력 종류와 같다`() {
        val contractTypes =
            ParseRequest.InputType.entries
                .filter { it != ParseRequest.InputType.UNRECOGNIZED && it.number != 0 }
                .map { it.name }
                .toSet()

        assertEquals(contractTypes, ParseInputType.entries.map { it.name }.toSet())
    }
}
