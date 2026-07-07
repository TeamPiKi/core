package com.depromeet.piki.image.service

import com.depromeet.piki.common.storage.ImageStorage
import com.depromeet.piki.image.domain.ProductImage
import com.depromeet.piki.product.service.ProductSnapshot
import org.springframework.stereotype.Component
import java.util.UUID

// 본 서버 안에서 이미지 파싱 전체(download→OCR→crop→결과 업로드)를 수행하는 embedded 구현.
// 원격 전환 게이트(product.extract.remote 의 enabled + image-enabled)가 켜지면 HttpImageSnapshotExtractor(@Primary)가
// 이 빈을 가로채고, 전환이 끝나면(이관 8단계) embedded 파이프라인(OCR·크롭)과 함께 제거된다.
@Component
class EmbeddedImageSnapshotExtractor(
    private val productImageExtractor: ProductImageExtractor,
    private val imageCropper: ImageCropper,
    private val imageStorage: ImageStorage,
) : ImageSnapshotExtractor {
    override fun extract(imageKey: String): ProductSnapshot {
        val stored = imageStorage.download(imageKey)
        // download 가 S3 content-type 메타를 못 주면(메타 유실 등) 등록 때 key 에 박은 확장자로 mimeType 을 복원한다 —
        // 멀쩡한 raw 가 메타 결함만으로 비복구 FAILED 되는 것을 막는다(key 확장자가 우리가 박은 신뢰값, content-type 은 fallback).
        val contentType = ProductImage.mimeTypeOfExtension(imageKey.substringAfterLast('.', "")) ?: stored.contentType
        val image = ProductImage.of(stored.bytes, contentType)
        val extraction = productImageExtractor.extract(image)
        // bbox 있으면 크롭 이미지를, 없으면 원본을 결과 이미지로 S3 에 올린다(READY 불변식: imageUrl 필수).
        val bytes = extraction.boundingBox?.let { imageCropper.crop(image.bytes, it) } ?: image.bytes
        val imageUrl = imageStorage.upload(bytes, "items/${UUID.randomUUID()}.png", "image/png")
        return extraction.snapshot.copy(imageUrl = imageUrl)
    }
}
