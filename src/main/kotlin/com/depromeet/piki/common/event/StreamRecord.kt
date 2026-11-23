package com.depromeet.piki.common.event

import org.springframework.data.redis.connection.stream.MapRecord

typealias StreamRecord = MapRecord<String, String, String>
