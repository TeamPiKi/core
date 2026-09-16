package com.depromeet.piki.support

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

// - scheduling.enabled=false: @Scheduled 등록 자체를 막는다(SchedulingConfig). 배경 tick 은 다른 테스트가 커밋한 전역 큐
//   행을 선점해 결정적 검증을 깨뜨린다(#1080). 폴링이 필요한 테스트는 진입점을 직접 호출한다(awaitTicking).
// - admin.discord-metrics-channel-id·admin.discord-bot-token: 공백이면 주간 리포트가 SKIPPED 로 빠져 실제 게시
//   경로(SENT)를 타지 않는다. 실제 Discord 호출은 StubDiscordMessageSender(@Primary)가 막는다.
// - admin.grant-signing-key: 공백이면 GrantTokenCodec.verify 가 항상 null 이라 grant 링크 흐름(토큰 소비 →
//   allowlist 등록 → 세션 확립)이 통째로 막힌다.
@SpringBootTest(
    properties = [
        "scheduling.enabled=false",
        "admin.enabled=true",
        "admin.local-bypass=true",
        "admin.discord-metrics-channel-id=test-metrics-channel",
        "admin.discord-bot-token=test-bot-token",
        "admin.grant-signing-key=test-grant-signing-key",
    ],
)
@Import(TestcontainersConfig::class, IntegrationStubs::class)
abstract class IntegrationTestSupport
