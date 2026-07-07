package com.depromeet.piki.common.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.core.JsonGenerator
import tools.jackson.core.JsonParser
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.ValueDeserializer
import tools.jackson.databind.ValueSerializer
import tools.jackson.databind.module.SimpleModule
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

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
            // request DTO(UpdateInviteDurationRequest.newExpiresAt 등)로 LocalDateTime 을 받을 때, 클라이언트가
            // 응답받은 +09:00 값을 그대로 재전송하면 기본 역직렬화가 offset suffix 를 못 읽어 실패한다(400).
            // 응답의 KST 직렬화와 대칭이 되도록 deserializer 도 등록해, offset 이 있으면 instant 를 보존하며 UTC 로 되돌린다.
            addSerializer(LocalDateTime::class.java, LocalDateTimeKstSerializer)
            addDeserializer(LocalDateTime::class.java, LocalDateTimeKstDeserializer)
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

    private object LocalDateTimeKstDeserializer : ValueDeserializer<LocalDateTime>() {
        override fun deserialize(p: JsonParser, ctxt: DeserializationContext): LocalDateTime {
            val text = p.string
            // offset suffix(+09:00 등)가 있으면 응답의 KST 직렬화를 그대로 재전송한 값이다 —
            // 같은 순간(instant)으로 UTC wall-clock 을 복원해 저장 계약(UTC)과 대칭을 맞춘다.
            // offset 이 없으면 기존 클라이언트 계약대로 LocalDateTime 을 그대로 파싱한다(하위호환).
            return try {
                OffsetDateTime.parse(text).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime()
            } catch (e: DateTimeParseException) {
                LocalDateTime.parse(text)
            }
        }
    }
}
