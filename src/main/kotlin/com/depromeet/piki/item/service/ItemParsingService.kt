package com.depromeet.piki.item.service

import com.depromeet.piki.item.domain.Item
import com.depromeet.piki.item.domain.ItemParseOutbox
import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.domain.ItemStatus
import com.depromeet.piki.item.domain.ParseInput
import com.depromeet.piki.item.event.ItemParsingCompleted
import com.depromeet.piki.item.event.ItemParsingFailed
import com.depromeet.piki.item.event.ItemParsingIncomplete
import com.depromeet.piki.item.repository.ItemParseOutboxRepository
import com.depromeet.piki.item.repository.ItemRepository
import com.depromeet.piki.item.repository.ItemSnapshotRepository
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.service.ProductSnapshot
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

@Service
class ItemParsingService(
    private val itemRepository: ItemRepository,
    private val itemSnapshotRepository: ItemSnapshotRepository,
    private val itemParseOutboxRepository: ItemParseOutboxRepository,
    private val itemResolver: ItemResolver,
    private val parseRequesters: ParseRequesters,
    private val eventPublisher: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // RR 이면 대기열이 limit 보다 적을 때 끝 갭이 잠겨 새 PENDING insert 가 집기 커밋까지 막힘(실측)
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun claim(limit: Int): List<Long> =
        itemParseOutboxRepository.findPendingForUpdate(limit).map { outbox ->
            outbox.claim()
            outbox.getId()
        }

    @Transactional(readOnly = true)
    fun inputOf(itemParseOutboxId: Long): ParseInput? = itemParseOutboxRepository.findById(itemParseOutboxId)?.input

    @Transactional
    fun markExtracted(
        itemParseOutboxId: Long,
        extracted: ProductSnapshot,
    ): ItemStatus? {
        val outbox = findProcessingForUpdate(itemParseOutboxId) ?: return null
        val requester = parseRequesters.find(outbox.requester) ?: return discard(outbox)
        if (!ItemSnapshot.accepts(extracted)) {
            close(outbox, requester)
            return ItemStatus.FAILED
        }
        val item = itemOf(outbox, requester, extracted.finalUrl)
        val version = itemSnapshotRepository.save(ItemSnapshot.extracted(item.getId(), extracted, requester.owner))
        requester.itemId ?: parseRequesters.resolveItem(outbox.requester, item.getId())
        outbox.succeed()
        eventPublisher.publishEvent(parsingFact(outbox, requester, version))
        return if (version.isReady()) ItemStatus.READY else ItemStatus.INCOMPLETE
    }

    @Transactional
    fun markFailed(itemParseOutboxId: Long): Boolean {
        val outbox = findProcessingForUpdate(itemParseOutboxId) ?: return false
        parseRequesters.find(outbox.requester)?.let { close(outbox, it) } ?: discard(outbox)
        return true
    }

    @Transactional
    fun requeue(itemParseOutboxId: Long) {
        findProcessingForUpdate(itemParseOutboxId)?.requeue()
    }

    private fun findProcessingForUpdate(itemParseOutboxId: Long): ItemParseOutbox? =
        itemParseOutboxRepository.findByIdForUpdate(itemParseOutboxId)?.takeIf { it.isProcessing() }

    // 탈퇴로 요청자 행이 하드 삭제되면 결과를 받을 곳이 없어 알림 없이 닫는다
    private fun discard(outbox: ItemParseOutbox): Nothing? {
        outbox.fail()
        return null
    }

    // 아이템 행을 잠가 토너먼트 시작과 버전 생성을 직렬화한다
    private fun itemOf(
        outbox: ItemParseOutbox,
        requester: ParseRequesterState,
        finalUrl: ProductLink?,
    ): Item {
        val resolvedId = requester.itemId ?: itemResolver.resolve(outbox.input, finalUrl).getId()
        val item = itemRepository.findByIdForUpdate(resolvedId) ?: error("item $resolvedId 이 없다")
        requester.itemId?.let { itemResolver.warnIfDrifted(item, finalUrl) }
        return item
    }

    private fun close(
        outbox: ItemParseOutbox,
        requester: ParseRequesterState,
    ) {
        outbox.fail()
        if (outbox.isRefresh()) logRefreshFailure(outbox, requester)
        eventPublisher.publishEvent(
            ItemParsingFailed(
                requester = outbox.requester,
                owner = requester.owner,
                refresh = outbox.isRefresh(),
            ),
        )
    }

    // 정체 URL 은 리다이렉트의 끝이라 앞선 홉이 만든 쿠키·상태 없이는 안 열리는 페이지일 수 있다. 대응 코드를 두기 전에 실제로 생기는지 본다
    private fun logRefreshFailure(
        outbox: ItemParseOutbox,
        requester: ParseRequesterState,
    ) {
        log.error(
            "item.refresh.failed outbox={} item={} url=\"{}\" 정체 URL 로 다시 가져오지 못함",
            outbox.getId(),
            requester.itemId,
            outbox.inputValue,
        )
    }

    private fun parsingFact(
        outbox: ItemParseOutbox,
        requester: ParseRequesterState,
        version: ItemSnapshot,
    ): Any {
        val refresh = outbox.isRefresh()
        return if (version.isReady()) {
            ItemParsingCompleted(outbox.requester, requester.owner, version.itemId, version.getId(), refresh)
        } else {
            ItemParsingIncomplete(outbox.requester, requester.owner, version.itemId, version.getId(), refresh)
        }
    }
}
