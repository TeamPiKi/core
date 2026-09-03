package com.depromeet.piki.common.ratelimit

import com.depromeet.piki.common.exception.ErrorCode
import java.util.UUID

// 누구 몫에서 깎고, 그 몫이 바닥났을 때 뭐라고 답할 것인가. 둘은 함께 정해져야 한다 —
// 문구가 주인을 전제하기 때문이다(토너먼트 코드는 오너의 사용량을 감추는 문구를 쓴다).
// 따로 넘기면 위시 주인에 토너먼트 코드를 실어도 컴파일된다.
data class ItemQuota(
    val owner: UUID,
    val errorCode: ErrorCode,
)
