package com.depromeet.piki.admin.access

import com.depromeet.piki.admin.config.ClientIp
import com.depromeet.piki.admin.config.ConditionalOnAdminEnabled
import io.swagger.v3.oas.annotations.Hidden
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// admin 세션 남은시간 조회·명시적 연장(#669). /admin/** 이라 AdminAccessFilter 게이트를 통과한 요청만 닿는다
// (세션·IP 바인딩·allowlist 확인됨). 게이트에서 sliding refresh 를 없앴으므로, 연장은 오직 이 extend 로만 일어난다.
@Hidden
@RestController
@ConditionalOnAdminEnabled
@RequestMapping("/admin/session")
class AdminSessionController(
    private val allowlistService: AdminAllowlistService,
) {
    // 남은 세션 시간(초). 카운트다운 UI 가 폴링한다. 만료·미등록이면 0.
    @GetMapping("/ttl")
    fun ttl(request: HttpServletRequest): Map<String, Long> = mapOf("remainingSeconds" to remainingSeconds(ClientIp.of(request)))

    // 명시적 연장 — allowlist TTL 을 다시 채운다. 연장 버튼 클릭으로만 호출된다(배경 요청으로는 갱신되지 않음).
    @PostMapping("/extend")
    fun extend(request: HttpServletRequest): Map<String, Long> {
        val ip = ClientIp.of(request)
        allowlistService.refresh(ip)
        return mapOf("remainingSeconds" to remainingSeconds(ip))
    }

    private fun remainingSeconds(ip: String): Long = allowlistService.ttl(ip)?.seconds ?: 0L
}
