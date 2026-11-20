package com.depromeet.piki.common.event

import org.springframework.modulith.events.FailedEventPublications
import org.springframework.modulith.events.ResubmissionOptions
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class FailedPublicationResubmitter(
    private val failedEventPublications: FailedEventPublications,
) {
    @Scheduled(fixedDelayString = "\${event-publication.resubmit-interval}")
    fun resubmit() {
        failedEventPublications.resubmit(ResubmissionOptions.defaults())
    }
}
