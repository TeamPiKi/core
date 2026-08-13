package com.depromeet.piki.notification.handler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 상품명 표시 다듬기. 두 호출부가 캡 요구가 달라(파싱 완료는 절단 없음, 아이템 삭제는 10글자) 양쪽을 다 고정한다.
class ItemDisplayNameTest {
    // ---- 절단 없음 (파싱 완료 알림 #913) ----

    @Test
    fun `maxGraphemes 를 안 주면 긴 이름도 자르지 않는다`() {
        // 파싱 완료는 title 이 이름뿐이라 OS 가 잘라도 의미가 안 사라진다. 우리가 미리 자를 이유가 없다.
        val long = "나이키 에어포스 1 07 화이트 로우탑 스니커즈"
        assertEquals(long, ItemDisplayName.of(long))
    }

    @Test
    fun `절단을 안 해도 char 상한은 걸린다`() {
        // 조합 부호를 쌓으면 grapheme 1개가 수백 char 이 된다. 그대로 두면 Notification 의
        // require(title.length <= 255)에 걸리고, dispatcher 가 그 예외를 삼켜 알림이 전 수신자에게 누락된다.
        val heavy = "가" + "́".repeat(300)
        assertTrue(ItemDisplayName.of(heavy).length <= 101, "char 상한을 넘었다")
    }

    // ---- 절단 있음 (아이템 삭제 알림) ----

    @Test
    fun `maxGraphemes 를 주면 초과분을 말줄임표로 자른다`() {
        assertEquals("일이삼사오육칠팔구십…", ItemDisplayName.of("일이삼사오육칠팔구십일이삼", 10))
    }

    @Test
    fun `정확히 한도인 이름은 자르지 않는다`() {
        val ten = "일이삼사오육칠팔구십"
        assertEquals(ten, ItemDisplayName.of(ten, 10))
    }

    @Test
    fun `한도 이하 이름은 그대로 쓴다`() {
        assertEquals("나이키 에어맥스", ItemDisplayName.of("나이키 에어맥스", 10))
    }

    @Test
    fun `경계에 걸친 이모지는 반쪽으로 잘리지 않는다`() {
        // UTF-16 take(10) 이면 surrogate pair 가 쪼개져 깨진 문자가 노출된다. grapheme 경계로 잘라 통째로 살린다.
        assertEquals("일이삼사오육칠팔구😀…", ItemDisplayName.of("일이삼사오육칠팔구😀가나", 10))
    }

    @Test
    fun `ZWJ 로 이어진 이모지 시퀀스도 통째로 유지된다`() {
        // 가족 이모지는 사람 4개를 ZWJ 로 이은 확장 grapheme cluster 1개(UTF-16 길이 11).
        // codePoint 기반 절단이면 ZWJ 경계에서 쪼개진다.
        val family = "👨‍👩‍👧‍👦"
        assertEquals("일이삼사오육칠팔구" + family + "…", ItemDisplayName.of("일이삼사오육칠팔구" + family + "가나", 10))
    }

    // ---- 공통 정규화 ----

    @Test
    fun `이름이 null 이면 기본값을 쓴다`() {
        assertEquals(ItemDisplayName.FALLBACK, ItemDisplayName.of(null))
    }

    @Test
    fun `이름이 공백뿐이면 기본값을 쓴다`() {
        assertEquals(ItemDisplayName.FALLBACK, ItemDisplayName.of("   "))
    }

    @Test
    fun `앞뒤 공백은 제거하고 글자 예산에 넣지 않는다`() {
        // 추출 이름은 상류에서 trim 되지 않는다. 앞 공백이 예산을 먹으면 실제 이름이 일찍 잘린다.
        assertEquals("일이삼사오육칠팔구십", ItemDisplayName.of("  일이삼사오육칠팔구십  ", 10))
    }

    @Test
    fun `이름 속 개행은 한 칸 공백으로 접는다`() {
        // 개행이 그대로 들어가면 알림·푸시가 의도치 않게 두 줄이 된다.
        assertEquals("나이키 에어맥스", ItemDisplayName.of("나이키\n에어맥스"))
    }
}
