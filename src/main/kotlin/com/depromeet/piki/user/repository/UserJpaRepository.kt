package com.depromeet.piki.user.repository

import com.depromeet.piki.user.domain.User
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface UserJpaRepository : JpaRepository<User, UUID> {
    // user 행을 SELECT ... FOR UPDATE 로 잠가 읽는다. 활성 확인과 쓰기를 같은 트랜잭션에서 원자화하는
    // 쓰기 경로(프로필 수정·wish 등록·FCM 등록)가, 확인~쓰기 사이에 탈퇴 cascade 가 끼어드는 경합을 막기 위해 쓴다(#776).
    // 반드시 트랜잭션 안에서 호출한다 — 락은 트랜잭션 경계까지만 유지된다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    fun findByIdForUpdate(
        @Param("id") id: UUID,
    ): User?

    // 여러 사용자 행을 id 오름차순으로 잠근다(#1288). 두 사람을 함께 잠가야 하는 경로가 서로 반대 순서로
    // 잡아 교착하는 것을 막는다 — 정렬을 질의에 박아 호출 순서와 무관하게 같은 순서가 되게 한다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id IN :ids ORDER BY u.id ASC")
    fun findAllByIdsForUpdate(
        @Param("ids") ids: Collection<UUID>,
    ): List<User>
}
