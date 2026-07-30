package com.depromeet.piki.common.config

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
//  - 부모 없는 JDBC 관측(datasource-micrometer 의 connection·query 등): 위 @Scheduled 억제 뒤에도 폴링 틱의
//    커넥션 획득이 부모 없는 루트 span 이 되어, 이름만 "connection" 인 1-2ms 트레이스가 초당 1-3건씩 Tempo 를
//    도배했다(#840). JDBC 는 어떤 작업(HTTP 요청·item.parse)의 자식일 때만 관측 가치가 있으므로 루트가 되는
//    경우만 거부한다. 판별 근거 둘 다 micrometer 소스로 확인했다 — (1) parent 는 predicate 평가 직전
//    setParentFromCurrentObservation 이 채우므로 여기서 읽을 수 있고(Observation.createNotStarted),
//    (2) 억제된 @Scheduled 의 noop scope 는 NOOP 레지스트리에 붙어 current 로 안 잡히므로 스케줄러발 JDBC 는
//    parent 가 null 이다. DataSourceBaseContext 타입 식별(컴파일 안전)은 @Scheduled 억제와 같은 방식.
//
// 실제 API 요청(http.server.requests, /actuator 외)·item.parse·그 밖의 observation 은 그대로 둔다.
@Configuration
class ObservationConfig {
    @Bean
    fun filterNoiseObservations(): ObservationPredicate =
        ObservationPredicate { _, context ->
            when {
                context is ScheduledTaskObservationContext -> false
                context is DataSourceBaseContext -> hasParent(context)
                context is ServerRequestObservationContext &&
                    (context.carrier?.requestURI?.startsWith("/actuator") ?: false) -> false
                else -> true
            }
        }

    private fun hasParent(context: Observation.Context): Boolean {
        context.parentObservation ?: return false
        return true
    }
}
