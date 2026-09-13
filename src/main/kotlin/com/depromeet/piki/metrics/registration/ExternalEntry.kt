package com.depromeet.piki.metrics.registration

enum class ExternalEntry {
    SHARE_SHEET,
    ;

    companion object {
        const val HEADER = "X-Client-Entry-Point"

        fun from(raw: String?): ExternalEntry? {
            val normalized = raw?.trim().orEmpty()
            return entries.firstOrNull { it.name.equals(normalized, ignoreCase = true) }
        }
    }
}
