package com.depromeet.piki.admin.quota

import com.depromeet.piki.admin.audit.AdminAuditAction
import com.depromeet.piki.admin.audit.AdminAuditService
import com.depromeet.piki.admin.config.ConditionalOnAdminEnabled
import com.depromeet.piki.common.ratelimit.DbItemQuotaSettings
import com.depromeet.piki.common.ratelimit.ItemQuotaProperties
import com.depromeet.piki.common.ratelimit.ItemQuotaSettingsEntity
import com.depromeet.piki.common.ratelimit.ItemQuotaSettingsJpaRepository
import com.depromeet.piki.common.ratelimit.ItemQuotaSnapshot
import com.depromeet.piki.common.ratelimit.ItemQuotaUsage
import com.depromeet.piki.common.ratelimit.ItemQuotaUsageReader
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.LocalDateTime
import java.util.UUID

// 백오피스 아이템 등록 한도 관리(#934). 배포 없이 한도를 조이거나 푼다.
//
// **왜 필요한가**: 값이 env 에만 있으면 비용이 튀어 급히 조여야 할 때도, 한도가 낮아 정상 사용자가 막힐 때도
// 배포나 재시작을 기다려야 한다. 둘 다 분 단위가 아까운 상황이다.
@Service
@ConditionalOnAdminEnabled
class AdminItemQuotaService(
    private val settings: DbItemQuotaSettings,
    private val properties: ItemQuotaProperties,
    private val usageReader: ItemQuotaUsageReader,
    private val writer: ItemQuotaSettingsWriter,
) {
    // 화면은 저장소를 직접 읽는다(캐시가 아니라) — 캐시는 afterCommit 갱신이라 방금 저장한 값이 아직 안 보인다.
    @Transactional(readOnly = true)
    fun board(): AdminItemQuotaView {
        val override = settings.findOverride()
        return AdminItemQuotaView(
            effective = ItemQuotaSnapshot.of(properties, override),
            defaults = ItemQuotaSnapshot.of(properties),
            override = override,
            capacityUsage = usageReader.capacity(),
        )
    }

    @Transactional(readOnly = true)
    fun usageOf(rawUserId: String): AdminItemQuotaUserUsage {
        val userId =
            try {
                UUID.fromString(rawUserId.trim())
            } catch (e: IllegalArgumentException) {
                // 형식 오류는 화면에서 되돌려 줄 계약 위반이다. 예외 메시지에 입력값을 싣지 않는다.
                throw IllegalArgumentException("userId 형식이 올바르지 않습니다 (UUID).", e)
            }
        return AdminItemQuotaUserUsage(userId = userId, usage = usageReader.user(userId))
    }

    // 빈 칸은 "그 노브를 기본값으로 되돌린다" 는 뜻이다(null 저장). 네 칸을 한 번에 저장하므로 화면이 곧 최종 상태다.
    fun save(
        form: ItemQuotaSettingsForm,
        actor: String,
        clientIp: String?,
    ) {
        val previous = ItemQuotaSnapshot.of(properties, settings.findOverride())
        // 엔티티 생성자의 require 가 불변식 층이지만, 그 메시지는 개발자용이라 화면에 그대로 내보내지 않는다.
        // 사용자 대면 문구는 이 경계가 소유한다(CLAUDE.md "검증은 입력 경계와 엔티티 양쪽에").
        form.userLimit?.let { require(it > 0) { "계정 한도는 1 이상이어야 합니다 (0 이면 등록이 통째로 막힙니다)." } }
        form.capacityLimit?.let { require(it > 0) { "전역 상한은 1 이상이어야 합니다 (0 이면 모든 사용자가 막힙니다)." } }
        form.capacityAlertPercent?.let { require(it in 1..100) { "경고선은 1 에서 100 사이여야 합니다." } }
        val entity =
            ItemQuotaSettingsEntity(
                enabled = form.enabled,
                userLimit = form.userLimit,
                capacityLimit = form.capacityLimit,
                capacityAlertPercent = form.capacityAlertPercent,
            )
        writer.write(entity, previous, actor, clientIp)
    }

    fun reset(
        actor: String,
        clientIp: String?,
    ) = writer.reset(ItemQuotaSnapshot.of(properties, settings.findOverride()), actor, clientIp)
}

