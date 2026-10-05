package com.depromeet.piki.item.domain

import com.depromeet.piki.product.domain.ProductLink

@JvmInline
value class CanonicalLink private constructor(
    val url: String,
) {
    val exceedsStorageLimit: Boolean
        get() = url.length > ProductLink.MAX_LENGTH

    override fun toString(): String = url

    companion object {
        // 지워도 같은 상품이 열리는 것을 실제 요청으로 확인한 이름만 지운다. 모르는 이름은 남긴다
        private val TRACKING_PARAM_PREFIXES = listOf("utm_", APPSFLYER_PREFIX, "airbridge")
        private val TRACKING_PARAM_NAMES =
            setOf(
                "fbclid", "gclid", "igshid", "yclid",
                "source_caller", "is_retargeting", "onelink_id", "shortlink",
                "event_uuid", "referrer_timestamp", "deeplink_url", "channel",
                "reward_key", "categorylargecode", "categorymediumcode", "categorysmallcode",
            )

        // AppsFlyer 의 유입 경로 파라미터. 상품 번호를 pid 로 주는 몰이 있어, AppsFlyer 가 붙인 링크일 때만 지운다
        private const val APPSFLYER_PREFIX = "af_"
        private const val APPSFLYER_MEDIA_SOURCE = "pid"

        // 지그재그는 같은 상품이 /p/{id} 와 /catalog/products/{id} 두 경로로 열린다
        private val ZIGZAG_SHORT_PATH = Regex("^/p/(\\d+)$")

        // fragment 는 서버로 가지 않아 상품을 바꾸지 못한다. 경로와 쿼리만 다시 조립하므로 저절로 빠진다
        fun of(link: ProductLink): CanonicalLink {
            val host = requireNotNull(link.normalizedHost()) { "host 없는 링크는 canonical 을 만들 수 없다" }
            val path = normalizePath(host, link.value.rawPath.orEmpty())
            val kept = keptParams(link.value.rawQuery)
            val url =
                buildString {
                    append("https://").append(host).append(path)
                    if (kept.isNotEmpty()) append('?').append(kept.joinToString("&"))
                }
            return CanonicalLink(url)
        }

        private fun normalizePath(
            host: String,
            rawPath: String,
        ): String {
            // cafe24 계열은 같은 상품을 /153 과 /153/ 둘 다로 준다
            val stripped = rawPath.trimEnd('/').ifEmpty { "/" }
            if (host == "zigzag.kr" || host.endsWith(".zigzag.kr")) {
                val folded =
                    ZIGZAG_SHORT_PATH.matchEntire(stripped)?.let { "/catalog/products/${it.groupValues[1]}" }
                return folded ?: stripped
            }
            return stripped
        }

        // 파라미터 순서만 다른 URL 이 다른 정체성이 되지 않게 정렬한다
        private fun keptParams(rawQuery: String?): List<String> {
            rawQuery ?: return emptyList()
            val params = rawQuery.split('&').filter { it.isNotBlank() }
            val fromAppsFlyer = params.any { nameOf(it).startsWith(APPSFLYER_PREFIX) }
            return params.filterNot { isTrackingParam(nameOf(it), fromAppsFlyer) }.sorted()
        }

        private fun isTrackingParam(
            name: String,
            fromAppsFlyer: Boolean,
        ): Boolean {
            if (name in TRACKING_PARAM_NAMES) return true
            if (TRACKING_PARAM_PREFIXES.any { name.startsWith(it) }) return true
            return fromAppsFlyer && name == APPSFLYER_MEDIA_SOURCE
        }

        private fun nameOf(segment: String): String = segment.substringBefore('=').lowercase()
    }
}
