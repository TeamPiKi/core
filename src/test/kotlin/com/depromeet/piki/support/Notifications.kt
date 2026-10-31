package com.depromeet.piki.support

import com.depromeet.piki.notification.domain.NotificationType
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

fun JdbcTemplate.notificationCountOf(
    userId: UUID,
    type: NotificationType,
): Int =
    queryForObject(
        "SELECT COUNT(*) FROM notifications WHERE user_id = ? AND type = ?",
        Int::class.java,
        uuidToBytes(userId),
        type.name,
    ) ?: 0
