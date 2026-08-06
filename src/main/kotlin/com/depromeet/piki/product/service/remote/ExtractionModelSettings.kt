package com.depromeet.piki.product.service.remote

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

// 추출 경로별 LLM 모델 지정의 단일 조회 지점. 인터페이스/구현 분리는 ExtractionRoutingPolicy 와 같은 구조 —
// 소비자(원격 클라이언트)의 단위 테스트가 DB 없이 지정값을 대체할 수 있게 한다.
interface ExtractionModelSettings {
    // 지정된 모델. 행이 없으면 null 이고, 그 경우 호출자는 요청에 모델을 싣지 않아 extractor 기본값으로 동작한다.
    fun modelOf(target: ExtractionTarget): String?
}

// DB(extraction_models) 기반 구현. 백오피스가 배포 없이 모델을 바꾼다 — 판정은 잦으므로(파싱마다) 매번 DB 를
// 치지 않고 메모리 캐시로 읽고, 백오피스 수정(afterCommit)과 주기 재적재가 reload() 로 갱신한다
// (DbExtractionRoutingPolicy 와 같은 패턴).
@Component
class DbExtractionModelSettings(
    private val repository: ExtractionModelJpaRepository,
) : ExtractionModelSettings {
    private val log = LoggerFactory.getLogger(javaClass)

    // 불변 Map 을 통째로 교체(@Volatile)한다 — reader(파싱 스레드)는 항상 옛/새 전체 중 하나만 본다.
    @Volatile
    private var models: Map<ExtractionTarget, String> = emptyMap()

    // @Synchronized 인 이유: 조회와 교체가 갈라져 있으면 늦게 시작한 재적재가 먼저 끝나는 역전이 생긴다.
    // 주기 재적재가 DB 를 읽는 사이 백오피스 저장의 afterCommit 재적재가 통째로 끝나 버리면, 뒤늦게 완료된
    // 주기 재적재가 방금 저장한 값을 옛 값으로 덮어쓴다 — 다음 주기(5분)까지 "저장했는데 안 바뀐" 상태가 된다.
    // load 전체를 직렬화하면 나중에 진입한 쪽이 항상 더 새 DB 를 읽으므로 최신이 남는다.
    @PostConstruct
    @Synchronized
    fun load() {
        models = findAll().mapValues { it.value.model }
    }

    // 백오피스 수정 직후(afterCommit)와 주기 재적재가 함께 부른다. 주기 재적재는 다른 인스턴스에서 바뀐 설정을
    // 이 인스턴스가 따라잡는 유일한 경로다 — blue-green 공존·수평 확장에서 stale 이 이 주기로 바운드된다.
    @Scheduled(fixedDelay = RELOAD_INTERVAL_MS)
    fun reload() = load()

    override fun modelOf(target: ExtractionTarget): String? = models[target]

    // 캐시 적재와 백오피스 화면이 함께 쓰는 단일 조회 지점. 화면이 캐시가 아니라 저장소를 직접 읽는 이유는
    // 캐시가 afterCommit 갱신이라 방금 저장한 값이 아직 안 보일 수 있어서다.
    //
    // tolerant reader — 이 바이너리가 모르는 target 행은 스킵하고 warn 만 남긴다. 그 경로는 모델 미지정으로
    // 취급돼 extractor 기본값으로 동작하므로, target 을 늘린 신버전에서 행을 만든 뒤 구버전으로 롤백해도
    // 파싱이 죽지 않는다 (DB 는 forward-only 라 행이 남는다).
    //
    // 인터페이스에 두지 않는 이유는 reload 와 같다 — 파싱 경로가 쓰지 않는 관리 기능이라 admin 이 구현을
    // 직접 주입받는다 (AdminExtractionPolicyService 가 DbExtractionRoutingPolicy 를 직접 받는 것과 같다).
    fun findAll(): Map<ExtractionTarget, ExtractionModelEntity> =
        repository
            .findAll()
            .mapNotNull { entity ->
                val target = ExtractionTarget.entries.find { it.name == entity.target }
                target ?: run {
                    log.warn("모르는 추출 target 을 스킵(해당 경로는 기본 모델): target={}", entity.target)
                    return@mapNotNull null
                }
                target to entity
            }.toMap()

    companion object {
        // stale 상한. 모델 변경은 사람 손의 백오피스 조작이라 분 단위 전파면 충분하다
        // (DbExtractionRoutingPolicy 와 같은 값·같은 이유).
        private const val RELOAD_INTERVAL_MS = 300_000L
    }
}
