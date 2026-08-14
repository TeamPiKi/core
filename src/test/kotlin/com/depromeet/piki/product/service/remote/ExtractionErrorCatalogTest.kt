package com.depromeet.piki.product.service.remote

import com.depromeet.piki.item.service.ItemParsingMetrics
import org.yaml.snakeyaml.Yaml
import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * 추출 실패 code 계약(정본: TeamPiKi/infra 의 `contracts/extraction-error-codes.yaml`)과 이 repo 의 번역·집계가
 * 어긋나지 않는지 기계로 강제하는 메타 테스트(#936).
 *
 * 강제하는 불변식 둘:
 *  1. 카탈로그의 확정 실패(permanent) code 전수가 [RemoteExtractionContract.PERMANENT_TRANSLATIONS] 에
 *     **명시 분기로** 있다 — 모르는 code 용 fallback 에 조용히 흡수되지 않는다.
 *  2. 각 code 가 카탈로그의 `bucket` 과 **같은 이름의 메트릭 reason** 으로 귀결된다 — 계약의 분류와 대시보드의
 *     분류가 같은 어휘를 쓴다.
 *
 * 카탈로그는 CI 가 `shared-infra` 경로로 체크아웃하고(ci.yml), 로컬은 infra 의 install.sh 가 같은 경로에 설치한다.
 * **파일이 없으면 skip 하지 않고 실패시킨다** — 없을 때 통과시키면 강제가 조용히 사라져, 어긋난 채로 CI 가
 * 초록불이 된다(그게 바로 이 테스트가 막으려는 상태다).
 *
 * Spring 컨텍스트·Docker 가 필요 없다: 카탈로그 파일과 순수 함수만 본다.
 */
class ExtractionErrorCatalogTest {
    private data class CatalogCode(
        val code: String,
        val disposition: String,
        val bucket: String?,
        val scope: String?,
    ) {
        // 우리(파싱 파이프라인)가 번역해야 하는 대상 — 확정 실패이면서 프로브 전용(백오피스 모델 검증)이 아닌 것.
        // 프로브 code 는 추출 응답이 아니라 모델 검증 응답이라 워커·메트릭에 닿지 않는다(HttpExtractionModelProbe 가 따로 번역).
        val isParsingPermanent: Boolean get() = disposition == DISPOSITION_PERMANENT && scope != SCOPE_PROBE
    }

    private val catalog: List<CatalogCode> by lazy {
        val file = File(CATALOG_PATH)
        if (!file.isFile) {
            fail(
                "추출 실패 code 계약 카탈로그를 찾지 못했다: ${file.absolutePath}\n" +
                    "CI 는 ci.yml 의 'Checkout extraction contract' 스텝이, 로컬은 infra 의 install.sh 가 $CATALOG_PATH 에 둔다. " +
                    "없다고 건너뛰면 계약 강제가 사라지므로 실패로 둔다.",
            )
        }
        val root = Yaml().load<Map<String, Any?>>(file.readText()) ?: fail("카탈로그가 비어 있다: ${file.absolutePath}")
        val codes = root[KEY_CODES] as? Map<*, *> ?: fail("카탈로그에 '$KEY_CODES' 매핑이 없다: ${file.absolutePath}")
        codes.map { (name, attributes) ->
            val code = name.toString()
            val fields = attributes as? Map<*, *> ?: fail("code '$code' 의 속성이 매핑이 아니다: $attributes")
            CatalogCode(
                code = code,
                disposition = fields[KEY_DISPOSITION]?.toString() ?: fail("code '$code' 에 $KEY_DISPOSITION 이 없다"),
                bucket = fields[KEY_BUCKET]?.toString(),
                scope = fields[KEY_SCOPE]?.toString(),
            )
        }
    }

    @Test
    fun `카탈로그의 확정 실패 code 는 전수가 translate 의 명시 분기로 있다`() {
        val expected = catalog.filter { it.isParsingPermanent }.map { it.code }.toSet()
        val mapped = RemoteExtractionContract.PERMANENT_TRANSLATIONS.keys
        val missing = expected - mapped
        val unknown = mapped - expected

        if (missing.isNotEmpty() || unknown.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("RemoteExtractionContract.PERMANENT_TRANSLATIONS 이 카탈로그($CATALOG_PATH)와 어긋난다.")
                    if (missing.isNotEmpty()) {
                        appendLine(
                            "- 매핑 누락(모르는 code 용 fallback 으로 떨어져 internal_error 로 집계된다): " +
                                missing.sorted().joinToString(", "),
                        )
                    }
                    if (unknown.isNotEmpty()) {
                        appendLine(
                            "- 카탈로그에 없는 code 를 매핑하고 있다(오타이거나 계약에서 사라진 code): " +
                                unknown.sorted().joinToString(", "),
                        )
                    }
                },
            )
        }
    }

    @Test
    fun `각 확정 실패 code 는 카탈로그 bucket 과 같은 메트릭 reason 으로 귀결된다`() {
        // 카탈로그 bucket → 예외 → reason 라벨까지 실제 경로를 그대로 태운다. 문자열 대조가 아니라 산출물 대조라,
        // 예외를 바꿔 다른 bucket 으로 새면(예: unreadable 을 not_product 예외로) 여기서 걸린다.
        val mismatches =
            catalog.filter { it.isParsingPermanent }.mapNotNull { entry ->
                val bucket = entry.bucket ?: return@mapNotNull "${entry.code}: 카탈로그에 bucket 이 없다(확정 실패는 bucket 필수)"
                val translate = RemoteExtractionContract.PERMANENT_TRANSLATIONS[entry.code] ?: return@mapNotNull null
                val reason = ItemParsingMetrics.reasonOf(translate())
                reason.takeIf { it != bucket }?.let { "${entry.code}: 카탈로그 bucket=$bucket 인데 메트릭 reason=$it 로 집계된다" }
            }

        if (mismatches.isNotEmpty()) {
            fail(
                "확정 실패 code 의 bucket 과 메트릭 reason 이 어긋난다 (카탈로그: $CATALOG_PATH):\n" +
                    mismatches.sorted().joinToString("\n"),
            )
        }
    }

    companion object {
        // worktree·CI 러너 모두 저장소 루트가 작업 디렉터리다(Gradle Test 의 기본 workingDir).
        private const val CATALOG_PATH = "shared-infra/contracts/extraction-error-codes.yaml"

        private const val KEY_CODES = "codes"
        private const val KEY_DISPOSITION = "disposition"
        private const val KEY_BUCKET = "bucket"
        private const val KEY_SCOPE = "scope"

        private const val DISPOSITION_PERMANENT = "permanent"
        private const val SCOPE_PROBE = "probe"
    }
}
