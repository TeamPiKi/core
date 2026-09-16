package com.depromeet.piki.metrics.registration

import org.springframework.data.jpa.repository.JpaRepository

interface WishExternalEntryRepository : JpaRepository<WishExternalEntry, Long>
