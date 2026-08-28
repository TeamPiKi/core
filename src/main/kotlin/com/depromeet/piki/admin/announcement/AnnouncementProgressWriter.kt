package com.depromeet.piki.admin.announcement
import com.depromeet.piki.announcement.repository.AnnouncementRepository

import com.depromeet.piki.admin.config.ConditionalOnAdminEnabled
import com.depromeet.piki.notification.fcm.service.UserDeviceService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

// 공지 발송의 짧은 트랜잭션 경계(#489). async fan-out(AnnouncementSendService.execute)은 외부 FCM 호출이 섞여
// 트랜잭션 밖에서 돌므로, 상태 전환·진행률 갱신·delivery 행 저장만 이 별도 빈의 짧은 트랜잭션에 위임한다
// (CLAUDE.md "외부 호출은 트랜잭션 밖" + self-invocation 회피 — async 빈에서 직접 @Transactional 호출은 proxy 우회).
@Service
@ConditionalOnAdminEnabled
class AnnouncementProgressWriter(
    private val announcementRepository: AnnouncementRepository,
    private val deliveryRepository: AnnouncementDeliveryRepository,
    private val userDeviceService: UserDeviceService,
) {
    // 발송할 푸시 내용 + 대상 수신자. claim 이 성공하면 SENDING 으로 전환된 상태다.
    // 알림(notifications, ≤255)엔 공지 body(마크다운 장문)가 아니라 push 전용 문구가 들어간다(#561). 긴 body 는 공지 페이지 전용.
    data class Claim(
        val pushTitle: String,
        val pushBody: String,
        val pushEnabled: Boolean,
        val recipients: List<UUID>,
    )

    // SENDING 클레임 — DRAFT/SCHEDULED 일 때만 SENDING 으로 전환하고 대상자를 못 박는다(진행률 분모).
    // 이미 처리 중·완료거나 없는 공지면 null(중복 발송 차단). 비관적 락(findByIdForUpdate)으로 조회→검사→저장을
    // 직렬화해, 더블클릭·스케줄러/수동 경합에서 같은 공지가 두 번 SENDING 으로 전환되는 중복 발송을 막는다.
    @Transactional
    fun claim(
        announcementId: Long,
        actor: String,
    ): Claim? {
        val announcement = announcementRepository.findByIdForUpdate(announcementId) ?: return null
        if (!(announcement.isDraft || announcement.isScheduled)) return null
        val recipients = userDeviceService.findAllTokenHolderIds()
        announcement.markSending(recipientCount = recipients.size, sentBy = actor)
        announcementRepository.save(announcement)
        return Claim(announcement.effectivePushTitle, announcement.effectivePushBody, announcement.pushEnabled, recipients)
    }

    // 배치 flush — delivery 건별 행 저장 + 진행률 누적. fan-out 중 주기적으로 호출돼 폴링이 진행률을 본다.
    @Transactional
    fun flush(
        announcementId: Long,
        deliveries: List<AnnouncementDelivery>,
        successDelta: Int,
        failureDelta: Int,
        skippedDelta: Int,
    ) {
        deliveryRepository.saveAll(deliveries)
        val announcement = announcementRepository.findById(announcementId).orElse(null) ?: return
        announcement.addProgress(successDelta, failureDelta, skippedDelta)
        announcementRepository.save(announcement)
    }

    // 마지막 배치 flush + SENT 고정.
    @Transactional
    fun finish(
        announcementId: Long,
        deliveries: List<AnnouncementDelivery>,
        successDelta: Int,
        failureDelta: Int,
        skippedDelta: Int,
    ) {
        deliveryRepository.saveAll(deliveries)
        val announcement = announcementRepository.findById(announcementId).orElse(null) ?: return
        announcement.addProgress(successDelta, failureDelta, skippedDelta)
        announcement.markSent()
        announcementRepository.save(announcement)
    }

    // 유예시간 넘긴 예약을 MISSED 로 정리. 중복 처리·경합을 막으려 claim 과 같은 비관적 락으로 직렬화한다.
    // SCHEDULED 가 아니면(이미 발송/정리됨) 조용히 넘어간다.
    @Transactional
    fun markMissed(announcementId: Long) {
        val announcement = announcementRepository.findByIdForUpdate(announcementId) ?: return
        if (!announcement.isScheduled) return
        announcement.markMissed()
        announcementRepository.save(announcement)
    }
}