// 영속화 전용 빈. 같은 클래스 안에서 @Transactional 메서드를 부르면 Spring AOP proxy 를 거치지 않아 트랜잭션이
// 무력화되므로(self-invocation), 경계를 나누려면 빈 자체가 갈려야 한다(ExtractionModelWriter 와 같은 이유).
@Service
@ConditionalOnAdminEnabled
class ItemQuotaSettingsWriter(
    private val repository: ItemQuotaSettingsJpaRepository,
    private val settings: DbItemQuotaSettings,
    private val properties: ItemQuotaProperties,
    private val auditService: AdminAuditService,
) {
    // upsert — PK 가 상수라 save 가 늘 같은 행을 덮어쓴다. "지우고 새로 넣기" 로 수정하면 그 사이 전부
    // 기본값으로 돌아가는 창이 생긴다(ExtractionModelWriter.write 와 같은 이유).
    @Transactional
    fun write(
        entity: ItemQuotaSettingsEntity,
        previous: ItemQuotaSnapshot,
        actor: String,
        clientIp: String?,
    ) {
        repository.save(entity)
        record(previous, ItemQuotaSnapshot.of(properties, entity), actor, clientIp)
        reloadAfterCommit()
    }

    // 전체 초기화 — 행을 지워 네 노브를 한 번에 env 기본값으로 되돌린다. 급히 조인 값을 원복하는 자리다.
    @Transactional
    fun reset(
        previous: ItemQuotaSnapshot,
        actor: String,
        clientIp: String?,
    ) {
        repository.deleteById(ItemQuotaSettingsEntity.SINGLE_ROW_ID)
        record(previous, ItemQuotaSnapshot.of(properties), actor, clientIp)
        reloadAfterCommit()
    }

    // 바뀐 노브만 남긴다 — 매번 네 값을 다 적으면 로그에서 "이번에 무엇이 달라졌나" 를 사람이 다시 비교해야 한다.
    private fun record(
        before: ItemQuotaSnapshot,
        after: ItemQuotaSnapshot,
        actor: String,
        clientIp: String?,
    ) {
        val changes =
            listOfNotNull(
                diff("사용", before.enabled, after.enabled),
                diff("계정 한도", before.userLimit, after.userLimit),
                diff("전역 상한", before.capacityLimit, after.capacityLimit),
                diff("경고선(%)", before.capacityAlertPercent, after.capacityAlertPercent),
            )
        // 값이 그대로여도 기록은 남긴다 — "누가 이 화면에서 저장을 눌렀나" 자체가 추적 대상이고,
        // 변경 없음이 곧 "확인만 했다" 는 정보다.
        auditService.record(
            actor,
            AdminAuditAction.ITEM_QUOTA_UPDATE,
            changes.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "변경 없음",
            clientIp,
        )
    }

    private fun <T> diff(
        label: String,
        before: T,
        after: T,
    ): String? = if (before == after) null else "$label: $before → $after"

    // 캐시 갱신은 커밋 후로 미룬다 — 커밋 전 reload 면 이후 단계 롤백 시 캐시만 새 값으로 남아 DB 와 어긋난다.
    private fun reloadAfterCommit() {
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = settings.reload()
            },
        )
    }
}

// 화면 입력 한 벌. null 은 "그 노브는 기본값을 쓴다" 는 뜻이라 빈 칸이 그대로 전달된다.
data class ItemQuotaSettingsForm(
    val enabled: Boolean?,
    val userLimit: Int?,
    val capacityLimit: Int?,
    val capacityAlertPercent: Int?,
)

// effective 는 지금 판정에 쓰이는 값, defaults 는 오버라이드를 걷어냈을 때의 env 값이다. 둘을 나란히 보여야
// "이 값이 왜 이런가"(기본인가, 누가 바꾼 것인가)를 화면에서 바로 안다. override 가 null 이면 전부 기본값이다.
data class AdminItemQuotaView(
    val effective: ItemQuotaSnapshot,
    val defaults: ItemQuotaSnapshot,
    val override: ItemQuotaSettingsEntity?,
    val capacityUsage: ItemQuotaUsage,
) {
    val overriddenAt: LocalDateTime? get() = override?.updatedAt
}

data class AdminItemQuotaUserUsage(
    val userId: UUID,
    val usage: ItemQuotaUsage,
)
