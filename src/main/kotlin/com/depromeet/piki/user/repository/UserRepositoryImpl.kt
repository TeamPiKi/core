package com.depromeet.piki.user.repository

import com.depromeet.piki.user.domain.User
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class UserRepositoryImpl(
    private val userJpaRepository: UserJpaRepository,
) : UserRepository {
    override fun save(user: User): User = userJpaRepository.save(user)

    override fun saveAndFlush(user: User): User = userJpaRepository.saveAndFlush(user)

    override fun findById(id: UUID): User? = userJpaRepository.findByIdOrNull(id)

    override fun findByIdForUpdate(id: UUID): User? = userJpaRepository.findByIdForUpdate(id)

    override fun findByIds(ids: Collection<UUID>): List<User> = userJpaRepository.findAllById(ids)

    override fun existsByNickname(nickname: String): Boolean = userJpaRepository.existsByNickname(nickname)

    override fun existsByNicknameAndIdNot(
        nickname: String,
        excludeUserId: UUID,
    ): Boolean = userJpaRepository.existsByNicknameAndIdNot(nickname, excludeUserId)

    override fun findNicknamesIn(candidates: Collection<String>): List<String> =
        userJpaRepository.findNicknamesIn(candidates)
}
