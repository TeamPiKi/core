package com.depromeet.piki.notification.handler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ItemParsingCompletedHandlerTest {
    @Test
    fun `10자 이하 이름은 그대로 쓴다`() {
        assertEquals("나이키 에어맥스", ItemParsingCompletedHandler.displayName("나이키 에어맥스"))
    }

    @Test
    fun `정확히 10자 이름은 자르지 않는다`() {
        val ten = "일이삼사오육칠팔구십" // 10자
        assertEquals(ten, ItemParsingCompletedHandler.displayName(ten))
    }

    @Test
    fun `10자 초과 이름은 앞 10자 + 말줄임표로 자른다`() {
        assertEquals("일이삼사오육칠팔구십…", ItemParsingCompletedHandler.displayName("일이삼사오육칠팔구십일이삼")) // 13자
    }

    @Test
    fun `경계에 걸친 이모지는 반쪽으로 잘리지 않고 통째로 유지된다`() {
        // 앞 9자(한글) + 이모지(surrogate pair) = 표시 10글자. UTF-16 take(10) 이면 이모지가 반쪽 나 깨지지만,
        // grapheme 절단이라 이모지가 통째로 살고 그 뒤(11번째 글자부터)만 말줄임표로 잘린다.
        assertEquals("일이삼사오육칠팔구😀…", ItemParsingCompletedHandler.displayName("일이삼사오육칠팔구😀가나"))
    }

    @Test
    fun `표시 10글자째가 이모지면 그대로 두고 자르지 않는다`() {
        val ten = "일이삼사오육칠팔구😀" // 한글 9 + 이모지 1 = 표시 10글자 (UTF-16 길이는 11)
        assertEquals(ten, ItemParsingCompletedHandler.displayName(ten))
    }

    @Test
    fun `ZWJ 로 이어진 이모지 시퀀스는 경계에 걸쳐도 통째로 유지된다`() {
        // 👨‍👩‍👧‍👦 = 사람 이모지 4개를 ZWJ(U+200D)로 이은 확장 grapheme cluster 1개 (UTF-16 길이는 11).
        // codePointCount 기반 절단이면 ZWJ 경계에서 쪼개져 깨지지만, grapheme 절단이라 통째로 살고 그 뒤만 잘린다.
        val family = "👨‍👩‍👧‍👦"
        val name = "일이삼사오육칠팔구" + family + "가나" // 한글 9 + 가족 이모지 1 = 표시 10글자, 그 뒤에 더 있음
        assertEquals("일이삼사오육칠팔구" + family + "…", ItemParsingCompletedHandler.displayName(name))
    }

    @Test
    fun `이름이 null 이면 기본값을 쓴다`() {
        assertEquals("상품", ItemParsingCompletedHandler.displayName(null))
    }

    @Test
    fun `이름이 공백뿐이면 기본값을 쓴다`() {
        assertEquals("상품", ItemParsingCompletedHandler.displayName("   "))
    }

    @Test
    fun `앞뒤 공백은 제거하고 이름 길이 예산에 넣지 않는다`() {
        // 추출 이름은 상류에서 trim 되지 않는다. 앞 공백이 10글자 예산을 먹으면 실제 이름이 일찍 잘린다.
        assertEquals("일이삼사오육칠팔구십", ItemParsingCompletedHandler.displayName("  일이삼사오육칠팔구십  "))
    }

    @Test
    fun `이름 속 개행은 한 칸 공백으로 접어 한 줄을 유지한다`() {
        // 개행이 그대로 들어가면 알림·푸시가 두 줄이 되어 이 문구 설계의 전제가 깨진다.
        assertEquals("나이키 에어맥스", ItemParsingCompletedHandler.displayName("나이키\n에어맥스"))
    }

    @Test
    fun `표시 10글자여도 char 길이가 과도하면 한 번 더 자른다`() {
        // 조합 부호를 쌓으면 grapheme 1개가 수백 char 이 될 수 있다. 그대로 두면 Notification 의
        // require(title.length <= 255)에 걸리고, dispatcher 가 그 예외를 삼켜 알림이 전 수신자에게 누락된다.
        val heavyCluster = "가" + "́".repeat(300) // 표시상 1글자, UTF-16 301 char
        val result = ItemParsingCompletedHandler.displayName(heavyCluster)
        assertTrue(result.length <= 101, "char 상한을 넘었다: ${result.length}")
    }
}
