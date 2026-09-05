package com.depromeet.piki.common.exception

import org.springframework.dao.DataIntegrityViolationException
import java.sql.SQLIntegrityConstraintViolationException

private const val MYSQL_DUPLICATE_ENTRY = 1062

fun DataIntegrityViolationException.isDuplicateKey(): Boolean =
    (mostSpecificCause as? SQLIntegrityConstraintViolationException)?.errorCode == MYSQL_DUPLICATE_ENTRY
