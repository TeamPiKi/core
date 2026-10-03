package com.depromeet.piki.support

import com.depromeet.piki.common.storage.ImageStorage
import java.time.Duration

// 순수 변환·부수효과뿐인 동작은 이전 테스트 상태가 샐 일이 없어 고정 기본값을 둠. 읽기만 throw 기본
class StubImageStorage : ImageStorage {
    val uploadedKeys = mutableListOf<String>()
    val defaultBehavior: (ByteArray, String, String) -> String = { _, key, _ -> "$BASE_URL/$key" }
    var behavior: (ByteArray, String, String) -> String = defaultBehavior

    override fun upload(
        bytes: ByteArray,
        key: String,
        contentType: String,
    ): String {
        val url = behavior(bytes, key, contentType)
        uploadedKeys.add(key)
        return url
    }

    val deletedPrefixes = mutableListOf<String>()

    override fun deleteByPrefix(prefix: String) {
        deletedPrefixes.add(prefix)
    }

    val deletedKeys = mutableListOf<String>()

    override fun delete(key: String) {
        deletedKeys.add(key)
    }

    val presignedKeys = mutableListOf<String>()
    val presignedContentLengths = mutableListOf<Long>()
    val defaultPresignBehavior: (String, String, Long, Duration) -> String =
        { key, _, _, _ -> "$BASE_URL/$key?X-Amz-Signature=stub" }
    var presignBehavior: (String, String, Long, Duration) -> String = defaultPresignBehavior

    override fun presignUpload(
        key: String,
        contentType: String,
        contentLength: Long,
        expiry: Duration,
    ): String {
        presignedKeys.add(key)
        presignedContentLengths.add(contentLength)
        return presignBehavior(key, contentType, contentLength, expiry)
    }

    val existsCheckedKeys = mutableListOf<String>()
    val defaultExistsBehavior: (String) -> Boolean = { true }
    var existsBehavior: (String) -> Boolean = defaultExistsBehavior

    override fun exists(key: String): Boolean {
        existsCheckedKeys.add(key)
        return existsBehavior(key)
    }

    val downloadedKeys = mutableListOf<String>()
    var downloadBehavior: (String) -> ByteArray = { notStubbed("downloadBehavior") }

    override fun download(key: String): ByteArray {
        downloadedKeys.add(key)
        return downloadBehavior(key)
    }

    companion object {
        // 테스트 application.yml 의 s3.public-base-url 과 같아야 함. 공지 rehost 가 이 prefix 로 우리 S3 URL 을 판별함
        const val BASE_URL = "https://test-bucket.s3.ap-northeast-2.amazonaws.com"
    }
}
