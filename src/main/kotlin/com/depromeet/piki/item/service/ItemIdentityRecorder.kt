package com.depromeet.piki.item.service

import com.depromeet.piki.item.domain.Item
import com.depromeet.piki.item.repository.ItemLinkRepository
import com.depromeet.piki.item.repository.ItemRepository
import com.depromeet.piki.product.domain.CanonicalLink
import com.depromeet.piki.product.domain.ProductLink
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate

// 상품 정체성(#825)의 기록 계층 — 별칭(item_links)과 canonical 확정을 담당한다. 공유 활성화 전 단계라
// **기록·관측만** 한다: 별칭 히트로 기존 item 에 붙는 재사용도, canonical 충돌의 병합(재부모화)도 아직 없다.
// 충돌은 메트릭·로그로만 남겨 활성화 단계의 판단 재료를 만든다.
//
// 정규화 실패·중복·충돌 어느 것도 호출부(등록·파싱 완료)를 실패시키지 않는다 — 정체성 기록은 값 전달의
// 부가 기능이라, 여기서의 문제로 등록·READY 전이가 죽으면 주객이 전도된다.
@Component
class ItemIdentityRecorder(
    private val itemRepository: ItemRepository,
    private val itemLinkRepository: ItemLinkRepository,
    private val meterRegistry: MeterRegistry,
    private val transactionTemplate: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 등록 트랜잭션 안에서 원본 입력을 별칭으로 기록한다. REQUIRED 라 호출부(영속화 빈)의 트랜잭션에 합류한다 —
    // 별칭은 등록과 원자적으로 남아야 pending 창의 재등록 매칭(활성화 단계)이 성립한다.
    // INSERT IGNORE 라 중복이 예외 없이 0행으로 끝나 등록을 죽일 위험이 없다.
    @Transactional
    fun recordRegistrationAlias(item: Item) {
        val link = item.link ?: return
        val canonical = normalizeOrNull(link, item.getId()) ?: return
        record(canonical, item.getId())
    }

    // 파싱 완료 후 귀결점(finalUrl)으로 canonical 을 확정하고 귀결점 별칭을 남긴다(워커가 READY 전이 커밋 후 호출).
    //
    // claim 블록만 TransactionTemplate 로 격리한다. 이유: canonical unique 충돌(병합 후보)은 SQL 오류라
    // 그 트랜잭션 세션을 rollback-only 로 오염시킨다 — 메서드 전체가 한 트랜잭션이면 예외를 잡아도 커밋에서
    // UnexpectedRollbackException 이 터져 "관측했는데 실패 로그가 남는" 어긋남이 생긴다. 충돌 트랜잭션은
    // 버리고(잃을 write 없음), 충돌 판정·관측은 바깥에서 새로 읽어 남긴다.
    // READY 전이와 분리된 별도 트랜잭션인 이유·병합 시 원자화 계획은 위와 같다: 전이를 오염시키지 않기 위해서고,
    // 공유 활성화 단계에서 병합이 전이와 원자적이어야 할 때 이 흐름을 전이 트랜잭션 안으로 재설계한다(#825 3단계).
    // 그 사이 "전이는 커밋됐는데 기록이 빠지는" 창은 다음 재파싱(갱신)이 자연 복구한다.
    fun recordParsingIdentity(
        itemId: Long,
        finalUrl: String?,
    ) {
        finalUrl ?: run {
            // 구버전 extractor(필드 없음)·이미지 경로. 배포 순서 무관 계약의 정상 경로라 조용히 센다.
            ItemIdentityMetrics.record(meterRegistry, ItemIdentityMetrics.CANONICAL_NO_FINAL_URL)
            return
        }
        val parsed =
            runCatching { ProductLink.parse(finalUrl) }.getOrElse {
                // 귀결점이 URL 계약(https 등)을 벗어남 — 대상 서버의 비정상 리다이렉트다. 정체성 미확정으로 둔다.
                log.warn("canonical 확정 건너뜀 - 귀결점 파싱 불가 item={}", itemId)
                ItemIdentityMetrics.record(meterRegistry, ItemIdentityMetrics.CANONICAL_UNPARSABLE)
                return
            }
        val canonical = normalizeOrNull(parsed, itemId) ?: return

        try {
            transactionTemplate.executeWithoutResult { claim(canonical, itemId) }
        } catch (e: DataIntegrityViolationException) {
            // uq_items_canonical_hash 위반 — 다른 item 이 같은 귀결점을 이미 소유한 병합 후보다.
            // 활성화 전이라 병합하지 않고 관측만 한다. 소유 item 을 로그로 짚어 활성화 단계의 데이터로 남긴다.
            val owner = itemRepository.findByCanonicalHash(canonical.hash)
            log.info(
                "canonical 병합 후보 관측 item={} owner={} url={}",
                itemId,
                owner?.getIdOrNull(),
                parsed.safeLogString(),
            )
            ItemIdentityMetrics.record(meterRegistry, ItemIdentityMetrics.CANONICAL_CONFLICT)
        }
    }

    private fun claim(
        canonical: CanonicalLink,
        itemId: Long,
    ) {
        if (itemRepository.claimCanonicalIfAbsent(itemId, canonical.url, canonical.hash)) {
            ItemIdentityMetrics.record(meterRegistry, ItemIdentityMetrics.CANONICAL_CLAIMED)
            record(canonical, itemId)
            return
        }
        // 0행 = 이 item 은 이미 canonical 을 가짐(재파싱·갱신). 같은 값이면 정상, 다르면 드리프트 관측.
        val current = itemRepository.findById(itemId)
        val currentHash = current?.canonicalHash
        if (currentHash == canonical.hash) {
            ItemIdentityMetrics.record(meterRegistry, ItemIdentityMetrics.CANONICAL_ALREADY_SAME)
            // 귀결점 별칭이 빠졌을 수 있는 과거 기록을 멱등 보수한다(INSERT IGNORE 라 중복 무해).
            record(canonical, itemId)
            return
        }
        // 정체성은 불변 — 첫 확정을 유지한다. 몰의 URL 구조 변경·단축링크 만료 등으로 귀결점이 흔들린 경우다.
        log.warn(
            "canonical 드리프트 관측 item={} 확정 hash 유지, 새 귀결점 무시 url={}",
            itemId,
            canonical.url.take(120),
        )
        ItemIdentityMetrics.record(meterRegistry, ItemIdentityMetrics.CANONICAL_DRIFT)
    }

    private fun record(
        canonical: CanonicalLink,
        itemId: Long,
    ) {
        val recorded = itemLinkRepository.recordIfAbsent(canonical.url, canonical.hash, itemId)
        val result = if (recorded) ItemIdentityMetrics.ALIAS_RECORDED else ItemIdentityMetrics.ALIAS_DUPLICATE
        ItemIdentityMetrics.record(meterRegistry, result)
    }

    private fun normalizeOrNull(
        link: ProductLink,
        itemId: Long,
    ): CanonicalLink? {
        val canonical = CanonicalLink.of(link)
        if (canonical.exceedsStorageLimit) {
            // 절단은 다른 상품과 충돌할 수 있는 거짓 정체성이라 기록 자체를 건너뛴다(#843 리뷰 결정).
            log.warn("정체성 기록 건너뜀 - 정규화 결과가 저장 상한 초과 item={} host={}", itemId, link.normalizedHost())
            ItemIdentityMetrics.record(meterRegistry, ItemIdentityMetrics.CANONICAL_OVERSIZE)
            return null
        }
        return canonical
    }
}
