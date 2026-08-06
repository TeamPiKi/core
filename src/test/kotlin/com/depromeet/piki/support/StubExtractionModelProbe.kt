package com.depromeet.piki.support

import com.depromeet.piki.product.service.remote.ExtractionModelProbe
import com.depromeet.piki.product.service.remote.ExtractionTarget

// 모델 프로브(extractor 를 거친 Gemini 실호출)를 통합 테스트에서 격리하기 위한 stub.
// 매 테스트가 본문에서 verify 람다를 명시 세팅한다 (셋업 hook · default 리셋 금지).
//
// default 는 throw 다 — "통과"를 기본으로 두면 저장 게이트가 실제로 프로브를 거치는지 검증하지 못한 채
// 초록불이 된다. 게이트가 사라져도 테스트가 통과하는 것이 이 stub 이 막아야 할 함정이다.
class StubExtractionModelProbe : ExtractionModelProbe {
    var behavior: (ExtractionTarget, String) -> Unit = { _, _ ->
        error("stub.behavior 를 테스트 본문에서 명시 세팅해야 한다. CLAUDE.md '테스트 셋업 원칙' 참고.")
    }

    // 호출 기록 — "저장 전에 프로브를 거쳤나"를 검증한다(mockito verify 와 동등).
    val calls: MutableList<Pair<ExtractionTarget, String>> = mutableListOf()

    override fun verify(
        target: ExtractionTarget,
        model: String,
    ) {
        calls += target to model
        behavior(target, model)
    }
}
