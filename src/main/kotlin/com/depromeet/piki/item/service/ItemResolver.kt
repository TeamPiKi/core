package com.depromeet.piki.item.service

import com.depromeet.piki.item.domain.Item
import com.depromeet.piki.item.domain.NormalizedInput
import com.depromeet.piki.item.domain.ParseInput
import com.depromeet.piki.item.repository.ItemRepository
import com.depromeet.piki.product.domain.ProductLink
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

// 호출부 트랜잭션 안에서 돈다. 같은 새 값을 동시에 확정하면 unique 위반으로 한쪽이 롤백되고 그 아웃박스는 회수 대상이 된다
@Component
class ItemResolver(
    private val itemRepository: ItemRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun resolve(
        input: ParseInput,
        finalUrl: ProductLink?,
    ): Item {
        val canonical = (normalizedFinalOf(finalUrl) ?: normalizedOf(input) ?: error("귀결점과 등록 입력이 모두 저장 상한을 넘는다")).asCanonical()
        return itemRepository.findByCanonical(canonical) ?: itemRepository.save(Item.of(input.type, canonical))
    }

    // 정체성은 바꾸지 않는다. 몰의 URL 구조 변경·단축링크 만료가 늘어나는지만 본다
    fun warnIfDrifted(
        item: Item,
        finalUrl: ProductLink?,
    ) {
        val normalized = normalizedFinalOf(finalUrl) ?: return
        if (item.canonicalUrl == normalized.asCanonical()) return
        log.warn("canonical 드리프트 관측 item={} 확정값 유지 url=\"{}\"", item.getId(), normalized)
    }

    private fun normalizedFinalOf(finalUrl: ProductLink?): NormalizedInput? = finalUrl?.let { normalizedOf(ParseInput.Link(it)) }

    // 서버가 다루지 않기로 한 길이다. 실제로 들어오면 상한을 다시 볼 근거라 에러로 남긴다
    private fun normalizedOf(input: ParseInput): NormalizedInput? =
        NormalizedInput.of(input) ?: run {
            log.error("item.normalized.oversize type={} value=\"{}\"", input.type, input.value)
            null
        }
}
