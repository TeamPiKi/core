package com.depromeet.piki.admin.extraction

import com.depromeet.piki.admin.audit.AdminAuditAction
import com.depromeet.piki.admin.audit.AdminAuditService
import com.depromeet.piki.admin.config.ConditionalOnAdminEnabled
import com.depromeet.piki.product.service.remote.DbExtractionModelSettings
import com.depromeet.piki.product.service.remote.ExtractionModelEntity
import com.depromeet.piki.product.service.remote.ExtractionModelJpaRepository
import com.depromeet.piki.product.service.remote.ExtractionModelProbe
import com.depromeet.piki.product.service.remote.ExtractionTarget
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.LocalDateTime

// 백오피스 추출 모델 관리(#875). 배포 없이 경로(LINK · IMAGE)별 LLM 모델을 지정·해제한다.
//
// 쓰기 경로(save · check)에 @Transactional 이 없는 것이 의도다 — 프로브가 extractor 를 거쳐 Gemini 를 실제로
// 부르는 외부 호출이라, 트랜잭션 안에 넣으면 그 왕복 동안 DB 커넥션을 잡는다(CLAUDE.md 트랜잭션 경계).
// 영속화만 ExtractionModelWriter 에 위임해 짧은 트랜잭션으로 묶는다. 외부 호출이 없는 조회는 그 사정과
// 무관하므로 형제 서비스(AdminExtractionPolicyService.board)와 같게 readOnly 트랜잭션을 연다.
@Service
@ConditionalOnAdminEnabled
class AdminExtractionModelService(
    private val settings: DbExtractionModelSettings,
    private val probe: ExtractionModelProbe,
    private val writer: ExtractionModelWriter,
) {
    // 지정 여부와 무관하게 모든 경로를 한 행씩 보여준다 — 미지정도 "기본값으로 동작 중"이라는 정보다.
    @Transactional(readOnly = true)
    fun board(): List<ExtractionModelView> {
        val rows = settings.findAll()
        return ExtractionTarget.entries.map { target ->
            val entity = rows[target]
            ExtractionModelView(target = target, model = entity?.model, updatedAt = entity?.updatedAt)
        }
    }

    // 저장 게이트 — 프로브가 성공한 모델만 등록된다. 아는 모델 목록을 코드에 박지 않으므로(그러면 새 모델마다
    // 배포가 필요해진다) 유효성은 런타임 실측이 판정한다. 프로브가 던지는 예외 메시지가 그대로 화면 사유가 된다.
    fun save(
        target: ExtractionTarget,
        rawModel: String,
        actor: String,
        clientIp: String?,
    ) {
        val model = normalize(rawModel)
        probe.verify(target, model)
        writer.write(target, model, actor, clientIp)
    }

    // 온디맨드 확인 — 등록 당시엔 유효했어도 모델은 사라진다(preview 는 2주 공지 후 deprecate). 그 경우 extractor 가
    // 기본 모델로 fallback 해 파싱은 계속 되지만, 화면에 적힌 모델과 실제로 도는 모델이 갈린 채 아무도 모르게 된다.
    // 이 버튼이 그 유령 상태를 운영자가 직접 확인하는 자리다.
    fun check(target: ExtractionTarget) {
        val model = settings.modelOf(target) ?: throw IllegalArgumentException("지정된 모델이 없습니다 (extractor 기본 모델로 동작 중).")
        probe.verify(target, model)
    }

    fun clear(
        target: ExtractionTarget,
        actor: String,
        clientIp: String?,
    ) = writer.clear(target, actor, clientIp)

    // 입력 정규화 + 검증. 모델명 자체의 유효성은 프로브가 보므로 여기서는 "프로브까지 갈 가치가 없는 입력"만 거른다
    // (빈 값 · 경로를 통째로 붙여넣은 실수 · 컬럼 길이 초과). 소문자화 같은 변형은 하지 않는다 — 모델명 대소문자
    // 규칙은 우리가 정하는 것이 아니라 제공자 것이고, 임의로 바꾸면 멀쩡한 입력이 없는 모델이 된다.
    private fun normalize(rawModel: String): String {
        val model = rawModel.trim()
        require(model.isNotBlank()) { "모델명을 입력해 주세요." }
        require(!model.contains('/')) { "모델명만 입력해 주세요 (경로 제외, 예: gemini-3.1-flash-lite)" }
        require(!model.contains(' ')) { "모델명에 공백이 들어갈 수 없습니다." }
        require(model.length <= ExtractionModelEntity.MODEL_MAX_LENGTH) {
            "모델명은 ${ExtractionModelEntity.MODEL_MAX_LENGTH}자를 초과할 수 없습니다."
        }
        return model
    }
}

// 영속화 전용 빈. 같은 클래스 안에서 @Transactional 메서드를 부르면 Spring AOP proxy 를 거치지 않아 트랜잭션이
// 무력화되므로(self-invocation), 외부 호출과 영속화의 경계를 나누려면 빈 자체가 갈려야 한다.
@Service
@ConditionalOnAdminEnabled
class ExtractionModelWriter(
    private val repository: ExtractionModelJpaRepository,
    private val settings: DbExtractionModelSettings,
    private val auditService: AdminAuditService,
) {
    // upsert — 같은 target 이 있으면 교체한다. "해제 후 재지정"으로 수정하게 하면 그 사이 기본 모델로 돌아가는
    // 공백 창이 생긴다 (AdminExtractionPolicyService.save 와 같은 이유).
    @Transactional
    fun write(
        target: ExtractionTarget,
        model: String,
        actor: String,
        clientIp: String?,
    ) {
        val previous = repository.findById(target.name).map { it.model }.orElse(null)
        repository.save(ExtractionModelEntity(target = target.name, model = model))
        auditService.record(
            actor,
            AdminAuditAction.EXTRACTION_MODEL_UPDATE,
            "$target: ${previous ?: "기본"} → $model",
            clientIp,
        )
        reloadAfterCommit()
    }

    @Transactional
    fun clear(
        target: ExtractionTarget,
        actor: String,
        clientIp: String?,
    ) {
        val entity = repository.findById(target.name).orElseThrow { IllegalArgumentException("지정된 모델이 없습니다.") }
        repository.delete(entity)
        auditService.record(
            actor,
            AdminAuditAction.EXTRACTION_MODEL_UPDATE,
            "$target: ${entity.model} → 기본",
            clientIp,
        )
        reloadAfterCommit()
    }

    // 캐시 갱신은 커밋 후로 미룬다 — 커밋 전 reload 면 이후 단계 롤백 시 캐시만 새 모델로 남아 DB 와 어긋난다.
    private fun reloadAfterCommit() {
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = settings.reload()
            },
        )
    }
}

// model 이 null 이면 미지정 — 그 경로는 요청에 모델을 싣지 않아 extractor 기본 모델로 동작한다.
data class ExtractionModelView(
    val target: ExtractionTarget,
    val model: String?,
    val updatedAt: LocalDateTime?,
)
