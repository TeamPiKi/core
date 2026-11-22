package com.depromeet.piki.tournament.service.dto

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 마스킹은 요청자 관점으로 결과를 다시 쓰는 순수 함수라, 분기 망라는 Spring·DB 없이 여기서 한다.
// 통합 테스트는 이 규칙이 실제 응답 계약까지 이어지는지만 확인한다.
class GroupResultTest {
    private val maskedImage = "https://cdn.example.com/defaults/user-masked.png"

    private fun participant(
        userId: UUID,
        nickname: String,
        isWithdrawn: Boolean = false,
        isHost: Boolean = false,
    ): ParticipantSummary =
        ParticipantSummary(
            userId = userId,
            nickname = nickname,
            profileImage = "https://cdn.example.com/$nickname.png",
            isWithdrawn = isWithdrawn,
            isHost = isHost,
        )

    private fun itemWith(chosenBy: List<ParticipantSummary>): GroupResultItem =
        GroupResultItem(
            rank = 1,
            itemId = 10,
            name = "나이키 에어맥스",
            price = 129_000,
            originalPrice = null,
            discountRate = null,
            currency = "KRW",
            imageUrl = "https://cdn.example.com/items/1.jpg",
            chosenBy = chosenBy,
        )

    @Test
    fun `본인은 가려지지 않고 chosenBy 맨 앞으로 온다`() {
        val me = UUID.randomUUID()
        val result = GroupResult(
            items = listOf(
                itemWith(
                    listOf(
                        participant(UUID.randomUUID(), "남1"),
                        participant(me, "나"),
                        participant(UUID.randomUUID(), "남2"),
                    ),
                ),
            ),
        )

        val masked = result.maskedFor(me, maskedImage)

        val first = masked.items[0].chosenBy[0]
        assertEquals(me, first.userId)
        assertEquals("나", first.nickname)
        assertEquals(false, first.isMasked)
    }

    @Test
    fun `본인 외 참여자는 userId 가 지워지고 물음표 닉네임과 마스킹 아바타로 덮인다`() {
        val me = UUID.randomUUID()
        val result = GroupResult(
            items = listOf(itemWith(listOf(participant(me, "나"), participant(UUID.randomUUID(), "남1")))),
        )

        val other = result.maskedFor(me, maskedImage).items[0].chosenBy[1]

        assertNull(other.userId, "가려진 참여자의 userId 가 남으면 토너먼트를 넘나들며 동일인을 추적할 수 있다")
        assertEquals(ParticipantSummary.MASKED_NICKNAME, other.nickname)
        assertEquals(maskedImage, other.profileImage)
        assertTrue(other.isMasked)
    }

    @Test
    fun `가려진 참여자가 탈퇴 유저여도 isWithdrawn 은 false 로 덮인다`() {
        // 탈퇴 여부를 남기면 "탈퇴한 사람" 과 "로그인하면 보이는 사람" 이 화면에서 갈려 마스킹이 그만큼 샌다.
        val me = UUID.randomUUID()
        val result = GroupResult(
            items = listOf(
                itemWith(
                    listOf(
                        participant(me, "나"),
                        participant(UUID.randomUUID(), "탈퇴aaaaaaaa", isWithdrawn = true),
                    ),
                ),
            ),
        )

        val other = result.maskedFor(me, maskedImage).items[0].chosenBy[1]

        assertEquals(false, other.isWithdrawn)
        assertEquals(ParticipantSummary.MASKED_NICKNAME, other.nickname)
    }

    @Test
    fun `가려진 참여자가 주최자여도 isHost 는 false 로 덮인다`() {
        // 게스트는 자기를 초대한 사람이 주최자임을 이미 안다 — 배지 하나가 가려진 한 명의 신원을 그대로 지목한다.
        val me = UUID.randomUUID()
        val result = GroupResult(
            items = listOf(
                itemWith(
                    listOf(
                        participant(me, "나"),
                        participant(UUID.randomUUID(), "주최자", isHost = true),
                    ),
                ),
            ),
        )

        val other = result.maskedFor(me, maskedImage).items[0].chosenBy[1]

        assertEquals(false, other.isHost)
        assertEquals(ParticipantSummary.MASKED_NICKNAME, other.nickname)
    }

    @Test
    fun `본인이 주최자면 가려지지 않으므로 isHost 가 그대로 남는다`() {
        val me = UUID.randomUUID()
        val result = GroupResult(
            items = listOf(itemWith(listOf(participant(UUID.randomUUID(), "남1"), participant(me, "나", isHost = true)))),
        )

        val mine = result.maskedFor(me, maskedImage).items[0].chosenBy[0]

        assertEquals(me, mine.userId)
        assertEquals(true, mine.isHost)
    }

    @Test
    fun `본인이 고르지 않은 아이템은 선택자가 전원 가려진다`() {
        val me = UUID.randomUUID()
        val result = GroupResult(
            items = listOf(itemWith(listOf(participant(UUID.randomUUID(), "남1"), participant(UUID.randomUUID(), "남2")))),
        )

        val chosenBy = result.maskedFor(me, maskedImage).items[0].chosenBy

        assertEquals(2, chosenBy.size)
        assertTrue(chosenBy.all { it.isMasked }, "본인이 없는 아이템은 전원 마스킹돼야 한다: $chosenBy")
    }

    @Test
    fun `본인을 앞으로 뽑아내도 나머지 참여자의 집계 순서는 보존된다`() {
        val me = UUID.randomUUID()
        val first = participant(UUID.randomUUID(), "남1")
        val second = participant(UUID.randomUUID(), "남2")
        val result = GroupResult(items = listOf(itemWith(listOf(first, participant(me, "나"), second))))

        val chosenBy = result.maskedFor(me, maskedImage).items[0].chosenBy

        // 가려진 뒤엔 서로 구분이 안 되므로, 순서 보존은 "본인만 앞으로 빠지고 나머지 개수·자리는 그대로" 로 확인한다.
        assertEquals(listOf(false, true, true), chosenBy.map { it.isMasked })
    }

    @Test
    fun `아이템의 이름 가격 이미지 순위는 마스킹해도 그대로 남는다`() {
        // 결과를 통째로 가리면 플레이를 끝내고도 결과를 못 봐 이탈한다 — 가리는 건 사람뿐이라는 정책이다.
        val me = UUID.randomUUID()
        val item = itemWith(listOf(participant(UUID.randomUUID(), "남1")))
        val result = GroupResult(items = listOf(item))

        val masked = result.maskedFor(me, maskedImage).items[0]

        assertEquals(item.rank, masked.rank)
        assertEquals(item.itemId, masked.itemId)
        assertEquals(item.name, masked.name)
        assertEquals(item.price, masked.price)
        assertEquals(item.currency, masked.currency)
        assertEquals(item.imageUrl, masked.imageUrl)
    }

    @Test
    fun `여러 아이템에 걸쳐 마스킹이 적용된다`() {
        val me = UUID.randomUUID()
        val result = GroupResult(
            items = listOf(
                itemWith(listOf(participant(me, "나"))),
                itemWith(listOf(participant(UUID.randomUUID(), "남1"))).copy(rank = 2, itemId = 20),
            ),
        )

        val masked = result.maskedFor(me, maskedImage)

        assertEquals(false, masked.items[0].chosenBy[0].isMasked)
        assertEquals(true, masked.items[1].chosenBy[0].isMasked)
    }
}
