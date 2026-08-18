package com.depromeet.piki.product.domain

import java.security.MessageDigest

// 정규화된 귀결점 — 상품 정체성(#825)의 단일 표현. "어디까지를 같은 상품으로 볼 것인가"의 경계를 이 정규화가
// 정하고, hash 는 그 결과를 색인 가능하게 줄이는 고정 길이 대리키(SHA-256 hex)일 뿐 같음의 정의에 관여하지 않는다
// (utf8mb4 인덱스 상한 768자 < URL 컬럼 2048자라 unique 를 hash 가 대신 진다).
//
// 두 차선이 같은 함수를 쓴다: 등록 입력(대체로 이미 깨끗함)과 파싱 귀결점(finalUrl). 무게중심은 후자다 —
// 리다이렉트가 공유자 계정 ID(af_referrer_customer_id)·리워드 토큰(reward_key)·클릭 UUID(event_uuid)·
// 밀리초 타임스탬프(referrer_timestamp)를 붙여 와, 정규화 없이는 같은 링크의 재파싱끼리도 정체성이 갈라진다
// (2026-07-30 실측). 이 값들은 개인·세션 연동 정보라 저장 위생 문제이기도 하다 — 정규화가 DB 진입 전에 떨군다.
class CanonicalLink private constructor(
    val url: String,
    val hash: String,
) {
    // 저장 한계: canonical 은 items.canonical_url·item_links.url 의 VARCHAR(2048) 에 저장된다. 정규화는 구성요소
    // 재조립뿐이라 입력을 늘리지 않지만, 입력 자체(특히 리다이렉트 귀결점 finalUrl)는 우리 밖에서 와 상한이 없다.
    // 초과 URL 은 자를 수 없다 — 절단된 canonical 은 다른 상품과 충돌할 수 있는 거짓 정체성이다. 그래서 영속 경로가
    // 이 플래그를 보고 canonical 확정·별칭 기록을 건너뛴다(그 item 은 정체성 미확정으로 남고, 빈도는 메트릭으로 관측).
    val exceedsStorageLimit: Boolean
        get() = url.length > STORAGE_MAX_LENGTH

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CanonicalLink) return false
        return url == other.url
    }

    override fun hashCode(): Int = url.hashCode()

    override fun toString(): String = url

    companion object {
        // 쿼리 전부 제거 몰 — "상품 번호가 경로에 있다"를 실측(prod 전수 쿼리 키 인벤토리 + 쿼리 유/무 A/B fetch
        // 동일 확인, 2026-07-30)으로 검증한 몰만 올린다. 미확인 몰을 올리면 쿼리로 상품을 식별하는 몰
        // (cafe24 의 product_no 등)에서 서로 다른 상품이 하나로 합쳐지는 사고가 난다 — 잘못 합쳐짐(정체성 오염)이
        // 안 합쳐짐(병합 효과 손실)보다 훨씬 비싸다는 비대칭이 이 목록을 보수적으로 유지하는 이유다.
        // onelink.me 는 AppsFlyer 단축 플랫폼 전체 — 경로가 곧 공유 코드라 쿼리가 상품을 식별할 수 없다.
        // W컨셉은 페이지가 JS 셸이라 A/B 검증 증거가 약해 제외(기본 규칙 적용).
        private val STRIP_ALL_QUERY_DOMAINS =
            setOf("musinsa.com", "29cm.co.kr", "zigzag.kr", "kream.co.kr", "a-bly.com", "onelink.me")

        // 미확인 몰용 보험 — 확실한 추적 파라미터만 이름으로 지운다. 여기 없는 이름이 남아 병합이 덜 되는 건
        // 감수하는 손실이고, 상품 식별 파라미터를 지우는 사고는 감수 불가라 목록을 최소로 유지한다.
        // pid·channel·campaign 처럼 범용적인 이름은 어느 몰이 의미 있게 쓸지 몰라 전역 목록에 넣지 않는다
        // (그런 이름이 붙는 몰은 실측 후 STRIP_ALL_QUERY_DOMAINS 로 올리는 게 정답).
        private val TRACKING_PARAM_PREFIXES = listOf("utm_", "af_", "airbridge")
        private val TRACKING_PARAM_NAMES = setOf("fbclid", "gclid", "igshid", "yclid")

        // 같은 상품이 경로 형식 두 가지로 존재하는 몰의 접기 규칙. 지그재그는 단축링크 귀결점이 /p/{id},
        // 웹 주소가 /catalog/products/{id} 로 갈리는데 같은 번호 = 같은 상품임을 실측 확인(2026-07-30).
        private val ZIGZAG_SHORT_PATH = Regex("^/p/(\\d+)$")

        private const val HASH_LENGTH = 64

        // items.canonical_url · item_links.url 컬럼 길이와 일치시킨다.
        const val STORAGE_MAX_LENGTH = 2048

        // fragment 는 여기서 별도 처리하지 않는다 — 재조립이 rawPath·rawQuery 만 쓰므로 구조적으로 탈락한다.
        // fragment 는 HTTP 요청에 실리지 않아 서버 렌더 몰에선 상품을 바꿀 수 없고(원리), prod 582건 중 의미 있는
        // fragment 는 0건(실측). 예외는 해시로 화면을 가르는 SPA 를 실제로 렌더해 읽는 경우뿐 — 그런 몰이 나타나면
        // 몰별 예외로 보존 규칙을 더한다.
        fun of(link: ProductLink): CanonicalLink {
            val host = requireNotNull(link.normalizedHost()) { "host 없는 링크는 canonical 을 만들 수 없다" }
            val path = normalizePath(host, link.value.rawPath.orEmpty())
            val query = normalizeQuery(link, link.value.rawQuery)
            val url =
                buildString {
                    append("https://").append(host).append(path)
                    query?.let { append('?').append(it) }
                }
            return CanonicalLink(url = url, hash = sha256Hex(url))
        }

        private fun normalizePath(
            host: String,
            rawPath: String,
        ): String {
            // trailing slash 유무로 정체성이 갈리지 않게 접는다 (cafe24 계열이 /153/ 형태를 쓴다).
            val stripped = rawPath.trimEnd('/').ifEmpty { "/" }
            if (host == "zigzag.kr" || host.endsWith(".zigzag.kr")) {
                val folded =
                    ZIGZAG_SHORT_PATH.matchEntire(stripped)?.let { "/catalog/products/${it.groupValues[1]}" }
                return folded ?: stripped
            }
            return stripped
        }

        private fun normalizeQuery(
            link: ProductLink,
            rawQuery: String?,
        ): String? {
            rawQuery ?: return null
            if (rawQuery.isBlank()) return null
            if (link.matchesAnyDomain(STRIP_ALL_QUERY_DOMAINS)) return null
            val kept =
                rawQuery
                    .split('&')
                    .filter { it.isNotBlank() && !isTrackingParam(it) }
                    // 파라미터 순서만 다른 URL 이 다른 정체성이 되지 않게 정렬한다 — GET 상품 페이지에서
                    // 서버가 파라미터 순서에 의미를 두는 경우는 없다.
                    .sorted()
            return kept.takeIf { it.isNotEmpty() }?.joinToString("&")
        }

        private fun isTrackingParam(segment: String): Boolean {
            val name = segment.substringBefore('=').lowercase()
            return name in TRACKING_PARAM_NAMES || TRACKING_PARAM_PREFIXES.any { name.startsWith(it) }
        }

        private fun sha256Hex(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            val hex = digest.joinToString("") { "%02x".format(it) }
            check(hex.length == HASH_LENGTH) { "SHA-256 hex 는 항상 64자다" }
            return hex
        }
    }
}
