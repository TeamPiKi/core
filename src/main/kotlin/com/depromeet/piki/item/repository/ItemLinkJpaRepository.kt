package com.depromeet.piki.item.repository

import com.depromeet.piki.item.domain.ItemLink
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ItemLinkJpaRepository : JpaRepository<ItemLink, Long> {
    // INSERT IGNORE(MySQL) — url_hash unique 충돌 시 예외 대신 0행으로 끝난다. 별칭 기록은 등록·파싱 완료
    // 트랜잭션 안에서 도는데, 중복이 예외로 터지면 그 트랜잭션(등록 자체)이 죽는다. 중복은 오류가 아니라
    // "이미 아는 링크 모양"이라는 정상 사실이므로 문장 수준에서 조용히 넘긴다.
    // native 인 이유: JPA save 는 충돌 시 예외 경로뿐이고, auditing 컬럼(created_at·updated_at)은 직접 채운다.
    @Modifying
    @Query(
        value = """
            INSERT IGNORE INTO item_links (url, url_hash, item_id, created_at, updated_at)
            VALUES (:url, :urlHash, :itemId, NOW(6), NOW(6))
        """,
        nativeQuery = true,
    )
    fun insertIgnore(
        @Param("url") url: String,
        @Param("urlHash") urlHash: String,
        @Param("itemId") itemId: Long,
    ): Int

    fun findByUrlHashAndDeletedAtIsNull(urlHash: String): ItemLink?

    fun findByItemIdAndDeletedAtIsNull(itemId: Long): List<ItemLink>
}
