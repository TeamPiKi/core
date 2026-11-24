package com.depromeet.piki.common.event

import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.stream.StreamListener

// 빈으로 등록하면 읽기(RedisStreamListening)와 이어받기(RedisStreamReclaimer)가 함께 붙음
data class RedisStreamSubscription(
    val stream: String,
    val group: String,
    val consumer: String,
    val listener: StreamListener<String, MapRecord<String, String, String>>,
)
