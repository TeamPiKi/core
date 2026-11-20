package com.depromeet.piki.user.repository

import com.depromeet.piki.user.domain.User
import java.util.UUID

interface UserRepository {
    fun save(user: User): User

    fun findById(id: UUID): User?

    // user 행을 비관락(FOR UPDATE)으로 잠가 읽는다. 활성 확인과 쓰기를 같은 트랜잭션에서 원자화하는 경로 전용(#776).
    // 트랜잭션 안에서만 호출한다.
    fun findByIdForUpdate(id: UUID): User?

    fun findByIds(ids: Collection<UUID>): List<User>

}
