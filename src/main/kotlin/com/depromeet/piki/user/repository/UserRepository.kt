package com.depromeet.piki.user.repository

import com.depromeet.piki.user.domain.User
import java.util.UUID

interface UserRepository {
    fun save(user: User): User

    // 저장 직후 즉시 flush 해 DB 제약 위반(닉네임 unique 등)을 호출 메서드 안에서 동기적으로 끌어올린다.
    // 신규 가입 경로가 unique 충돌을 catch 해 409 로 변환하거나 닉네임을 재시도하려면, INSERT 가 커밋까지 미뤄지면 안 된다.
    fun saveAndFlush(user: User): User

    fun findById(id: UUID): User?

    // user 행을 비관락(FOR UPDATE)으로 잠가 읽는다. 활성 확인과 쓰기를 같은 트랜잭션에서 원자화하는 경로 전용(#776).
    // 트랜잭션 안에서만 호출한다.
    fun findByIdForUpdate(id: UUID): User?

    fun findByIds(ids: Collection<UUID>): List<User>

    fun existsByNickname(nickname: String): Boolean

    // 본인 user 를 제외한 중복 검사. 닉네임 유지·자기 닉네임으로 다시 변경하는 흐름에서
    // 본인까지 잡아 409 를 내던 결을 막기 위한 변형 (#230).
    fun existsByNicknameAndIdNot(
        nickname: String,
        excludeUserId: UUID,
    ): Boolean

    fun findNicknamesIn(candidates: Collection<String>): List<String>
}
