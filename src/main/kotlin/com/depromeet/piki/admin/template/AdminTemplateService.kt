package com.depromeet.piki.admin.template

import com.depromeet.piki.admin.audit.AdminAuditAction
import com.depromeet.piki.admin.audit.AdminAuditService
import com.depromeet.piki.admin.config.ConditionalOnAdminEnabled
import com.depromeet.piki.notification.domain.NotificationKind
import com.depromeet.piki.notification.domain.NotificationType
import com.depromeet.piki.notification.repository.NotificationTemplateJpaRepository
import com.depromeet.piki.notification.service.DbNotificationTemplateProvider
import com.depromeet.piki.notification.service.NotificationTemplateRenderer
import com.depromeet.piki.notification.service.NotificationTemplateVariables
import com.depromeet.piki.notification.service.TemplateVariable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

// 백오피스 템플릿 관리(#250). 편집은 미래 발송에만 적용(렌더 결과는 발송 시점 freeze) — 여기선 템플릿 문자열·푸시 토글만 바꾼다.
// 수정 시: 변수 검증(선언 안 된 변수 차단) → 저장 → provider 캐시 reload → 감사 기록.
@Service
@ConditionalOnAdminEnabled
class AdminTemplateService(
    private val templateRepository: NotificationTemplateJpaRepository,
    private val renderer: NotificationTemplateRenderer,
    private val templateProvider: DbNotificationTemplateProvider,
    private val auditService: AdminAuditService,
) {
    fun list(): List<TemplateView> =
        templateRepository
            .findAll()
            // ANNOUNCEMENT 는 재사용 "템플릿"이 아니라 매번 등록하는 공지 내용이다 — /admin/announcements 에서 관리하므로
            // 편집기 목록에서 제외한다(이벤트 알림 8종만 노출). DB 의 ${title}/${body} 행은 dispatch passthrough 로 남는다.
            .filter { it.type != NotificationType.ANNOUNCEMENT }
            .sortedBy { it.type.ordinal }
            .map { entity ->
            TemplateView(
                type = entity.type,
                kind = kindOf(entity.type),
                title = entity.titleTemplate,
                body = entity.bodyTemplate,
                pushEnabled = entity.pushEnabled,
                variables = NotificationTemplateVariables.availableFor(entity.type),
            )
        }

    fun get(type: NotificationType): TemplateView {
        val entity = templateRepository.findById(type).orElseThrow { IllegalStateException("템플릿 없음: $type") }
        return TemplateView(
            type = entity.type,
            kind = kindOf(entity.type),
            title = entity.titleTemplate,
            body = entity.bodyTemplate,
            pushEnabled = entity.pushEnabled,
            variables = NotificationTemplateVariables.availableFor(entity.type),
        )
    }

    @Transactional
    fun update(
        type: NotificationType,
        title: String,
        body: String,
        pushEnabled: Boolean,
        actor: String,
        clientIp: String?,
    ) {
        require(type != NotificationType.ANNOUNCEMENT) { "ANNOUNCEMENT 는 템플릿으로 수정할 수 없습니다." }
        validateLengths(title, body)
        validateVariables(type, title, body)
        val entity = templateRepository.findById(type).orElseThrow { IllegalStateException("템플릿 없음: $type") }
        entity.update(title, body, pushEnabled)
        templateRepository.save(entity)
        auditService.record(
            actor,
            AdminAuditAction.TEMPLATE_UPDATE,
            "$type 템플릿 수정 (푸시 ${if (pushEnabled) "ON" else "OFF"})",
            clientIp,
        )
        // 캐시 갱신은 커밋 후로 미룬다 — 커밋 전 reload 면 이후 단계(감사 저장 등) 롤백 시 캐시만 새 문구로 남아 DB 와 어긋난다.
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = templateProvider.reload()
            },
        )
    }

    // 실시간 미리보기 — 샘플값으로 렌더한 결과 + 선언 안 된 변수(있으면 경고).
    fun preview(
        type: NotificationType,
        title: String,
        body: String,
    ): TemplatePreview {
        require(type != NotificationType.ANNOUNCEMENT) { "ANNOUNCEMENT 는 미리보기 대상이 아닙니다." }
        val samples = NotificationTemplateVariables.sampleValues(type)
        val unknown = NotificationTemplateVariables.usedIn(title, body) - NotificationTemplateVariables.names(type)
        return TemplatePreview(
            title = renderer.render(title, samples),
            body = renderer.render(body, samples),
            kind = kindOf(type),
            unknownVariables = unknown.toList(),
        )
    }

    // 편집기는 알림 "타입" 자체를 다루지 특정 알림 인스턴스를 다루지 않아 라우팅 출처가 없다(routingKind = null).
    // 파싱 알림 템플릿은 위시·토너먼트 양쪽 발행에 공유되므로 어느 한쪽으로 못 정하고, of 의 기본값(위시)으로 표시된다.
    // 표시용 분류일 뿐이라 문구 편집·발송에는 영향이 없다(발송 시점 kind 는 실제 라우팅 출처로 파생된다).
    private fun kindOf(type: NotificationType): NotificationKind = NotificationKind.of(type, routingKind = null)

    private fun validateVariables(
        type: NotificationType,
        title: String,
        body: String,
    ) {
        val unknown = NotificationTemplateVariables.usedIn(title, body) - NotificationTemplateVariables.names(type)
        require(unknown.isEmpty()) { "이 타입에 없는 변수: ${unknown.joinToString(", ") { "\${$it}" }}" }
    }

    // DB 컬럼 한계(title 255 · body 500)를 서비스에서 먼저 막아 운영자 입력 실수를 400(사용자 오류)으로 처리한다.
    // 안 막으면 커밋 시점 DB 제약 위반으로 500 이 난다. 컨트롤러가 IllegalArgumentException 을 잡아 편집 화면에 에러를 보인다.
    private fun validateLengths(
        title: String,
        body: String,
    ) {
        require(title.length <= TITLE_MAX_LENGTH) { "제목은 ${TITLE_MAX_LENGTH}자를 초과할 수 없습니다." }
        require(body.length <= BODY_MAX_LENGTH) { "본문은 ${BODY_MAX_LENGTH}자를 초과할 수 없습니다." }
    }

    companion object {
        private const val TITLE_MAX_LENGTH = 255
        private const val BODY_MAX_LENGTH = 500
    }
}

data class TemplateView(
    val type: NotificationType,
    val kind: NotificationKind,
    val title: String,
    val body: String,
    val pushEnabled: Boolean,
    val variables: List<TemplateVariable>,
) {
    // 백오피스 배지의 한글 라벨. NotificationKind 는 클라 응답에 실리는 enum 이라 표시 문구를 담지 않는다
    // (문구는 각 소비자가 소유한다 — 에러 code 와 같은 결). 이 화면의 라벨은 이 화면 모델이 진다.
    // when 이 전수라 kind 가 늘면 여기서 컴파일이 깨져 라벨 누락을 막는다.
    val kindLabel: String
        get() =
            when (kind) {
                NotificationKind.WISH -> "위시"
                NotificationKind.TOURNAMENT -> "토너먼트"
                NotificationKind.SYSTEM -> "시스템"
            }
}

data class TemplatePreview(
    val title: String,
    val body: String,
    val kind: NotificationKind,
    val unknownVariables: List<String>,
)
