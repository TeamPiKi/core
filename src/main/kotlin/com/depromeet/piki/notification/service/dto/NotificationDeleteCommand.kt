package com.depromeet.piki.notification.service.dto

// 삭제 명령 — 요청 DTO(NotificationDeleteRequest)의 XOR 검증을 통과한 "정확히 한 가지" 의도를 타입으로 고정한다.
// 읽음(NotificationReadCommand)과 대칭이다. 서비스는 when + sealed 로 분기해 nullable 잡탕 분기를 피한다.
sealed interface NotificationDeleteCommand {
    // 본인 알림 전부 삭제 (읽음 무관, 모두 삭제 버튼).
    data object All : NotificationDeleteCommand

    // 지정한 알림들만 삭제 (단건·다건). 본인 소유만 반영되고 타인/없는 id 는 무영향(멱등).
    data class Ids(
        val ids: List<Long>,
    ) : NotificationDeleteCommand
}
