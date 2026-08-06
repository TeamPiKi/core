package com.depromeet.piki.admin.access

import org.junit.jupiter.api.Test
import org.springframework.core.annotation.OrderUtils
import org.springframework.session.web.http.SessionRepositoryFilter
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// 게이트가 세션 저장소 필터보다 안쪽에 등록되는지 — 필터 등록 순서 자체를 불변식으로 못박는다(#891).
//
// 왜 이 테스트가 따로 필요한가: 세션이 Redis 로 옮겨간 뒤(#885/#888) getSession 은 SessionRepositoryFilter 가
// 씌우는 요청 래퍼를 통해서만 저장소에 닿는다. 게이트가 그 필터 바깥에 있으면 래퍼 없는 원본 요청을 받아
// 톰캣 인메모리(빈) 세션을 조회하고, 신원이 멀쩡히 Redis 에 있어도 항상 null 이라 grant 직후 404 가 난다.
// 실제로 #888 배포 후 dev 에서 그렇게 터졌다 — 세션·allowlist 는 Redis 에 정상이었고 게이트만 눈이 멀었다.
//
// 이 어긋남은 동작 테스트로는 안 잡힌다. MockMvc 는 addFilters 에 넘긴 순서대로 체인을 만들어 @Order 를 아예
// 참조하지 않으므로, 테스트가 손으로 올바른 순서를 넣는 한 order 가 틀려도 초록불이다(그게 #888 의 통합
// 테스트가 통과한 이유다). 그래서 "실제 배포에서 어떤 순서로 등록되는가" 는 등록값을 직접 비교해 확인한다.
// Boot 는 @Order 가 붙은 Filter 빈을 AnnotationAwareOrderComparator 로 정렬해 등록하므로 이 값이 곧 그 순서다.
class AdminAccessFilterTest {
    @Test
    fun `게이트는 세션 저장소 필터보다 안쪽에 등록된다`() {
        val gateOrder = OrderUtils.getOrder(AdminAccessFilter::class.java)

        assertNotNull(gateOrder, "AdminAccessFilter 에 @Order 가 없다 — 등록 순서가 미정이면 세션 접근이 보장되지 않는다.")
        assertTrue(
            gateOrder > SessionRepositoryFilter.DEFAULT_ORDER,
            "게이트(order=$gateOrder)가 세션 저장소 필터(order=${SessionRepositoryFilter.DEFAULT_ORDER})보다 바깥이다 — " +
                "이러면 게이트의 getSession 이 Redis 세션을 못 봐 grant 직후에도 404 가 난다.",
        )
    }
}
