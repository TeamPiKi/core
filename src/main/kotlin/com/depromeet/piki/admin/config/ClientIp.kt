package com.depromeet.piki.admin.config

import jakarta.servlet.http.HttpServletRequest
import java.net.InetAddress

// 접속자 실제 IP 추출 — 게이트(#526)의 IP allowlist 키라 위조되면 안 된다.
// nginx 는 X-Forwarded-For 를 append 로 넣어 클라 위조분이 첫 hop 에 섞이므로 쓰지 않고, nginx 가 $remote_addr(실
// 연결 IP)로 '덮어쓰는' X-Real-IP 를 신뢰한다(앞단 ALB/CloudFront 없는 EIP 직결이라 $remote_addr 이 진짜 클라 IP).
//
// 앱은 `-p 127.0.0.1:PORT`(deploy.yml)로 호스트 loopback 에만 바인딩돼 외부가 직접 못 닿고 nginx 로만 들어온다.
// 단 docker bridge + userland-proxy 가 source 를 docker0 gateway(예: 172.17.0.1)로 SNAT 하므로, 앱이 보는
// remoteAddr 은 loopback 이 아니라 사설대역이다. 과거 loopback 만 신뢰해 이 gateway 뒤의 X-Real-IP 를 버린 탓에
// 모든 요청 IP 가 gateway 하나로 뭉개져 allowlist 격리가 통째로 무력화됐다(2026-07-14: grant 한 번에 전원 통과).
// 그래서 loopback + 사설대역(RFC1918)을 신뢰 프록시로 본다.
//
// 이 신뢰는 "앱이 nginx 뒤, 사설/loopback 에서만 닿는다"는 배포 불변식에 의존한다 — 0.0.0.0 바인딩 등으로 공인망
// 직접 접근이 열리면 X-Real-IP 위조가 가능해지므로 그 땐 이 신뢰 범위를 재검토해야 한다. nginx 없는 로컬·테스트는
// remoteAddr 로 폴백한다.
object ClientIp {
    private const val REAL_IP = "X-Real-IP"

    // X-Real-IP 는 신뢰 프록시(nginx·docker gateway)가 덮어쓴 값일 때만 신뢰한다. 그 외(공인 IP 직접 접근 등)는
    // 헤더가 위조됐을 수 있어 채택하지 않고 remoteAddr 를 쓴다.
    fun of(request: HttpServletRequest): String {
        val remote = request.remoteAddr
        if (isTrustedProxy(remote)) {
            request.getHeader(REAL_IP)?.trim()?.ifBlank { null }?.let { return it }
        }
        return remote
    }

    // 신뢰 프록시 = loopback(127/8·::1) + 사설대역(RFC1918 10/8·172.16–31·192.168)·link-local. nginx·docker
    // gateway 가 전부 여기 든다. InetAddress 로 대역을 판정해 gateway IP 하드코딩(변동에 깨짐)을 피한다.
    private fun isTrustedProxy(ip: String): Boolean =
        runCatching {
            val addr = InetAddress.getByName(ip)
            addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress
        }.getOrDefault(false)
}
