package com.depromeet.piki.profile.repository

import java.util.UUID

// "이 사용자의 공개 ○○가 몇 개" 한 줄. 폴더 수·위시 수가 같은 모양이라 뷰 하나를 공유한다.
interface UserCountView {
    val userId: UUID

    val count: Long
}
