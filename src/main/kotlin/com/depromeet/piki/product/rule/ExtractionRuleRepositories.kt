package com.depromeet.piki.product.rule

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query

interface ExtractionRuleJpaRepository : JpaRepository<ExtractionRuleEntity, Long> {
    fun findAllByEnabledTrueOrderByIdAsc(): List<ExtractionRuleEntity>

    // 수정·삭제는 행을 잠그고 시작한다. 이력 INSERT 는 곧바로 나가고 본문 UPDATE 는 커밋 때 나가므로, 잠그지 않으면
    // 두 수정이 겹칠 때 마지막 이력과 남은 본문이 서로 다른 사람의 것이 된다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ExtractionRuleEntity r where r.id = :id")
    fun findForUpdate(id: Long): ExtractionRuleEntity?
}

interface ExtractionRuleRevisionJpaRepository : JpaRepository<ExtractionRuleRevisionEntity, Long> {
    @Query("select coalesce(max(r.id), 0) from ExtractionRuleRevisionEntity r")
    fun lastRevisionId(): Long

    // 인덱스 없이 읽는다. 이력은 사람이 규칙을 저장할 때만 한 줄씩 늘어 전수를 훑어도 수백 행이다
    fun findTop20ByExtractionRuleIdOrderByIdDesc(extractionRuleId: Long): List<ExtractionRuleRevisionEntity>
}
