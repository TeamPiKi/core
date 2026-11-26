package com.depromeet.piki.user.event

import java.util.UUID

// 프로필을 비공개로 바꿨다. 그 사람의 위시와 폴더가 남에게 보이지 않게 된다
data class UserProfileHidden(val userId: UUID)

data class UserWithdrawn(val userId: UUID)
