package com.depromeet.piki.item.service

import com.depromeet.piki.common.exception.HttpMappable
import com.depromeet.piki.product.service.ExtractionFailureBucket
import com.depromeet.piki.product.service.ExtractionFailureCode
import com.depromeet.piki.product.service.ProductSnapshot
import io.micrometer.core.instrument.MeterRegistry

// 파싱 단건의 종결 결과(READY/FAILED)를 result·reason 라벨로 센다 — 추출 실패가 트래픽에서 얼마나·왜 나는지 관측한다(#506).
// 메트릭 이름·라벨 키를 한 곳에 고정해 여러 emit 경로(워커·recover)가 같은 키 집합을 쓰게 한다: 키가 어긋나면
// Prometheus 가 뒤 시계열을 조용히 드롭하기 때문(#465 product.extract 사건). 모든 호출이 result·reason 둘 다 채운다.
// host 는 등록 URL 마다 달라 카디널리티가 무한히 커질 수 있어 라벨에 넣지 않는다 — 호스트별 실패는 로그(safeLogString)로 본다.
object ItemParsingMetrics {
    const val METRIC = "item.parsing"
    const val TAG_RESULT = "result"
    const val TAG_REASON = "reason"

    const val RESULT_READY = "ready"

    // 파싱은 끝났으나 일부 필드만 채워 사용자 입력을 기다리는 종결(#944). 실패가 아니므로 failed 에 섞지 않는다 —
    // 섞으면 "우리가 못 끝낸 것"과 "사용자가 마저 채울 것"이 한 숫자가 되어 실패율이 실제보다 나쁘게 보인다.
    const val RESULT_INCOMPLETE = "incomplete"

    const val RESULT_FAILED = "failed"

    // 성공.
    const val REASON_NONE = "none"

    // 워커 확정 실패 5종 — "이 숫자가 늘면 누가 무엇을 하는가"로 나눈다(#936). 값은 계약 카탈로그
    // (shared-infra/contracts/extraction-error-codes.yaml)의 bucket 과 같은 문자열이어야 한다: 원격 code 를
    // 우리 예외로 번역할 때 붙는 bucket 이 그대로 이 라벨이 되고, ExtractionErrorCatalogTest 가 셋을 대조한다.

    // 사용자가 상품 아닌 걸 넣음. 정상 트래픽이라 할 일이 없다.
    const val REASON_NOT_PRODUCT = "not_product"

    // 우리 구성으로 그 페이지를 못 읽음. 늘면 그 도메인의 허가 후보를 본다.
    const val REASON_UNREADABLE = "unreadable"

    // 대상이 우리를 막음. 늘면 UNSUPPORTED 정책 후보를 본다.
    const val REASON_BLOCKED = "blocked"

    // 추출은 됐는데 값을 믿을 수 없음. 늘면 모델·프롬프트·검증 규칙을 본다.
    const val REASON_EXTRACT_QUALITY = "extract_quality"

    // 우리 버그·방어 발동, 또는 매핑되지 않은 원격 code. 늘면 코드를 조사한다.
    const val REASON_INTERNAL_ERROR = "internal_error"

    // 추출은 됐으나 READY 전이가 값 검증에 막힘(이름 없음 등).
    const val REASON_READY_REJECTED = "ready_rejected"

    // recover — 일시 오류 재실행이 상한을 소진. "우리 파이프라인이 끝내 못 끝낸" 진짜 실패라 가장 주목할 reason.
    const val REASON_RETRY_EXHAUSTED = "retry_exhausted"

    // recover — 되살릴 입력이 없음(link·imageKey 둘 다 부재인 orphan). 이미지도 S3 raw 로 durable 적재되므로 정상 흐름엔 없고,
    // 도달하면 영속화 경로가 깨진 신호다.
    const val REASON_NO_SOURCE = "no_source"

    // recover — 마감(created_at 기준 상한) 초과로 종결. attempt 예산과 무관한 벽시계 판정이라, 박동이 멀쩡한 느린 실행과
    // 슬롯이 없어 집히지 못한 PENDING 이 여기로 온다. 이 reason 이 늘면 용량 부족(또는 외부가 느려짐)의 신호다.
    const val REASON_DEADLINE = "deadline"

    fun record(
        registry: MeterRegistry,
        result: String,
        reason: String,
    ) {
        registry.counter(METRIC, TAG_RESULT, result, TAG_REASON, reason).increment()
    }

    // INCOMPLETE 로 끝난 건이 **무엇을 못 채웠는지** 를 로그 한 필드("price" · "name+price")로 남긴다.
    // 메트릭 라벨로 두지 않는 이유는 조합이 늘어도 운영 액션이 같아서다 — 분포는 로그로 보고, 메트릭은
    // result=incomplete 한 줄로 센다(라벨 키 집합을 경로마다 같게 유지하는 #465 규율과도 맞다).
    // currency 는 READY 필수가 아니라 "못 채운 것"에 세지 않는다.
    fun missingFieldsOf(snapshot: ProductSnapshot): String {
        val missing = mutableListOf<String>()
        snapshot.name?.takeIf { it.isNotBlank() } ?: missing.add("name")
        snapshot.price ?: missing.add("price")
        snapshot.imageUrl ?: missing.add("imageUrl")
        return missing.joinToString("+")
    }

    // 확정 실패 예외 → reason 라벨. 분류의 정본은 예외가 참조하는 ErrorCode 의 bucket 이고(ExtractionFailureCode),
    // 여기서는 그 bucket 을 라벨 문자열로 옮기기만 한다 — 원격 code 가 늘어도 이 함수는 그대로다.
    // when 이 exhaustive 라, bucket 이 추가되면 라벨을 정하지 않은 채로는 컴파일되지 않는다.
    //
    // bucket 을 못 얻는 경우(분류 밖 예외 — 코드 버그성 NPE·JVM Error, 또는 매핑되지 않은 원격 code)는
    // internal_error 다. 그 자리는 "우리가 이름을 아는 실패"가 아니라 조사 대상이라는 뜻이므로, 이름 없는
    // 실패를 다른 바구니에 섞지 않는다. 링크·이미지 두 워커가 같은 함수를 쓴다(같은 메트릭 모집단).
    fun reasonOf(e: Throwable): String {
        val bucket = ((e as? HttpMappable)?.errorCode as? ExtractionFailureCode)?.bucket ?: return REASON_INTERNAL_ERROR
        return when (bucket) {
            ExtractionFailureBucket.NOT_PRODUCT -> REASON_NOT_PRODUCT
            ExtractionFailureBucket.UNREADABLE -> REASON_UNREADABLE
            ExtractionFailureBucket.BLOCKED -> REASON_BLOCKED
            ExtractionFailureBucket.EXTRACT_QUALITY -> REASON_EXTRACT_QUALITY
            ExtractionFailureBucket.INTERNAL_ERROR -> REASON_INTERNAL_ERROR
        }
    }
}
