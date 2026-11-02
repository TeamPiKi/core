package com.depromeet.piki.folder.domain

import com.depromeet.piki.common.domain.LongBaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.LocalDateTime
import java.util.UUID

// 위시를 묶는 폴더. 위시 목록은 들지 않는다 — 연결은 FolderWish 행이고, 넣기·빼기·이동 규칙은 FolderContents 가 맡는다
@Entity
@Table(name = "folders")
class Folder(

    @Column(nullable = false, updatable = false, columnDefinition = "BINARY(16)")
    val userId: UUID,

    name: FolderName,

) : LongBaseEntity() {
    @Column(nullable = false, length = FolderName.MAX_CODE_POINTS)
    var name: String = name.value
        protected set

    fun rename(name: FolderName) {
        this.name = name.value
    }

    fun delete() {
        deletedAt = LocalDateTime.now()
    }
}
