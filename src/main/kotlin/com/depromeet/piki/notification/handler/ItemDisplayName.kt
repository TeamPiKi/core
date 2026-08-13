package com.depromeet.piki.notification.handler

import java.text.BreakIterator

// 알림 문구에 실을 상품명을 표시용으로 다듬는다. 파싱 완료(#913)와 아이템 삭제 두 알림이 공유한다.
//
// 표시 글자 절단은 **선택**이다 — 두 알림의 사정이 다르다.
//   - 파싱 완료: title 이 이름뿐이라 OS 가 잘라도 의미가 안 사라진다. 절단하지 않는다(maxGraphemes = null).
//   - 아이템 삭제: "OO님이 '{이름}'을(를) 삭제했어요" 처럼 이름 뒤에 문장이 붙어, 이름이 길면 정작 무슨 일인지가
//     OS 절단에 먹힌다. 그래서 이쪽은 캡이 필요하다.
object ItemDisplayName {
    // 이름이 없거나 공백뿐일 때. 파싱 전(PENDING·PROCESSING)이나 추출 실패로 name 이 비는 경우가 실재한다.
    const val FALLBACK = "상품"

    // 표시 글자 수와 무관하게 거는 UTF-16 char 상한. grapheme cluster 1개가 조합 부호로 수십~수백 char 일 수 있어
    // "N 글자" 가 곧 짧은 문자열을 뜻하지 않는다. 그대로 두면 Notification 생성자의 require(title.length <= 255)에
    // 걸리고, dispatcher 의 수신자별 runCatching 이 그 예외를 삼켜 알림이 전 수신자에게 조용히 누락된다.
    // 문구의 나머지 부분(닉네임·고정 문장)을 감안해 넉넉히 남긴다.
    private const val MAX_CHARS = 100

    private const val ELLIPSIS = "…"
    private val WHITESPACE = Regex("\\s+")

    /**
     * @param maxGraphemes 표시 글자(grapheme cluster) 상한. null 이면 절단하지 않고 char 안전망만 건다.
     */
    fun of(
        rawName: String?,
        maxGraphemes: Int? = null,
    ): String {
        // 공백을 먼저 접는다 — 추출 이름은 상류(ProductSnapshot)에서 trim 되지 않아 앞뒤 공백·개행이 그대로 온다.
        // 앞 공백이 글자 예산을 먹어 이름이 일찍 잘리고, 개행이 섞이면 알림·푸시가 두 줄이 된다.
        val name = rawName?.replace(WHITESPACE, " ")?.trim()?.takeIf { it.isNotEmpty() } ?: return FALLBACK
        maxGraphemes ?: return name.capChars()
        return name.truncateGraphemes(maxGraphemes)
    }

    // 절단 단위는 grapheme cluster(BreakIterator) 다 — String.length·take 는 UTF-16 코드 단위라, 이모지(surrogate
    // pair)·ZWJ 시퀀스·조합문자가 경계에 걸치면 반쪽만 남아 깨진 문자로 노출된다. 사용자가 보는 "글자" 로 자른다.
    private fun String.truncateGraphemes(limit: Int): String {
        val boundary = BreakIterator.getCharacterInstance().apply { setText(this@truncateGraphemes) }
        var end = boundary.first()
        repeat(limit) {
            val next = boundary.next()
            if (next == BreakIterator.DONE) return capChars() // 표시 글자 수가 한도 이하 — 자를 필요 없음
            end = next
        }
        // 한도째 경계까지 왔다. 그 뒤에 글자가 더 있으면(다음 경계가 DONE 이 아니면) 잘라서 말줄임표를 붙인다.
        return if (boundary.next() == BreakIterator.DONE) capChars() else substring(0, end).capChars() + ELLIPSIS
    }

    // 마지막 안전망 — grapheme 경계와 무관하게 char 길이를 자른다. 여기 걸리는 입력은 정상 상품명이 아니라
    // 조합 부호를 쌓아 만든 비정상 값이라, 글자 깨짐보다 알림 누락을 막는 쪽을 택한다.
    private fun String.capChars(): String = if (length <= MAX_CHARS) this else take(MAX_CHARS) + ELLIPSIS
}
