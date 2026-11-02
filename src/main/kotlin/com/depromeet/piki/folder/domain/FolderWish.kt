package com.depromeet.piki.folder.domain

import com.depromeet.piki.common.domain.LongBaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

// 폴더가 위시를 가리키는 연결. 빼면 행을 지운다. createdAt 이 폴더에 넣은 시각이다
@Entity
@Table(name = "folder_wishes")
class FolderWish(

    @Column(nullable = false, updatable = false)
    val folderId: Long,

    @Column(nullable = false, updatable = false)
    val wishId: Long,

) : LongBaseEntity()
