package com.depromeet.piki.support

import com.depromeet.piki.common.storage.ImageStorage
import com.depromeet.piki.common.storage.StoredImage
import java.time.Duration

// S3 업로드를 통합 테스트에서 격리하는 stub. 기본 동작은 key 로 URL 을 만드는 순수 변환이라
// 이전 테스트 상태가 누수될 위험이 없어, ProductExtractor stub 과 달리 default 를 고정 URL 로 둔다.
// S3 실패(502) 시나리오가 필요한 테스트만 본문에서 behavior 를 throw 람다로 교체한다(직접 복원).
// 호출 검증이 필요하면 uploadedKeys 를 본다.
class StubImageStorage : ImageStorage {
    val uploadedKeys = mutableListOf<String>()

    // upload 한 raw bytes 를 key 로 보관 — download 가 그대로 돌려줘 "등록 시 올린 원본을 워커가 다시 읽는" 실제 경로를
    // 재현한다(crop 테스트처럼 실제 디코딩 가능한 바이트가 필요한 경우 포함). key 가 UUID 라 테스트 간 충돌이 없다.
    private val storedBytes = mutableMapOf<String, Pair<ByteArray, String>>()

    // deleteByPrefix 호출 기록. 삭제는 부수효과뿐이라 default 가 throw 가 아니라 단순 기록이라 안전하다
    // (upload 와 같은 결 — 명시 세팅을 강제할 동적 응답이 없다). 호출 검증은 deletedPrefixes 를 본다.
    val deletedPrefixes = mutableListOf<String>()

    // 기본 동작 — key 로 고정 URL 생성. 502 시나리오 테스트가 behavior 를 throw 람다로 교체한 뒤 이 값으로 복원한다.
    val defaultBehavior: (ByteArray, String, String) -> String = { _, key, _ -> "$BASE_URL/$key" }
    var behavior: (ByteArray, String, String) -> String = defaultBehavior

    override fun upload(
        bytes: ByteArray,
        key: String,
        contentType: String,
    ): String {
        val url = behavior(bytes, key, contentType)
        uploadedKeys.add(key)
        storedBytes[key] = bytes to contentType
        return url
    }

    override fun deleteByPrefix(prefix: String) {
        deletedPrefixes.add(prefix)
    }

    // raw 이미지 download stub. 워커가 ProductImage.of(bytes, contentType)로 재구성하므로 non-empty 바이트 + 지원 MIME 를
    // 돌려준다(바이트 내용은 StubProductImageExtractor 가 결정하므로 무관). 502 시나리오는 downloadBehavior 를 throw 람다로 교체한다.
    val downloadedKeys = mutableListOf<String>()

    // 기본 동작 — upload 한 raw bytes 를 그대로 돌려준다. content-type 누락 같은 시나리오 테스트는 downloadBehavior 를
    // 교체한 뒤 이 값으로 복원한다(upload 의 defaultBehavior 와 같은 결).
    val defaultDownloadBehavior: (String) -> StoredImage = { key ->
        storedBytes[key]?.let { StoredImage(it.first, it.second) } ?: StoredImage(byteArrayOf(1), "image/png")
    }
    var downloadBehavior: (String) -> StoredImage = defaultDownloadBehavior

    override fun download(key: String): StoredImage {
        downloadedKeys.add(key)
        return downloadBehavior(key)
    }

    // 단건 raw 회수 호출 기록. 부수효과뿐이라 default 가 단순 기록이다(upload 와 같은 결). 회수 검증은 deletedKeys 를 본다.
    val deletedKeys = mutableListOf<String>()

    override fun delete(key: String) {
        deletedKeys.add(key)
    }

    // presigned 업로드 URL 발급 stub — 클라가 서버를 거치지 않고 S3 에 직접 PUT 하는 v2 경로를 재현한다.
    // 서명 계산은 부수효과 없는 순수 변환이라 default 를 고정 URL 로 둔다(upload 와 같은 결). 발급 실패(502)
    // 시나리오만 presignBehavior 를 throw 람다로 교체한다. 발급 검증은 presignedKeys 를 본다.
    val presignedKeys = mutableListOf<String>()
    val defaultPresignBehavior: (String, String, Duration) -> String = { key, _, _ -> "$BASE_URL/$key?X-Amz-Signature=stub" }
    var presignBehavior: (String, String, Duration) -> String = defaultPresignBehavior

    override fun presignUpload(
        key: String,
        contentType: String,
        expiry: Duration,
    ): String {
        presignedKeys.add(key)
        return presignBehavior(key, contentType, expiry)
    }

    // 객체 존재 확인 stub — v2 confirm 이 "클라가 실제로 올렸는지" HEAD 로 검증하는 경로를 재현한다.
    // default 는 "올라왔다"(true)로 두고, 미업로드(400)·존재확인 실패(502) 시나리오만 existsBehavior 를 교체한다.
    // 확인 검증은 existsCheckedKeys 를 본다.
    val existsCheckedKeys = mutableListOf<String>()
    val defaultExistsBehavior: (String) -> Boolean = { true }
    var existsBehavior: (String) -> Boolean = defaultExistsBehavior

    override fun exists(key: String): Boolean {
        existsCheckedKeys.add(key)
        return existsBehavior(key)
    }

    companion object {
        // 실제 S3ImageStorage 와 같은 "{publicBaseUrl}/{key}" 형식을 흉내 내도록, 테스트 application.yml 의
        // s3.public-base-url 과 동일하게 둔다. 공지 이미지 rehost(#561)가 "이미 우리 S3 인 URL"을 publicBaseUrl
        // prefix 로 판별하므로, 이 값이 어긋나면 재저장 시 stub URL 을 외부로 오인해 다시 fetch 하려 한다.
        const val BASE_URL = "https://test-bucket.s3.ap-northeast-2.amazonaws.com"
    }
}
