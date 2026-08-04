package com.depromeet.piki.product.service.screening

import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 2단계 dev 서버 end-to-end 검수 (일회성 측정 도구, 회귀 테스트 아님).
 *
 * dev.api.piki.day 실제 등록 API 로 전체 파이프라인(fetch + 구조화/Gemini + DB)을 통과시켜, URL 하나를
 * 다음으로 가른다:
 *   REGISTER-4xx          등록 동기 실패(400 미지원 쇼핑몰·형식오류 등). 응답 detail 을 그대로 보여준다.
 *   REGISTER-ERROR        등록 POST 의 연결·timeout 등 transport 오류 (한 URL 실패가 전체 스캔을 막지 않게 기록만)
 *   READY                 추출 성공 — name·price·currency 확정
 *   FAILED                fetch/추출이 끝내 실패(봇 차단·상품 아님 등)
 *   POLL-ERROR            폴링 중 5xx·연결·파싱 등 transport 오류 (TIMEOUT 과 구분)
 *   TIMEOUT(추출미완)      제한 시간 내 status 가 READY/FAILED 로 전이하지 않음
 *
 * 1단계 로컬이 'Gemini필요'로 미룬 URL 이 실제로 추출되는지, 로컬 fetch 차단이 AWS DC IP 에서도 같은지는
 * 이 단계만 답한다(로컬 fetch 와 dev 서버 IP·Gemini 유무가 다르기 때문).
 *
 * 부수효과: 실제 Gemini 호출(비용) + dev DB 에 검수용 qa 유저의 위시가 쌓인다. 실제 상품이라 값이 가변이므로
 * 단언은 두지 않고 결과를 출력만 한다(측정 도구).
 *
 * 입력은 1단계와 같은 SCREENING_INPUT JSON 을 공유한다(격리 게이트 겸용). dev base 는 SCREENING_DEV_BASE 로
 * 덮을 수 있다(기본 https://dev.api.piki.day).
 *
 * 실행: SCREENING_INPUT=/path/to/urls.json ./gradlew test --tests "*LinkScreeningDevApiE2ETest" -i
 * 결과는 stdout 과 /tmp/link-screening-dev.txt 양쪽에 남는다.
 */
@EnabledIfEnvironmentVariable(named = ScreeningInput.ENV, matches = ".+")
class LinkScreeningDevApiE2ETest {
    private val baseUrl = System.getenv(DEV_BASE_ENV) ?: DEFAULT_DEV_BASE
    private val mapper = jacksonObjectMapper()
    private val client =
        RestClient
            .builder()
            .baseUrl(baseUrl)
            // X-Client-Type: APP 가 없으면 토큰이 쿠키로 내려가고 body 의 accessToken 이 null 로 비워진다.
            .defaultHeader(CLIENT_TYPE_HEADER, "APP")
            // dev 서버·네트워크가 느릴 때 각 호출이 무기한 붙잡혀 전체 검수가 @Timeout 까지 끌리지 않게 per-request timeout 으로 fail-fast.
            .requestFactory(
                SimpleClientHttpRequestFactory().apply {
                    setConnectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SEC))
                    setReadTimeout(Duration.ofSeconds(READ_TIMEOUT_SEC))
                },
            ).build()

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    fun `PC·모바일 링크 dev 서버 end-to-end 검수 - READY FAILED 상품명·가격 확정`() {
        val memberToken = bootstrapMemberToken()
        val sb = StringBuilder()
        sb.appendLine("base=$baseUrl")
        sb.appendLine()
        ScreeningInput.load().forEach { target ->
            sb.appendLine("=== ${target.platform} ===")
            sb.appendLine("  [PC 직링크]")
            target.pc.forEach { sb.appendLine("    " + register(it, memberToken)) }
            sb.appendLine("  [모바일 공유링크]")
            target.mobile.forEach { sb.appendLine("    " + register(it, memberToken)) }
            sb.appendLine()
        }
        val report = sb.toString()
        println(report)
        File("/tmp/link-screening-dev.txt").writeText(report)
    }

    // guest → dev/users(MEMBER) 부트스트랩. 위시는 MEMBER 전용이라 게스트 토큰만으론 403.
    // dev/users 는 인증이 필요해(authenticated) 먼저 게스트 토큰으로 호출한다.
    private fun bootstrapMemberToken(): String {
        val guestToken = post("/api/v1/auth/guest", body = null, bearer = null).requireText("/data/accessToken")
        val nickname = "qa-${UUID.randomUUID().toString().take(7)}" // 닉네임 10자 제한 + 중복 409 라 짧고 유니크하게(qa- + 7자 = 10자).
        return post("/api/v1/dev/users", body = mapOf("nickname" to nickname), bearer = guestToken)
            .requireText("/data/accessToken")
    }

    // 등록 → 추출 완료 폴링 → 결과 한 줄.
    private fun register(
        url: String,
        token: String,
    ): String {
        val created =
            try {
                post("/api/v1/wishlists", body = mapOf("url" to url), bearer = token)
            } catch (e: RestClientResponseException) {
                // 등록 동기 실패(400 미지원·형식오류 등). 응답 detail 을 그대로 보여준다.
                return "REGISTER-${e.statusCode.value()}        ${tail(url)}  ${detailOf(e)}"
            } catch (e: RestClientException) {
                // 연결·timeout 등 transport 오류. 한 URL 실패가 전체 스캔을 막지 않게 결과로 남기고 다음 입력으로 계속한다.
                return "REGISTER-ERROR        ${tail(url)}  ${e.javaClass.simpleName}: ${e.message?.take(80)}"
            }
        val wishId = created.requireText("/data/wish/id").toLong()
        return poll(wishId, token, url)
    }

    private fun poll(
        wishId: Long,
        token: String,
        url: String,
    ): String {
        var item: JsonNode? = null
        // transport·5xx 오류는 await 가 timeout 까지 재시도해 TIMEOUT 으로 뭉개므로, 즉시 폴링을 끝내고 별도 결과로 가른다.
        var transportError: RestClientException? = null
        val reachedTerminal =
            runCatching {
                await()
                    .atMost(Duration.ofSeconds(POLL_TIMEOUT_SEC))
                    .pollInterval(Duration.ofSeconds(POLL_INTERVAL_SEC))
                    .until {
                        val data =
                            try {
                                get("/api/v1/wishlists/$wishId", token).at("/data/item")
                            } catch (e: RestClientException) {
                                transportError = e
                                return@until true // 재시도해도 같은 결과 — 폴링을 끝내고 아래에서 별도 결과로 처리
                            }
                        item = data
                        data.at("/status").asString() in TERMINAL_STATUSES
                    }
                true
            }.getOrDefault(false)
        transportError?.let {
            return "POLL-ERROR            ${tail(url)}  ${it.javaClass.simpleName}: ${it.message?.take(80)}"
        }
        if (!reachedTerminal) return "TIMEOUT(추출미완)       ${tail(url)}"
        val node = item ?: return "POLL-ERROR            ${tail(url)}  응답에 item 이 없음"
        return when (node.at("/status").asString()) {
            "READY" ->
                "READY                 ${tail(url)}  name=${node.at("/name").asString()}  " +
                    "price=${node.at("/price").asString()}  currency=${node.at("/currency").asString()}"
            else -> "FAILED                ${tail(url)}"
        }
    }

    private fun post(
        path: String,
        body: Any?,
        bearer: String?,
    ): JsonNode {
        var request = client.post().uri(path)
        bearer?.let { request = request.header(HttpHeaders.AUTHORIZATION, "Bearer $it") }
        // body 는 우리 mapper(jackson 3)로 직렬화한 JSON 문자열로 보낸다. standalone RestClient 의 기본 메시지
        // 컨버터가 객체→JSON 을 보장하지 않아 빈 body 로 나가면 서버가 검증 400 을 주므로, StringHttpMessageConverter 로 확정한다.
        val response =
            body
                ?.let {
                    request
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(mapper.writeValueAsString(it))
                        .retrieve()
                }
                ?: request.retrieve()
        val json = response.body(String::class.java) ?: error("빈 응답: $path")
        return mapper.readTree(json)
    }

    private fun get(
        path: String,
        bearer: String,
    ): JsonNode {
        val json =
            client
                .get()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $bearer")
                .retrieve()
                .body(String::class.java) ?: error("빈 응답: $path")
        return mapper.readTree(json)
    }

    private fun detailOf(e: RestClientResponseException): String =
        runCatching { mapper.readTree(e.responseBodyAsString).at("/detail").asString() }
            .getOrNull()
            ?.ifBlank { null }
            ?: (e.message ?: "")

    private fun JsonNode.requireText(pointer: String): String =
        at(pointer).asString().ifBlank { error("응답에 $pointer 가 없다: ${this.toString().take(200)}") }

    // 로그 가독성용으로 host+path 의 마지막 식별자만 짧게 보여준다(쿼리스트링 제외).
    private fun tail(url: String): String = url.substringBefore("?").takeLast(40)

    companion object {
        private const val DEV_BASE_ENV = "SCREENING_DEV_BASE"
        private const val DEFAULT_DEV_BASE = "https://dev.api.piki.day"
        private const val CLIENT_TYPE_HEADER = "X-Client-Type"

        // 정상 추출은 등록 후 1~3초, 영구 실패는 즉시 종결된다(#519). 넉넉히 잡되 recover 재시도(최악 ~150초)까지는
        // 기다리지 않는다 — 측정 도구라 정상 완료를 보는 것이 목적이고, 길게 매달린 건 TIMEOUT 으로 가른다.
        private const val POLL_TIMEOUT_SEC = 40L
        private const val POLL_INTERVAL_SEC = 1L
        private val TERMINAL_STATUSES = setOf("READY", "FAILED")

        // dev API per-request timeout. 한 URL 이 무기한 붙잡혀 전체 검수가 끌리지 않게 fail-fast 로 끊는다.
        private const val CONNECT_TIMEOUT_SEC = 3L
        private const val READ_TIMEOUT_SEC = 10L
    }
}
