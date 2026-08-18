package com.depromeet.piki.common.ratelimit

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

// 지금 적용 중인 한도 값의 단일 조회 지점. 인터페이스/구현 분리는 DomainAccessPolicy·ExtractionModelSettings 와
// 같은 구조 — 소비자(한도 게이트)의 단위 테스트가 DB 없이 값을 대체할 수 있게 한다.
interface ItemQuotaSettings {
    fun current(): ItemQuotaSnapshot
}

// DB(item_quota_settings) 기반 구현. 백오피스가 배포 없이 한도를 바꾼다(#934) — 판정은 잦으므로(등록마다)
// 매번 DB 를 치지 않고 메모리 캐시로 읽고, 백오피스 저장(afterCommit)과 주기 재적재가 reload() 로 갱신한다
// (DbExtractionModelSettings 와 같은 패턴).
//
// 오버라이드는 env 기본값 **위에 얹는다.** 행이 없거나 그 컬럼이 null 이면 그 노브는 env 값을 그대로 쓴다.
@Component
class DbItemQuotaSettings(
    private val repository: ItemQuotaSettingsJpaRepository,
    private val properties: ItemQuotaProperties,
) : ItemQuotaSettings {
    private val log = LoggerFactory.getLogger(javaClass)

    // 불변 스냅샷을 통째로 교체(@Volatile)한다 — reader(등록 스레드)는 항상 옛/새 전체 중 하나만 본다.
    // 초기값을 env 로 두는 이유: @PostConstruct 이전이나 DB 재적재 실패 창에서도 판정이 값 없이 멈추지 않는다.
    @Volatile
    private var snapshot: ItemQuotaSnapshot = ItemQuotaSnapshot.of(properties)

    // @Synchronized 인 이유: 조회와 교체가 갈라져 있으면 늦게 시작한 재적재가 먼저 끝나는 역전이 생긴다.
    // 주기 재적재가 DB 를 읽는 사이 백오피스 저장의 afterCommit 재적재가 통째로 끝나 버리면, 뒤늦게 완료된
    // 주기 재적재가 방금 저장한 값을 옛 값으로 덮어쓴다 — 다음 주기(5분)까지 "저장했는데 안 바뀐" 상태가 된다.
    @PostConstruct
    @Synchronized
    fun load() {
        // 재적재 실패(일시 DB 오류)에 기존 스냅샷을 유지한다 — 한도를 env 로 되돌리면 방금 조인 값이 조용히
        // 풀려 비용 방어가 사라진다. 실패는 다음 주기에 재시도된다.
        val entity =
            try {
                findOverride()
            } catch (e: Exception) {
                log.warn("아이템 한도 설정 재적재 실패 — 기존 값을 유지한다.", e)
                return
            }
        snapshot = ItemQuotaSnapshot.of(properties, entity)
    }

    // 백오피스 저장 직후(afterCommit)와 주기 재적재가 함께 부른다. 주기 재적재는 다른 인스턴스에서 바뀐 값을
    // 이 인스턴스가 따라잡는 유일한 경로다 — blue-green 공존·수평 확장에서 stale 이 이 주기로 바운드된다.
    @Scheduled(fixedDelay = RELOAD_INTERVAL_MS)
    fun reload() = load()

    override fun current(): ItemQuotaSnapshot = snapshot

    // 캐시 적재와 백오피스 화면이 함께 쓰는 단일 조회 지점. 화면이 캐시가 아니라 저장소를 직접 읽는 이유는
    // 캐시가 afterCommit 갱신이라 방금 저장한 값이 아직 안 보일 수 있어서다(DbExtractionModelSettings 와 같다).
    fun findOverride(): ItemQuotaSettingsEntity? =
        repository.findById(ItemQuotaSettingsEntity.SINGLE_ROW_ID).orElse(null)

    companion object {
        // stale 상한. 한도 변경은 사람 손의 백오피스 조작이라 분 단위 전파면 충분하다
        // (DbExtractionModelSettings 와 같은 값·같은 이유).
        private const val RELOAD_INTERVAL_MS = 300_000L
    }
}
