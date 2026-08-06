package com.depromeet.piki.admin.announcement

import com.depromeet.piki.announcement.domain.AnnouncementImageException
import org.slf4j.LoggerFactory
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.time.Duration

// 운영자가 공지 본문에 붙여넣은 외부 이미지 URL 을 가져온다(rehost #561).
// 붙여넣는 이미지의 출처를 미리 알 수 없어 도메인 화이트리스트를 둘 수 없으므로, SSRF 방어를
// IP 차단으로 한다 — 임의 https 는 허용하되 사설/내부 IP 로의 요청만 막는다.
data class FetchedImage(
    val bytes: ByteArray,
    val contentType: String,
)

interface AnnouncementImageFetcher {
    fun fetch(url: String): FetchedImage
}

@Component
class DefaultAnnouncementImageFetcher : AnnouncementImageFetcher {
    private val log = LoggerFactory.getLogger(javaClass)

    private val client =
        RestClient
            .builder()
            .requestFactory(
                // 리다이렉트 자동 추적 차단 — 공개 도메인에서 내부망(IMDS 등)으로 유도하는 SSRF 우회를 막는다.
                object : SimpleClientHttpRequestFactory() {
                    init {
                        setConnectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                        setReadTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    }

                    override fun prepareConnection(
                        connection: HttpURLConnection,
                        httpMethod: String,
                    ) {
                        super.prepareConnection(connection, httpMethod)
                        connection.instanceFollowRedirects = false
                    }
                },
            ).build()

    override fun fetch(url: String): FetchedImage {
        val uri = parseHttpsUri(url)
        guardAgainstInternalAddress(uri.host)
        return try {
            client
                .get()
                .uri(uri)
                .exchange { _, response ->
                    if (response.statusCode.isError || response.statusCode.is3xxRedirection) {
                        log.warn("공지 이미지 fetch 실패: status={}", response.statusCode)
                        throw AnnouncementImageException.fetchFailed()
                    }
                    val contentType =
                        response.headers.contentType?.takeIf { it.type == "image" }
                            ?: run {
                                log.warn("공지 이미지 Content-Type 이 image 가 아님: {}", response.headers.contentType)
                                throw AnnouncementImageException.fetchFailed()
                            }
                    FetchedImage(readWithLimit(response.body), contentType.toString())
                }
        } catch (e: AnnouncementImageException) {
            throw e
        } catch (e: Exception) {
            log.warn("공지 이미지 fetch 네트워크 오류: {}", e.message)
            throw AnnouncementImageException.fetchFailed()
        }
    }

    private fun parseHttpsUri(url: String): URI =
        try {
            URI.create(url).also {
                // https 만 허용 — http·file·gopher 등 스킴을 통한 SSRF·로컬파일 접근을 막는다.
                if (it.scheme != "https" || it.host.isNullOrBlank()) throw AnnouncementImageException.blockedUrl()
            }
        } catch (e: IllegalArgumentException) {
            log.warn("공지 이미지 URL 파싱 실패: {}", e.message)
            throw AnnouncementImageException.blockedUrl()
        }

    // host 를 해석한 모든 IP 중 하나라도 사설/내부면 차단한다(SSRF). DNS rebinding(검사 후 connect 시 재해석)
    // 잔여 위험은 운영자가 신뢰 경계(슬랙-IP 게이트) 안이라는 점 + no-redirect 로 완화한다.
    private fun guardAgainstInternalAddress(host: String) {
        val addresses =
            try {
                InetAddress.getAllByName(host)
            } catch (e: Exception) {
                log.warn("공지 이미지 host 해석 실패: {}", e.message)
                throw AnnouncementImageException.fetchFailed()
            }
        if (addresses.any { isBlockedImageHostAddress(it) }) {
            log.warn("공지 이미지 차단된 주소(SSRF 방어): host={}", host)
            throw AnnouncementImageException.blockedUrl()
        }
    }

    private fun readWithLimit(stream: InputStream): ByteArray {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0L
        stream.use {
            var n: Int
            while (it.read(chunk).also { read -> n = read } != -1) {
                total += n
                if (total > MAX_BYTES) throw AnnouncementImageException.tooLarge()
                buffer.write(chunk, 0, n)
            }
        }
        return buffer.toByteArray()
    }

    companion object {
        private const val TIMEOUT_SECONDS = 5L
        private const val MAX_BYTES = 10L * 1024 * 1024 // 10MB — 애니메이션 gif 여유
    }
}

// 사설/내부 대상 주소인지 — SSRF 차단 대상 여부. 순수 함수라 단위테스트로 망라한다.
// loopback(127/8·::1)·anyLocal(0.0.0.0·::)·link-local(169.254/16 IMDS·fe80::/10)·
// site-local(10/8·172.16/12·192.168/16)·multicast, 그리고 IPv6 unique-local(fc00::/7)을 막는다.
fun isBlockedImageHostAddress(addr: InetAddress): Boolean {
    if (addr.isLoopbackAddress || addr.isAnyLocalAddress) return true
    if (addr.isLinkLocalAddress || addr.isSiteLocalAddress) return true
    if (addr.isMulticastAddress) return true
    // Java 의 isSiteLocalAddress 는 IPv6 unique-local(fc00::/7)을 잡지 않으므로 직접 본다.
    if (addr is Inet6Address) {
        val firstByte = addr.address[0].toInt() and 0xFF
        if (firstByte and 0xFE == 0xFC) return true
    }
    return false
}
