package com.depromeet.piki.item.service

import com.depromeet.piki.contracts.extraction.v1.ExtractionErrorCode
import com.depromeet.piki.contracts.extraction.v1.ExtractionMethod
import com.depromeet.piki.product.service.ProductSnapshot
import com.depromeet.piki.product.service.remote.ProductExtractorException
import com.depromeet.piki.product.service.remote.RemoteExtractionContract

data class ParseResultMessage(
    val requestId: Long,
    val code: String?,
    private val fields: Map<String, String>,
) {
    fun isSuccess(): Boolean = code == CODE_OK

    fun toSnapshot(): ProductSnapshot =
        ProductSnapshot.fromExtracted(
            name = fields["name"],
            imageUrl = fields["imageUrl"],
            price = fields["currentPrice"]?.toIntOrNull(),
            currency = fields["currency"],
            finalUrl = fields["finalUrl"],
            method = fields["method"]?.let { name -> ExtractionMethod.entries.firstOrNull { it.name == name } },
            originalPrice = fields["originalPrice"]?.toIntOrNull(),
        )

    fun toFailure(): Throwable {
        val errorCode = ExtractionErrorCode.entries.firstOrNull { it.name == code } ?: return ProductExtractorException.permanentFailure()
        val translation = RemoteExtractionContract.PERMANENT_TRANSLATIONS[errorCode] ?: return ProductExtractorException.transientFailure(null)
        return translation()
    }

    companion object {
        const val STREAM = "parse:results"
        const val CODE_OK = "OK"

        fun of(fields: Map<String, String>): ParseResultMessage? {
            val requestId = fields["requestId"]?.toLongOrNull() ?: return null
            return ParseResultMessage(requestId, fields["code"], fields)
        }
    }
}
