package com.depromeet.piki.product.service.remote

// 백오피스 입력 규칙. 형식은 추출 계약의 같은 필드(infra contracts/extraction.proto)와 같고, 받는 서비스도 다시 본다.
// bot 문자열 거르기는 값을 입력받는 이 쪽 몫이다. 받는 서비스는 형식만 본다.
object ExtractionIdentityRule {
    const val MAX_LENGTH = 40

    private val FORMAT = Regex("[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)?")

    // 우리를 겨냥하지 않은 일반 룰이 문자열만 보고 거는 이름이다.
    private val CRAWLER_WORDS = Regex("bot|crawler|spider", RegexOption.IGNORE_CASE)

    // 어긴 규칙의 화면 문구. 지키면 null.
    fun violationOf(identity: String): String? =
        when {
            identity.length > MAX_LENGTH -> "${MAX_LENGTH}자 이내로 입력해 주세요."
            !FORMAT.matches(identity) -> "이름 또는 이름/버전 형식으로 입력해 주세요. 영문, 숫자, 점, 밑줄, 하이픈만 쓸 수 있습니다."
            CRAWLER_WORDS.containsMatchIn(identity) -> "bot, crawler, spider 가 든 이름은 쓸 수 없습니다."
            else -> null
        }
}
