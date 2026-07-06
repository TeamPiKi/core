package com.depromeet.piki.common.storage

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.time.Duration

@Configuration
class S3Config(
    private val s3Properties: S3Properties,
) {
    // 자격증명은 DefaultCredentialsProvider 체인으로 해결한다 — EC2 는 instance role,
    // 로컬은 환경변수/프로파일. 코드에 access key 를 박지 않는다.
    // 업로드가 무한정 매달리지 않도록 호출/시도 타임아웃을 둔다 (외부 호출 경계).
    @Bean
    fun s3Client(): S3Client =
        S3Client
            .builder()
            .region(Region.of(s3Properties.region))
            .overrideConfiguration(
                ClientOverrideConfiguration
                    .builder()
                    .apiCallTimeout(Duration.ofSeconds(10))
                    .apiCallAttemptTimeout(Duration.ofSeconds(5))
                    .build(),
            ).build()

    // 이미지 등록 v2 는 클라이언트가 S3 에 직접 PUT 업로드하도록 presigned URL 을 발급한다.
    // 서명은 로컬 계산이라 네트워크 호출이 없다(자격증명·region 만 필요) — s3Client 와 같은 credential 체인·region 을 쓴다.
    @Bean
    fun s3Presigner(): S3Presigner =
        S3Presigner
            .builder()
            .region(Region.of(s3Properties.region))
            .build()
}
