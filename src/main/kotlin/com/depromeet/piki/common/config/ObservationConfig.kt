package com.depromeet.piki.common.config

import io.lettuce.core.tracing.LettuceObservationContext
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationPredicate
import net.ttddyy.observation.tracing.DataSourceBaseContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.server.observation.ServerRequestObservationContext
import org.springframework.scheduling.support.ScheduledTaskObservationContext

// 관측 노이즈를 observation 단계에서 제외한다 — predicate 가 false 면 그 observation 이 NOOP 이 되어 span·메트릭이
// 아예 생기지 않는다. Spring Boot 가 @Bean ObservationPredicate 를 ObservationRegistry 에 자동 적용한다.
//
// 제외 대상은 "우리 API 표면도, 실제 작업도 아닌" 셋뿐이다:
//  - @Scheduled 폴링(ItemParsingScheduler.dispatch 매 1s · recover 매 15s): 할 일이 없어도(claim 0건) 메서드
//    실행마다 span 을 만들어 한산한 prod 의 Tempo 트레이스를 이 폴링으로 가득 채운다. 실제 파싱 작업은 워커가
//    독립 "item.parse" span 으로 따로 남기므로(파싱 워커가 @Async 라 이 폴링 trace 가 전파되지 않고
//    자기 trace 를 새로 연다) 폴링을 꺼도 파싱 추적은 그대로 보존된다. ScheduledTaskObservationContext 타입으로
//    식별해 observation name 문자열에 의존하지 않는다(컴파일 안전).
//  - actuator 요청(EC2 내부 Alloy 의 /actuator/prometheus scrape 등): 우리 API 가 아니라 수집기 트래픽이라 노이즈다.
//  - 부모 없는 인프라 관측(JDBC 의 connection·query, Redis 의 exists·pexpire, Spring Security 필터체인):
//    위 @Scheduled·actuator 억제 뒤에도 그 틱·요청 안에서 돌던 인프라 관측이 부모 없는 루트 span 이 되어,
//    이름만 "connection"·"pexpire" 인 1ms 안팎 트레이스가 Tempo 를 도배한다(#840·#889). micrometer 는 부모
//    억제를 자식에 전파하지 않아, 부모가 사라져도 자식은 자기 판정으로 살아남아 스스로 루트가 되기 때문이다.
//    인프라 관측은 그 자체가 작업이 아니라 어떤 작업(HTTP 요청·item.parse)의 부속이라 부모가 있을 때만 관측
//    가치가 있으므로, 루트가 되는 경우만 거부한다. 판별 근거 둘 다 micrometer 소스로 확인했다 — (1) parent 는
//    predicate 평가 직전 setParentFromCurrentObservation 이 채우므로 여기서 읽을 수 있고
//    (Observation.createNotStarted), (2) 억제된 부모 안의 인프라 관측은 parent 가 null 이거나, noop scope 가
//    공유 static thread-local 을 타고 current 로 잡혀 noop 부모로 들어온다(#851, hasParent 주석). 둘 다
//    "실제 부모 없음"으로 거부한다.
//    JDBC·Redis 는 context 타입(DataSourceBaseContext·LettuceObservationContext)으로 식별해 @Scheduled 억제와
//    같은 컴파일 안전을 지킨다. Spring Security 만 observation name prefix 로 식별하는데, 그 context 타입
//    (ObservationFilterChainDecorator.FilterChainObservationContext)이 package-private final 이라 밖에서 타입
//    참조가 불가능하기 때문이다 — 그 대신 실제 이름을 ObservationConfigTest 가 고정해 라이브러리가 이름을
//    바꾸면 테스트가 깨지게 한다.
//
// 실제 API 요청(http.server.requests, /actuator 외)·item.parse·그 밖의 observation 은 그대로 둔다.
// 부모가 있는 인프라 관측도 그대로 둔다 — 정상 요청의 JDBC·Redis·Security span 은 그 요청 span 의 자식으로 붙는다.
@Configuration
class ObservationConfig {
    @Bean
    fun filterNoiseObservations(): ObservationPredicate =
        ObservationPredicate { name, context ->
            when {
                context is ScheduledTaskObservationContext -> false
                isInfrastructure(name, context) -> hasParent(context)
                context is ServerRequestObservationContext &&
                    (context.carrier?.requestURI?.startsWith("/actuator") ?: false) -> false
                else -> true
            }
        }

    // 그 자체가 작업이 아니라 작업의 부속인 관측 — 부모가 있을 때만 남길 대상이다.
    private fun isInfrastructure(
        name: String,
        context: Observation.Context,
    ): Boolean =
        context is DataSourceBaseContext ||
            context is LettuceObservationContext ||
            name.startsWith(SPRING_SECURITY_PREFIX)

    private fun hasParent(context: Observation.Context): Boolean {
        val parent = context.parentObservation ?: return false
        // noop 부모도 부모 없음으로 취급한다(#851). 억제된 observation(NoopButScopeHandlingObservation)이
        // scope 를 열면 micrometer 1.16.x 의 NOOP 레지스트리가 scope 저장을 SimpleObservationRegistry 의
        // 공유 static thread-local 로 위임해, 실제 레지스트리의 current 로 그 noop 이 잡힌다 — 그래서 억제된
        // @Scheduled 틱 안의 JDBC 관측은 parent 가 null 이 아니라 noop 으로 들어온다. noop 부모는 span 을
        // 만들지 않아 자식이 결국 루트 "connection" 트레이스로 떨어지므로, 실제 부모가 있을 때만 남긴다.
        // parentObservation 의 정적 타입은 ObservationView 라 isNoop 판별을 위해 Observation 으로 좁힌다
        // (noop 두 구현 모두 Observation 이므로, Observation 이 아닌 view 는 실제 부모로 취급).
        val parentObservation = parent as? Observation ?: return true
        return !parentObservation.isNoop()
    }

    companion object {
        // Spring Security 필터체인 관측의 observation name prefix — 현재 "spring.security.http.secured.requests"
        // 와 "...unsecured.requests"(ObservationFilterChainDecorator) 및 그 하위 인가 관측이 여기 든다. 개별
        // 이름이 아니라 prefix 로 잡아 라이브러리가 관측을 더해도 같은 판정을 받게 한다.
        const val SPRING_SECURITY_PREFIX = "spring.security."
    }
}
