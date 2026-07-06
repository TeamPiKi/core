package com.depromeet.piki.product.service.http

import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.domain.ProductLinkException
import com.depromeet.piki.product.service.PageContent
import com.depromeet.piki.product.service.PageFetcher
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.nio.charset.Charset

// fetch 용 RestClient 와 host→IP 해석(dnsResolver)을 생성자로 주입받는다. 둘 다 밖에서 교체할 수 있어야
// 네트워크 없이 redirect 루프(3xx 따라가기·cross-domain 따라가기·다운그레이드 차단·hop 상한)를 단위 테스트로 검증할 수 있다.
//
// dnsResolver 는 PageFetchHttpClientConfig 의 RestClient 와 같은 인스턴스를 공유한다 — 가드가 검증한 IP 로 실제
// 연결도 이뤄지게(IP pin) 해 DNS rebinding/TOCTOU 를 닫는다. 한 fetch 가 끝나면 clear() 로 캐시를 비운다.
@Component
class HttpPageFetcher(
    // RestClient 빈이 둘(pageFetchRestClient·remoteExtractionRestClient)이라 대상을 명시한다 —
    // 타입 단일 후보에 기대던 기존 주입은 빈 추가만으로 조용히 깨지는 잠재 결함이었다.
    @Qualifier("pageFetchRestClient") private val restClient: RestClient,
    private val dnsResolver: RequestScopedDnsResolver,
) : PageFetcher {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun fetch(link: ProductLink): PageContent =
        try {
            fetchFollowingRedirects(link)
        } finally {
            // 요청 스코프 DNS 캐시를 비워 다음 fetch 와 격리한다(이번 요청이 본 IP 가 다음 요청으로 새지 않게).
            dnsResolver.clear()
        }

    private fun fetchFollowingRedirects(link: ProductLink): PageContent {
        var current = link
        repeat(MAX_REDIRECTS + 1) {
            guardAgainstInternalHost(current)
            val response = request(current)
            // Location 을 가진 진짜 redirect 코드(301/302/303/307/308)만 따라간다.
            // 304 Not Modified·300 Multiple Choices·305 Use Proxy 같은 비-redirect 3xx 는 일반 응답처럼 body 로 처리한다.
            if (response.statusCode.value() in REDIRECT_CODES) {
                current = nextRedirect(current, response)
                return@repeat
            }
            val bytes =
                response.body ?: run {
                    log.warn("link fetch empty body url={}", current.safeLogString())
                    throw PageFetchException.emptyBody()
                }
            // 응답을 바이트로 받아 charset 을 직접 정해 디코딩한다. RestClient 의 String 변환은 Content-Type 에 charset 이
            // 없으면 ISO-8859-1 로 떨어져(카카오 등 UTF-8 무헤더 페이지) 한글이 깨졌다(mojibake). 헤더 charset → HTML meta → UTF-8 순.
            val html = decodeHtml(bytes, response.headers.contentType?.charset)
            val truncated = if (html.length > MAX_FETCH_CHARS) html.substring(0, MAX_FETCH_CHARS) else html
            // link 는 사용자가 등록한 원본 URL 을 유지하고, html 은 redirect 를 따라간 최종 페이지로 채운다.
            // finalUrl 은 그 최종 페이지의 URL(current) — 상대 URL resolve 의 baseUri 가 원본이 아닌 최종 host 기준이 되게 한다.
            return PageContent(link = link, html = truncated, finalUrl = current)
        }
        log.warn("link fetch too many redirects url={}", link.safeLogString())
        throw PageFetchException.tooManyRedirects()
    }

    // String 오버로드는 URI 템플릿으로 해석되어 {q} 같은 쿼리가 변수로 치환될 수 있으므로 URI 로 명시 전달.
    // toEntity 로 3xx status·Location 에 접근한다(기본 onStatus 는 4xx/5xx 만 에러로 던지므로 3xx 는 정상 수신).
    private fun request(current: ProductLink): ResponseEntity<ByteArray> =
        try {
            restClient
                .get()
                .uri(current.value)
                .retrieve()
                .toEntity(ByteArray::class.java)
        } catch (e: RestClientResponseException) {
            log.warn("link fetch failed: status={} url={}", e.statusCode, current.safeLogString())
            throw when {
                // 500/501 은 봇 차단처럼 결정론적 영구 실패가 흔해 재시도하지 않고(SERVER_ERROR), 헤드리스면 뚫릴 수 있어
                // escalatable 로 던진다. 대형 몰은 상시 가용이라 우리가 받는 500/501 은 대개 봇 방어이고, body 유무로 장애/차단이
                // 깔끔히 안 갈려 body 를 구분하지 않는다. 502/503/504 는 일시(게이트웨이·과부하·타임아웃)일 수 있어 재시도(RETRYABLE) 대상.
                e.statusCode.value() in PERMANENT_SERVER_ERRORS -> PageFetchException.permanentUpstreamError(e)
                e.statusCode.is5xxServerError -> PageFetchException.upstreamError(e)
                // 그 외 4xx(403·404·410·429 등)는 봇 방어의 클로킹일 수 있어 clientError 가 escalatable 로 던진다(무조건 폴백).
                else -> PageFetchException.clientError(e)
            }
        } catch (e: ResourceAccessException) {
            log.warn("link fetch upstream error url={}", current.safeLogString())
            throw PageFetchException.upstreamError(e)
        }

    // 3xx 응답의 Location 을 절대 URI 로 만들고, https 면 다음 hop 으로 따라간다.
    // cross-domain redirect 도 따라간다(무신사 OneLink·bit.ly 등 단축·딥링크가 다른 도메인의 최종 상품 페이지로 보낸다).
    // 사설망 SSRF 는 매 hop 의 guardAgainstInternalHost(IP 검증)가 막으므로, 도메인 단위 차단 없이도 내부망 접근은 닫혀 있다.
    private fun nextRedirect(
        current: ProductLink,
        response: ResponseEntity<ByteArray>,
    ): ProductLink {
        val location = redirectLocation(current, response)
        // 상대 Location(/path)도 원본 URI 에 resolve 해 절대 URI 로 만든다.
        val target = current.value.resolve(location)
        // https 강제(http 다운그레이드 차단)와 형식 검증을 ProductLink.parse 가 한다. https 가 아니거나(다운그레이드)
        // 형식이 깨진 redirect 는 따라가지 않는다.
        return try {
            ProductLink.parse(target.toString())
        } catch (e: ProductLinkException) {
            log.warn("link fetch redirect rejected (non-https or malformed) url={}", current.safeLogString())
            throw PageFetchException.blockedHost()
        }
    }

    // Location 헤더를 URI 로 읽는다. 외부 서버가 깨진 Location(잘못된 이스케이프 등)을 주거나 3xx 인데 Location 을
    // 안 주면, 우리 버그가 아니라 대상 서버의 비정상 redirect 응답이므로 malformedRedirect(SERVER_ERROR/502)로 귀결시킨다.
    // 둘 다 재시도해도 결정론적으로 재실패하는 영구 오류라 RETRYABLE(upstreamError)이 아니다.
    private fun redirectLocation(
        current: ProductLink,
        response: ResponseEntity<ByteArray>,
    ): URI {
        val location =
            try {
                response.headers.location
            } catch (e: IllegalArgumentException) {
                log.warn("link fetch malformed Location url={}", current.safeLogString())
                throw PageFetchException.malformedRedirect(e)
            }
        return location ?: run {
            log.warn("link fetch redirect without Location url={}", current.safeLogString())
            throw PageFetchException.malformedRedirect()
        }
    }

    // host 가 외부 라우팅 불가 주소(loopback/사설/링크로컬/메타데이터/IPv6 ULA)로 resolve 되면 SSRF 위험으로 차단.
    // 외부 쇼핑몰만 fetch 하는 것이 본 컴포넌트의 책임이라 외부 라우팅 가능 IP 만 허용한다.
    // redirect 를 따라갈 때도 매 hop 의 새 host 에 대해 다시 호출해 점프 후 SSRF 를 막는다.
    private fun guardAgainstInternalHost(link: ProductLink) {
        val host =
            link.value.host ?: run {
                log.warn("[SSRF] blocked: missing host url={}", link.safeLogString())
                throw PageFetchException.blockedHost()
            }
        val addresses =
            try {
                dnsResolver.resolve(host)
            } catch (e: java.net.UnknownHostException) {
                log.info("link fetch unknown host url={}", link.safeLogString())
                throw PageFetchException.upstreamError(e)
            }
        if (addresses.any { isInternalAddress(it) }) {
            log.error("[SSRF] blocked: internal address resolved url={}", link.safeLogString())
            throw PageFetchException.blockedHost()
        }
    }

    // 외부 라우팅 불가 주소면 SSRF 위험으로 본다. Java 의 isSiteLocalAddress 는 IPv4 사설만 잡고 IPv6 ULA(fc00::/7)는
    // 못 잡으므로(GCP·Alibaba 등의 IPv6 메타데이터·내부망), 이를 별도로 차단한다.
    internal fun isInternalAddress(addr: InetAddress): Boolean =
        addr.isLoopbackAddress ||
            addr.isAnyLocalAddress ||
            addr.isSiteLocalAddress ||
            addr.isLinkLocalAddress ||
            addr.isMulticastAddress ||
            isUniqueLocalIpv6(addr) ||
            isCarrierGradeNatIpv4(addr) ||
            // AWS / GCP IPv4 메타데이터(169.254.169.254 는 link-local 이라 위에서 잡히지만 방어적으로 명시).
            addr.hostAddress == "169.254.169.254"

    // IPv6 Unique Local Address(fc00::/7). 첫 바이트의 상위 7비트가 1111110 (0xfc 또는 0xfd)이면 ULA 다.
    private fun isUniqueLocalIpv6(addr: InetAddress): Boolean =
        addr is Inet6Address && (((addr.address.firstOrNull()?.toInt() ?: 0) and 0xfe) == 0xfc)

    // IPv4 Carrier-Grade NAT(100.64.0.0/10). 외부 라우팅이 안 되는 캐리어 NAT 대역으로, 일부 클라우드
    // 메타데이터 엔드포인트(예: 100.100.100.200)가 여기 속해 SSRF 차단 대상이다.
    private fun isCarrierGradeNatIpv4(addr: InetAddress): Boolean {
        if (addr !is Inet4Address) return false
        val bytes = addr.address
        val first = bytes[0].toInt() and 0xFF
        val second = bytes[1].toInt() and 0xFF
        return first == 100 && second in 64..127
    }

    // 응답 바이트를 올바른 charset 으로 디코딩한다. 우선순위: 응답 Content-Type 의 charset → HTML 내 meta charset → UTF-8.
    // (StringHttpMessageConverter 는 charset 이 없으면 ISO-8859-1 로 떨어져 UTF-8 페이지를 깨뜨리므로 직접 정한다.)
    private fun decodeHtml(
        bytes: ByteArray,
        headerCharset: Charset?,
    ): String {
        val charset = headerCharset ?: detectMetaCharset(bytes) ?: Charsets.UTF_8
        return String(bytes, charset)
    }

    // <meta charset=...> / <meta http-equiv="Content-Type" content="...charset=..."> 에서 charset 을 읽는다.
    // meta 선언은 head 앞쪽에 ASCII 로 있으므로 앞부분만 ISO-8859-1(ASCII 호환)로 훑어 charset 토큰을 찾는다.
    // 알 수 없는 charset 이름이면 null 을 돌려 호출부가 UTF-8 로 폴백하게 한다.
    private fun detectMetaCharset(bytes: ByteArray): Charset? {
        val head = String(bytes, 0, minOf(bytes.size, META_SCAN_BYTES), Charsets.ISO_8859_1)
        val name = META_CHARSET.find(head)?.groupValues?.get(1)?.trim() ?: return null
        return runCatching { Charset.forName(name) }.getOrNull()
    }

    companion object {
        // 같은 회사 도메인 안에서의 www↔non-www·http→https 정도라 한두 번이면 충분. 무한·체인 redirect 는 여기서 끊는다.
        private const val MAX_REDIRECTS = 3

        // HTML head 의 charset 선언을 훑는 범위(앞 4KB)와 패턴. <meta charset=...> 와 http-equiv 의 charset= 둘 다 잡는다.
        private const val META_SCAN_BYTES = 4096
        private val META_CHARSET = Regex("""charset\s*=\s*["']?\s*([A-Za-z0-9_\-]+)""", RegexOption.IGNORE_CASE)

        // Location 을 가진 진짜 redirect 상태 코드. 304/300/305 등 다른 3xx 는 따라가지 않는다.
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        // 재시도해도 결정론적으로 재실패하는 5xx 로 보는 상태 코드. 500(Internal)·501(Not Implemented)이며,
        // 봇 차단을 500(no body)으로 응답하는 쇼핑몰이 흔해 영구 실패로 처리한다. 502/503/504 는 제외(일시 가능).
        private val PERMANENT_SERVER_ERRORS = setOf(500, 501)

        // fetch 본문의 보관·파싱 비용을 막는 안전 상한이다. LLM 토큰 상한이 아니다 — 그건 Gemini 입력 직전
        // GeminiHtmlExtractor 가 정리(sanitize)된 HTML 에 따로 적용한다. 이 상한을 넉넉히 둬야 구조화 추출
        // (JSON-LD/OpenGraph)이 페이지 전체를 본다: JSON-LD·가격이 본문 뒤쪽에 있는 사이트(아마존 약 1.65MB,
        // 나이키 약 0.75MB 실측)는 앞부분만 보면 가격을 놓친다. 동시 파싱(워커 풀) 시 메모리(상한 x 동시 수)를
        // 감안해 무제한이 아니라 3MB 로 바운드한다. 무거운 한국 메이저 몰(쿠팡·G마켓·옥션)은 403 으로 막혀
        // 애초에 fetch 되지 않으므로 이 상한과 무관하다.
        private const val MAX_FETCH_CHARS = 3_000_000
    }
}
