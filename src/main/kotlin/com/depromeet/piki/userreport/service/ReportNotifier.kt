package com.depromeet.piki.userreport.service

import com.depromeet.piki.userreport.service.dto.ReportNotification

// 신고가 접수됐음을 운영에 알린다. 저장이 끝난 뒤 트랜잭션 밖에서 불린다.
//
// 포트로 두는 이유: 보내는 수단(디스코드)이 admin 설정에 묶인 조건부 빈이라, 서비스가 그 사정을 모르게 한다.
interface ReportNotifier {
    fun notify(notification: ReportNotification)
}
