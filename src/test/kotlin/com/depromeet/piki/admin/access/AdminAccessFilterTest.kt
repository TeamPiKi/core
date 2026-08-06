package com.depromeet.piki.admin.access

import org.junit.jupiter.api.Test
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties
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
// 값을 정확히 한 점(DEFAULT_ORDER+1)으로 못박지 않고 구간으로 두는 이유: 게이트가 요구하는 건 두 이웃과의
// 상대 위치뿐이고, 그 사이 어디에 앉는지는 계약이 아니다. 한 점으로 고정하면 구현 상수를 테스트가 그대로
// 베껴 적는 꼴이라, 아무 문제 없는 값 조정에도 테스트가 깨지면서 정작 지켜야 할 두 경계는 더 안 지켜준다.
class AdminAccessFilterTest {
    @Test
    fun `게이트는 세션 저장소 필터 안쪽, Security 보다는 바깥에 등록된다`() {
        val gateOrder = OrderUtils.getOrder(AdminAccessFilter::class.java)

        assertNotNull(gateOrder, "AdminAccessFilter 에 @Order 가 없다 — 등록 순서가 미정이면 세션 접근이 보장되지 않는다.")
        assertTrue(
            gateOrder > SessionRepositoryFilter.DEFAULT_ORDER,
            "게이트(order=$gateOrder)가 세션 저장소 필터(order=${SessionRepositoryFilter.DEFAULT_ORDER})보다 바깥이다 — " +
                "이러면 게이트의 getSession 이 Redis 세션을 못 봐 grant 직후에도 404 가 난다.",
        )
        // 반대쪽 경계 — Security 안쪽으로 넘어가면 메인 JWT 체인이 먼저 401 을 내고, 게이트의 "존재 숨김(404)"
        // 의도가 그 응답에 가려진다. 위 하한만 두면 order 를 0 같은 값으로 옮기는 변경이 그대로 통과한다.
        assertTrue(
            gateOrder < SecurityFilterProperties.DEFAULT_FILTER_ORDER,
            "게이트(order=$gateOrder)가 Security(order=${SecurityFilterProperties.DEFAULT_FILTER_ORDER})보다 안쪽이다 — " +
                "미허용 요청이 404 가 아니라 메인 체인의 401 로 새어 존재 숨김이 깨진다.",
        )
    }
}
