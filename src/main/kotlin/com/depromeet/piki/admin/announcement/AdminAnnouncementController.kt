package com.depromeet.piki.admin.announcement
import io.swagger.v3.oas.annotations.Hidden
import com.depromeet.piki.announcement.domain.Announcement
import com.depromeet.piki.announcement.domain.AnnouncementImageException

import com.depromeet.piki.admin.access.AdminSession
import com.depromeet.piki.admin.config.ClientIp
import com.depromeet.piki.admin.config.ConditionalOnAdminEnabled
import com.depromeet.piki.common.response.ApiResponseBody
import com.depromeet.piki.notification.service.DefaultPushImage
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import java.time.LocalDateTime

// 공지 등록·예약/발송·결과 화면(#391/#489). 등록(초안)과 발송(목록에서 선택)을 분리해 발송 시 자유입력이 없어 오타가 안 생긴다.
// 발송은 즉시 또는 예약(시각 지정), 결과는 집계+진행률 폴링으로 본다. 게이트(슬랙-세션)·actor 신원은 #526.
@Hidden
@Controller
@ConditionalOnAdminEnabled
@RequestMapping("/admin/announcements")
class AdminAnnouncementController(
    private val adminAnnouncementService: AdminAnnouncementService,
    private val defaultPushImage: DefaultPushImage,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping
    fun page(
        @RequestParam(defaultValue = "0") page: Int,
        model: Model,
    ): String {
        val announcements = adminAnnouncementService.list(page)
        model.addAttribute("announcements", announcements.content)
        model.addAttribute("page", announcements.number)
        model.addAttribute("hasPrev", announcements.hasPrevious())
        model.addAttribute("hasNext", announcements.hasNext())
        model.addAttribute("totalPages", announcements.totalPages)
        model.addAttribute("recipientCount", adminAnnouncementService.recipientCount())
        return "admin/announcements"
    }

    // 새 공지 작성 — 빈 초안(stub-create)을 만들고 곧장 작성 화면으로 보낸다(#561). 제목·본문·이미지·푸시는 거기서 채운다.
    // 이미지 즉시 업로드가 announcement/{id}/ 키를 쓰려면 작성 시점에 id 가 있어야 해서, 빈 초안으로 id 를 먼저 확보한다.
    @PostMapping
    fun register(request: HttpServletRequest): String {
        val announcement = adminAnnouncementService.register(actor(request), clientIp(request))
        return "redirect:/admin/announcements/${announcement.getId()}/edit"
    }

    // 본문 이미지 즉시 업로드(#561) — 수정 화면 에디터(addImageBlobHook)가 로컬 파일을 보내면 S3 에 올리고 공개 URL 을 돌려준다.
    // 검증 실패(형식·매직바이트)·비-DRAFT 는 예외 → GlobalExceptionHandler 가 ApiResponseBody.fail 로 매핑(에디터 hook 이 처리).
    @PostMapping("/{id}/images")
    @ResponseBody
    fun uploadImage(
        @PathVariable id: Long,
        @RequestParam("image") image: MultipartFile,
    ): ApiResponseBody<AnnouncementImageUploadResponse> =
        ApiResponseBody.ok(AnnouncementImageUploadResponse(adminAnnouncementService.uploadImage(id, image.bytes, image.contentType)))

    // 초안 수정 페이지(#561) — DRAFT 만. 기존 내용·푸시 설정을 에디터·필드에 채워 보여준다. 발송·예약 건은 목록으로 되돌린다.
    @GetMapping("/{id}/edit")
    fun editPage(
        @PathVariable id: Long,
        model: Model,
    ): String {
        val announcement = adminAnnouncementService.get(id)
        if (!announcement.isDraft) return "redirect:/admin/announcements" // 발송·예약된 건 내용 수정 불가
        model.addAttribute("announcement", announcement)
        model.addAttribute("pushIconUrl", defaultPushImage.url) // 푸시 미리보기 아이콘 — 실제 FCM 이 쓰는 S3 기본 아이콘
        return "admin/announcement-edit"
    }

    // 초안 수정(DRAFT 만, 발송 전 오타 교정 #561). 발송된·예약된 공지는 서비스·엔티티가 거부한다.
    @PostMapping("/{id}/edit")
    fun update(
        @PathVariable id: Long,
        @RequestParam title: String,
        @RequestParam(required = false) body: String?,
        @RequestParam(defaultValue = "false") pushEnabled: Boolean,
        @RequestParam(required = false) pushTitle: String?,
        @RequestParam(required = false) pushBody: String?,
        redirectAttributes: RedirectAttributes,
        request: HttpServletRequest,
    ): String {
        val safeBody = body ?: ""
        val safePushTitle = pushTitle ?: ""
        val safePushBody = pushBody ?: ""
        // 검증·rehost 실패로 edit 로 되돌릴 땐 방금 입력한 값을 flash 로 보존해 재주입한다 — 긴 패치노트를 다시 쓰게 하지 않는다.
        if (!validLengths(title, safeBody, safePushTitle, safePushBody)) {
            preserveDraftInput(redirectAttributes, title, safeBody, pushEnabled, safePushTitle, safePushBody)
            return "redirect:/admin/announcements/$id/edit?error=length"
        }
        return try {
            adminAnnouncementService.update(id, title, safeBody, pushEnabled, safePushTitle, safePushBody, actor(request), clientIp(request))
            "redirect:/admin/announcements/$id/send"
        } catch (e: AnnouncementImageException) {
            // 클라 계약 위반(운영자가 넣은 이미지/주소 문제)이라 info — id 로 운영자 제보와 상관시키고, 사유(어떤 실패 팩토리)를 남긴다.
            // 차단 host·status 같은 근본 원인은 fetcher 가 이미 warn 으로 남기므로(상관용), 여기선 스택을 남기지 않는다(스택은 error/서버버그용).
            log.info("공지 이미지 처리 실패로 수정 폼 복귀: announcementId={}, reason={}", id, e.message)
            preserveDraftInput(redirectAttributes, title, safeBody, pushEnabled, safePushTitle, safePushBody)
            "redirect:/admin/announcements/$id/edit?error=image"
        }
    }

    // edit 로 되돌릴 때 입력값을 flash 로 보존한다 — editPage 의 다음 렌더에서 draft* 모델 속성으로 폼에 재주입된다(없으면 DRAFT 값).
    private fun preserveDraftInput(
        ra: RedirectAttributes,
        title: String,
        body: String,
        pushEnabled: Boolean,
        pushTitle: String,
        pushBody: String,
    ) {
        ra.addFlashAttribute("draftTitle", title)
        ra.addFlashAttribute("draftBody", body)
        ra.addFlashAttribute("draftPushEnabled", pushEnabled)
        ra.addFlashAttribute("draftPushTitle", pushTitle)
        ra.addFlashAttribute("draftPushBody", pushBody)
    }

    // 입력 경계 길이 검증 — 등록·수정 공용. 초과는 친화적으로 막는다(엔티티 검증이 최후의 보루).
    private fun validLengths(
        title: String,
        body: String,
        pushTitle: String,
        pushBody: String,
    ): Boolean =
        title.isNotBlank() &&
            title.length <= Announcement.MAX_TITLE_LENGTH &&
            body.length <= Announcement.MAX_BODY_LENGTH &&
            pushTitle.length <= Announcement.MAX_PUSH_TEXT_LENGTH &&
            pushBody.length <= Announcement.MAX_PUSH_TEXT_LENGTH

    // 발송/예약 설정 페이지 — 발송 전 "대상자 추출"(토큰 보유자 수)·내용·푸시 미리보기 + 발송 시각 선택. 실제 발송은 아래 POST.
    @GetMapping("/{id}/send")
    fun confirmSend(
        @PathVariable id: Long,
        model: Model,
    ): String {
        val announcement = adminAnnouncementService.get(id)
        if (!announcement.isDraft) return "redirect:/admin/announcements" // 발송됐거나 예약된 건 재설정 불가
        model.addAttribute("announcement", announcement)
        model.addAttribute("recipientCount", adminAnnouncementService.recipientCount())
        // 푸시 미리보기 아이콘 — 실제 FCM 푸시(시스템 알림)가 쓰는 S3 기본 아이콘(defaults/push-icon.svg)을 그대로 보여준다.
        model.addAttribute("pushIconUrl", defaultPushImage.url)
        return "admin/announcement-send"
    }

    // 발송 확정 — scheduledAt 비우면 즉시, 채우면 그 시각으로 예약. 즉시면 결과 화면으로, 예약이면 목록으로.
    @PostMapping("/{id}/send")
    fun send(
        @PathVariable id: Long,
        @RequestParam(required = false) scheduledAt: String?,
        request: HttpServletRequest,
    ): String {
        // 잘못된 형식의 scheduledAt(직접 POST·UI 버그 등)이 500 으로 떨어지지 않게 친화적으로 막는다.
        val at =
            scheduledAt?.trim()?.ifBlank { null }?.let {
                runCatching { LocalDateTime.parse(it) }.getOrElse { return "redirect:/admin/announcements/$id/send?error=time" }
            }
        adminAnnouncementService.schedule(id, at, actor = actor(request), clientIp = clientIp(request))
        // 예약이면 목록에서 예약 상태를 보고, 즉시면 바로 결과(진행률) 화면으로 보낸다.
        return at?.let { "redirect:/admin/announcements?scheduled" } ?: "redirect:/admin/announcements/$id/result"
    }

    // 예약 취소 — SCHEDULED → DRAFT.
    @PostMapping("/{id}/cancel")
    fun cancel(
        @PathVariable id: Long,
        request: HttpServletRequest,
    ): String {
        adminAnnouncementService.cancelSchedule(id, actor = actor(request), clientIp = clientIp(request))
        return "redirect:/admin/announcements?canceled"
    }

    // 발송 결과 화면 — 집계(성공·실패·코드별)와 진행률 %. SENDING 동안 result.json 을 폴링해 갱신한다.
    @GetMapping("/{id}/result")
    fun result(
        @PathVariable id: Long,
        model: Model,
    ): String {
        model.addAttribute("result", adminAnnouncementService.result(id))
        return "admin/announcement-result"
    }

    // 진행률·집계 폴링용 JSON — 내부 SSR 엔드포인트도 공통 응답 래퍼(ApiResponseBody)로 통일한다.
    @GetMapping("/{id}/result.json")
    @ResponseBody
    fun resultJson(
        @PathVariable id: Long,
    ): ApiResponseBody<AnnouncementResult> = ApiResponseBody.ok(adminAnnouncementService.result(id))

    // 등록한 초안 삭제 (발송·예약된 건 불가).
    @PostMapping("/{id}/delete")
    fun delete(
        @PathVariable id: Long,
    ): String {
        adminAnnouncementService.delete(id)
        return "redirect:/admin/announcements?deleted"
    }

    // 감사 actor — Discord 게이트(#526·#654)가 세션에 바인딩한 신원. 게이트를 우회하는 로컬(admin.enabled)엔 세션이 없어 "운영자" 로 폴백.
    private fun actor(request: HttpServletRequest): String =
        request.getSession(false)?.let { AdminSession.actorName(it) } ?: "운영자"

    // 게이트와 동일한 안전 추출(X-Real-IP, 위조 불가)을 audit 에도 쓴다 — XFF 첫 hop 은 스푸핑 가능.
    private fun clientIp(request: HttpServletRequest): String = ClientIp.of(request)
}

// 본문 이미지 업로드 응답(#561) — 에디터 addImageBlobHook 이 data.url 을 본문에 삽입한다.
data class AnnouncementImageUploadResponse(
    val url: String,
)
