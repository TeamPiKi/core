package com.depromeet.piki.common.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.core.JsonGenerator
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.ValueSerializer
import tools.jackson.databind.module.SimpleModule
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Configuration
class JacksonConfig {
    @Bean
    fun localDateTimeModule(): SimpleModule =
        SimpleModule().apply {
            // 저장값은 serverTimezone=UTC 기준의 UTC wall-clock 을 담은 LocalDateTime(타임존 정보 없음)이다.
            // 인앱에 표시되는 모든 시각은 KST 여야 하므로, 저장된 UTC 를 같은 순간(instant)의
            // KST(+09:00) 로 변환해 ISO-8601 with offset 형태로 직렬화한다.
            // instant 자체는 보존되고 오프셋 표기만 UTC→KST 로 바뀐다 — 오프셋을 파싱하는 클라이언트는
            // 동일한 순간으로 안전하게 처리하고, 날짜 문자열을 그대로 쓰는 클라이언트는 KST 날짜를 얻는다.
            //
            // 주의: 현재 코드베이스에는 request body 로 LocalDateTime 을 직접 받는 필드가 없다.
            // 추후 request DTO 에 LocalDateTime 필드를 추가하는 경우, 클라이언트가 offset suffix 를
            // 붙여 보내면 기본 역직렬화가 실패한다. 그 시점에 custom deserializer 도 함께 추가해야 한다.
            addSerializer(LocalDateTime::class.java, LocalDateTimeKstSerializer)
        }

    private object LocalDateTimeKstSerializer : ValueSerializer<LocalDateTime>() {
        // KST 는 DST 가 없어 항상 +09:00 고정 오프셋이다.
        private val KST = ZoneOffset.ofHours(9)
        private val formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

        override fun serialize(value: LocalDateTime, gen: JsonGenerator, ctxt: SerializationContext) {
            gen.writeString(
                value.atOffset(ZoneOffset.UTC)
                    .withOffsetSameInstant(KST)
                    .format(formatter),
            )
        }
    }
}
