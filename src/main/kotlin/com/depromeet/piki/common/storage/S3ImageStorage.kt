package com.depromeet.piki.common.storage

import org.springframework.stereotype.Component
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.Delete
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.ObjectIdentifier
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration

@Component
class S3ImageStorage(
    private val s3Client: S3Client,
    private val s3Presigner: S3Presigner,
    private val s3Properties: S3Properties,
) : ImageStorage {
    override fun upload(
        bytes: ByteArray,
        key: String,
        contentType: String,
    ): String =
        // AWS SDK 예외(네트워크·권한·버킷 장애)를 계약 예외(502)로 변환해 호출부가 일관되게 다루게 한다.
        runCatching {
            // S3 object key 는 raw 로 저장하고, 반환 URL 만 경로 인코딩한다.
            s3Client.putObject(
                PutObjectRequest
                    .builder()
                    .bucket(s3Properties.bucket)
                    .key(key)
                    .contentType(contentType)
                    .build(),
                RequestBody.fromBytes(bytes),
            )
            "${s3Properties.publicBaseUrl.trimEnd('/')}/${encodePath(key)}"
        }.getOrElse { e -> throw ImageStorageException.uploadFailed(e) }

    override fun presignUpload(
        key: String,
        contentType: String,
        expiry: Duration,
    ): String =
        // 서명은 로컬 계산이라 네트워크 호출이 없지만, SDK 예외(자격증명 없음 등)는 계약 예외(502)로 변환한다.
        // contentType 을 putObjectRequest 에 박아 서명하면, 클라이언트는 같은 content-type 헤더로만 PUT 할 수 있다(S3 가 강제).
        runCatching {
            s3Presigner
                .presignPutObject(
                    PutObjectPresignRequest
                        .builder()
                        .signatureDuration(expiry)
                        .putObjectRequest(
                            PutObjectRequest
                                .builder()
                                .bucket(s3Properties.bucket)
                                .key(key)
                                .contentType(contentType)
                                .build(),
                        ).build(),
                ).url()
                .toString()
        }.getOrElse { e -> throw ImageStorageException.presignFailed(e) }

    override fun exists(key: String): Boolean =
        // 객체 없음(404)은 정상 결과(false)이고, 그 외 스토리지 장애(권한·네트워크·timeout)만 502 로 던진다.
        // headObject 는 없는 key 에 대해 NoSuchKeyException(=S3Exception, statusCode 404)을 던진다.
        runCatching {
            s3Client.headObject(
                HeadObjectRequest.builder().bucket(s3Properties.bucket).key(key).build(),
            )
            true
        }.getOrElse { e ->
            if ((e as? S3Exception)?.statusCode() == 404) false else throw ImageStorageException.existsCheckFailed(e)
        }

    override fun deleteByPrefix(prefix: String) {
        // AWS SDK 예외를 삭제 실패 계약 예외(502)로 변환한다. 호출부(탈퇴)가 best-effort 로 감싸지만
        // 일관성을 위해 같은 변환을 둔다 — 던지는 예외 타입이 경계 전체에서 동일해야 호출부가 한 가지로 다룬다.
        runCatching {
            // ListObjectsV2 는 한 번에 최대 1000개를 돌려준다. isTruncated 면 continuation token 으로 다음 페이지를 이어 받는다.
            var continuationToken: String? = null
            while (true) {
                val listed =
                    s3Client.listObjectsV2(
                        ListObjectsV2Request
                            .builder()
                            .bucket(s3Properties.bucket)
                            .prefix(prefix)
                            .continuationToken(continuationToken)
                            .build(),
                    )
                // 비어 있으면 DeleteObjectsRequest 가 검증 실패하므로 빈 배치는 건너뛴다 (prefix no-op).
                listed
                    .contents()
                    .map { ObjectIdentifier.builder().key(it.key()).build() }
                    .takeIf { it.isNotEmpty() }
                    ?.let { ids ->
                        s3Client.deleteObjects(
                            DeleteObjectsRequest
                                .builder()
                                .bucket(s3Properties.bucket)
                                .delete(Delete.builder().objects(ids).build())
                                .build(),
                        )
                    }
                continuationToken = listed.nextContinuationToken().takeIf { listed.isTruncated } ?: break
            }
        }.getOrElse { e -> throw ImageStorageException.deleteFailed(e) }
    }

    override fun download(key: String): StoredImage =
        // AWS SDK 예외(네트워크·권한·객체 없음)를 계약 예외(502, RETRYABLE)로 변환한다 — 워커가 일시 오류로 받아 PROCESSING 유지(recover 재시도).
        runCatching {
            val response =
                s3Client.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(s3Properties.bucket).key(key).build(),
                )
            StoredImage(bytes = response.asByteArray(), contentType = response.response().contentType())
        }.getOrElse { e -> throw ImageStorageException.downloadFailed(e) }

    override fun delete(key: String) {
        // 단건 raw 원본 회수. 객체가 없어도 S3 deleteObject 는 성공(멱등)이라 별도 존재 확인이 필요 없다.
        runCatching {
            s3Client.deleteObject(
                DeleteObjectRequest.builder().bucket(s3Properties.bucket).key(key).build(),
            )
        }.getOrElse { e -> throw ImageStorageException.deleteFailed(e) }
    }

    // key 의 각 경로 세그먼트를 URL 인코딩한다 ('/' 는 구분자로 보존). 공백/한글/예약문자 키도
    // 접근 가능한 URL 이 되도록 한다 (URLEncoder 의 '+' 는 path 에선 '%20' 이어야 한다).
    private fun encodePath(key: String): String =
        key
            .split("/")
            .joinToString("/") { URLEncoder.encode(it, StandardCharsets.UTF_8).replace("+", "%20") }
}
