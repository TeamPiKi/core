package com.depromeet.piki.folder.service

import com.depromeet.piki.item.service.ItemCardService
import com.depromeet.piki.wishlist.repository.WishRepository
import org.springframework.stereotype.Component

// 폴더 카드 표지. 내 폴더 목록과 다른 사람 프로필의 폴더 카드가 같은 규칙을 쓴다(#1302) — 따로 두면 두 화면의 표지가 어긋난다.
// 표지는 최근 넣은 위시 최대 3개. 이미지가 없는 위시(파싱 중·실패)도 칸을 차지하고 null 로 둔다 — 앱이 그 칸을 기본 이미지로 채운다(정책 5장).
// 연결 id 는 호출부가 이미 읽어 온다(개수에도 쓰임). 비싼 카드 계산은 폴더당 표지 칸만큼만 한다
@Component
class FolderCovers(
    private val wishRepository: WishRepository,
    private val itemCardService: ItemCardService,
) {
    // 폴더 id 별 위시 id(최근 넣은 순)를 받아 폴더 id 별 표지 칸을 돌려준다. 위시가 없는 폴더는 결과에 없다
    fun coversOf(wishIdsByFolder: Map<Long, List<Long>>): Map<Long, List<String?>> {
        val coverWishesByFolder = wishIdsByFolder.mapValues { (_, ids) -> ids.take(COVER_IMAGE_COUNT) }
        val imageByWish = imageUrlsOf(coverWishesByFolder.values.flatten().distinct())
        return coverWishesByFolder.mapValues { (_, ids) -> ids.map { imageByWish[it] } }
    }

    private fun imageUrlsOf(wishIds: List<Long>): Map<Long, String> {
        if (wishIds.isEmpty()) return emptyMap()
        val wishes = wishRepository.findAllByIds(wishIds)
        val cards = itemCardService.cardsOf(wishes.map { it.cardQuery() })
        return wishes
            .mapNotNull { wish -> cards.getValue(wish.cardQuery()).display?.imageUrl?.let { wish.getId() to it } }
            .toMap()
    }

    companion object {
        private const val COVER_IMAGE_COUNT = 3
    }
}
