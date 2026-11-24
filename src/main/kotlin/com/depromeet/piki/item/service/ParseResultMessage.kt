package com.depromeet.piki.item.service

import com.depromeet.piki.contracts.extraction.v1.ExtractionErrorCode
import com.depromeet.piki.contracts.extraction.v1.ExtractionMethod
import com.depromeet.piki.contracts.extraction.v1.ExtractionProto
import com.depromeet.piki.contracts.extraction.v1.ParseResult
import com.depromeet.piki.product.service.ProductSnapshot
import com.depromeet.piki.product.service.remote.ProductExtractorException
import com.depromeet.piki.product.service.remote.RemoteExtractionContract

data class ParseResultMessage(
    val requestId: Long,
    val code: String?,
    private val fields: Map<String, String>,
) {
    fun isSuccess(): Boolean = code == CODE_OK && EXTRACTED_FIELDS.any { !fields[it].isNullOrBlank() }

    fun toSnapshot(): ProductSnapshot =
        ProductSnapshot.fromExtracted(
            name = fields[NAME],
            imageUrl = fields[IMAGE_URL],
            price = fields[CURRENT_PRICE]?.toIntOrNull(),
            currency = fields[CURRENCY],
            finalUrl = fields[FINAL_URL],
            method = fields[METHOD]?.let { name -> ExtractionMethod.entries.firstOrNull { it.name == name } },
            originalPrice = fields[ORIGINAL_PRICE]?.toIntOrNull(),
        )

    fun toFailure(): Throwable {
        val errorCode = ExtractionErrorCode.entries.firstOrNull { it.name == code } ?: return ProductExtractorException.permanentFailure()
        val translation = RemoteExtractionContract.PERMANENT_TRANSLATIONS[errorCode] ?: return ProductExtractorException.transientFailure(null)
        return translation()
    }

    companion object {
        val STREAM: String = ParseResult.getDescriptor().options.getExtension(ExtractionProto.redisStream)
        val GROUP: String = ParseResult.getDescriptor().options.getExtension(ExtractionProto.redisConsumerGroup)

        // 계약은 이 값을 ParseResult.code 의 주석으로만 가짐
        const val CODE_OK = "OK"

        private val REQUEST_ID = fieldName(ParseResult.REQUEST_ID_FIELD_NUMBER)
        private val CODE = fieldName(ParseResult.CODE_FIELD_NUMBER)
        private val NAME = fieldName(ParseResult.NAME_FIELD_NUMBER)
        private val IMAGE_URL = fieldName(ParseResult.IMAGE_URL_FIELD_NUMBER)
        private val CURRENT_PRICE = fieldName(ParseResult.CURRENT_PRICE_FIELD_NUMBER)
        private val CURRENCY = fieldName(ParseResult.CURRENCY_FIELD_NUMBER)
        private val FINAL_URL = fieldName(ParseResult.FINAL_URL_FIELD_NUMBER)
        private val METHOD = fieldName(ParseResult.METHOD_FIELD_NUMBER)
        private val ORIGINAL_PRICE = fieldName(ParseResult.ORIGINAL_PRICE_FIELD_NUMBER)
        private val EXTRACTED_FIELDS = listOf(NAME, IMAGE_URL, CURRENT_PRICE)

        fun of(fields: Map<String, String>): ParseResultMessage? {
            val requestId = fields[REQUEST_ID]?.toLongOrNull() ?: return null
            return ParseResultMessage(requestId, fields[CODE], fields)
        }

        private fun fieldName(number: Int): String = ParseResult.getDescriptor().findFieldByNumber(number).jsonName
    }
}
