package com.depromeet.piki.product.service.remote

import com.depromeet.piki.contracts.extraction.v1.Bucket
import com.depromeet.piki.contracts.extraction.v1.Disposition
import com.depromeet.piki.contracts.extraction.v1.ExtractionErrorCode
import com.depromeet.piki.contracts.extraction.v1.ExtractionProto
import com.depromeet.piki.item.service.ItemParsingMetrics
import kotlin.test.Test
import kotlin.test.fail

/**
 * 추출 실패 code 계약(정본: TeamPiKi/infra 의 `contracts/extraction.proto`)과 이 repo 의 번역·집계가
 * 어긋나지 않는지 기계로 강제하는 메타 테스트(#936).
 *
 * 강제하는 불변식 둘:
 *  1. 계약의 확정 실패(PERMANENT) code 전수가 [RemoteExtractionContract.PERMANENT_TRANSLATIONS] 에
 *     **명시 분기로** 있다 — 모르는 code 용 fallback 에 조용히 흡수되지 않는다.
 *  2. 각 code 가 계약의 `bucket` 과 **같은 이름의 메트릭 reason** 으로 귀결된다 — 계약의 분류와 대시보드의
 *     분류가 같은 어휘를 쓴다.
 *
 * Spring 컨텍스트·Docker 가 필요 없다: 계약 enum 의 옵션과 순수 함수만 본다.
 */
class ExtractionErrorCatalogTest {
    // 우리(파싱 파이프라인)가 번역해야 하는 대상 — 확정 실패이면서 프로브 전용(백오피스 모델 검증)이 아닌 것.
    // 프로브 code 는 모델 검증 응답이라 워커·메트릭에 닿지 않는다(HttpExtractionModelProbe 가 따로 번역).
    private val parsingPermanentCodes: List<ExtractionErrorCode> =
        ExtractionErrorCode.entries
            .filter { it != ExtractionErrorCode.UNRECOGNIZED && it.number != 0 }
            .filter { it.valueDescriptor.options.getExtension(ExtractionProto.disposition) == Disposition.PERMANENT }
            .filterNot { it.valueDescriptor.options.getExtension(ExtractionProto.probeOnly) }

    @Test
    fun `계약의 확정 실패 code 는 전수가 translate 의 명시 분기로 있다`() {
        val expected = parsingPermanentCodes.map { it.name }.toSet()
        val mapped = RemoteExtractionContract.PERMANENT_TRANSLATIONS.keys
        val missing = expected - mapped
        val unknown = mapped - expected

        if (missing.isNotEmpty() || unknown.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("RemoteExtractionContract.PERMANENT_TRANSLATIONS 이 계약(extraction.proto)과 어긋난다.")
                    if (missing.isNotEmpty()) {
                        appendLine(
                            "- 매핑 누락(모르는 code 용 fallback 으로 떨어져 internal_error 로 집계된다): " +
                                missing.sorted().joinToString(", "),
                        )
                    }
                    if (unknown.isNotEmpty()) {
                        appendLine(
                            "- 계약에 없는 code 를 매핑하고 있다(오타이거나 계약에서 사라진 code): " +
                                unknown.sorted().joinToString(", "),
                        )
                    }
                },
            )
        }
    }

    @Test
    fun `각 확정 실패 code 는 계약 bucket 과 같은 메트릭 reason 으로 귀결된다`() {
        // 계약 bucket → 예외 → reason 라벨까지 실제 경로를 그대로 태운다. 문자열 대조가 아니라 산출물 대조라,
        // 예외를 바꿔 다른 bucket 으로 새면(예: unreadable 을 not_product 예외로) 여기서 걸린다.
        val mismatches =
            parsingPermanentCodes.mapNotNull { code ->
                val bucket = code.valueDescriptor.options.getExtension(ExtractionProto.bucket)
                if (bucket == Bucket.BUCKET_UNSPECIFIED) {
                    return@mapNotNull "${code.name}: 계약에 bucket 이 없다(확정 실패는 bucket 필수)"
                }
                val translate = RemoteExtractionContract.PERMANENT_TRANSLATIONS[code.name] ?: return@mapNotNull null
                val reason = ItemParsingMetrics.reasonOf(translate())
                val expected = bucket.name.lowercase()
                reason.takeIf { it != expected }?.let { "${code.name}: 계약 bucket=$expected 인데 메트릭 reason=$it 로 집계된다" }
            }

        if (mismatches.isNotEmpty()) {
            fail(
                "확정 실패 code 의 bucket 과 메트릭 reason 이 어긋난다 (계약: extraction.proto):\n" +
                    mismatches.sorted().joinToString("\n"),
            )
        }
    }
}
