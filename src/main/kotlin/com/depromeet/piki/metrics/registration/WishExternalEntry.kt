package com.depromeet.piki.metrics.registration

import com.depromeet.piki.common.domain.LongBaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table

@Entity
@Table(name = "wish_external_entries")
class WishExternalEntry(
    @Column(name = "wish_id", nullable = false)
    val wishId: Long,
    @Enumerated(EnumType.STRING)
    @Column(name = "entry_point", nullable = false, length = 16)
    val entryPoint: ExternalEntry,
) : LongBaseEntity()
