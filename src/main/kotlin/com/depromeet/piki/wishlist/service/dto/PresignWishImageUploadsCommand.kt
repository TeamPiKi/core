package com.depromeet.piki.wishlist.service.dto

import com.depromeet.piki.image.domain.UploadFormat
import java.util.UUID

data class PresignWishImageUploadsCommand(
    val userId: UUID,
    val uploadFormats: List<UploadFormat>,
)
