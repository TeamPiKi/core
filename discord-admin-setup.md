# Discord admin 슬래시커맨드 셋업 (#654)

백오피스 접근(`/admin-access/discord`)을 Slack → Discord 로 이관(#654)하며 필요한 **일회성 운영 셋업**. 코드 배포 후 한 번만 하면 된다.

## 사전 조건 (repo secret, 이미 설정)

| secret | 용도 |
|---|---|
| `DISCORD_PUBLIC_KEY` | 인터랙션 Ed25519 서명 검증 (앱 Public Key, Developer Portal → General Information) |
| `DISCORD_ADMIN_USER_IDS` | 접근 허용 Discord userId (콤마 구분) |
| `DISCORD_ADMIN_CHANNEL_ID` | admin 커맨드 허용 채널 id (이 채널 밖 실행은 거부) |
| `DISCORD_BOT_TOKEN` | 슬래시커맨드 등록용 (PR 봇과 공유) |

> dev/staging 은 이 셋(PUBLIC_KEY·ADMIN_USER_IDS·ADMIN_CHANNEL_ID)이 모두 있어야 배포된다(deploy.yml 브릭 가드). 하나라도 비면 배포가 fail-fast 로 멈춘다.

## 1. 슬래시커맨드 등록

봇 토큰으로 길드 커맨드를 등록한다(길드 스코프 = 즉시 반영). `APP_ID`(Application ID)·`GUILD_ID`(서버 ID)는 Developer Portal·서버에서 확인.

```bash
curl -X PUT "https://discord.com/api/v10/applications/$APP_ID/guilds/$GUILD_ID/commands" \
  -H "Authorization: Bot $DISCORD_BOT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '[{
    "name": "piki-admin",
    "description": "백오피스 접근 관리",
    "options": [
      { "type": 1, "name": "grant",  "description": "원타임 접근 링크 발급" },
      { "type": 1, "name": "list",   "description": "허용된 IP 목록" },
      { "type": 1, "name": "revoke", "description": "IP 허용 해제",
        "options": [{ "type": 3, "name": "ip", "description": "해제할 IP", "required": true }] },
      { "type": 1, "name": "allow",  "description": "IP 직접 허용",
        "options": [{ "type": 3, "name": "ip", "description": "허용할 IP", "required": true }] }
    ]
  }]'
```

(`type: 1` = 서브커맨드, `type: 3` = 문자열 옵션)

## 2. Interactions Endpoint URL 등록

Developer Portal → 앱 → **General Information → Interactions Endpoint URL** 에 배포된 엔드포인트를 넣는다:

```
https://<prod API 도메인>/admin-access/discord
```

**저장 시 Discord 가 PING 을 실제로 쏴 검증**한다 — 그래서 **엔드포인트가 먼저 배포**되고 `DISCORD_PUBLIC_KEY` 가 설정돼 있어야 저장된다(우리 코드가 PING(type 1) → PONG 을 서명검증과 함께 처리). 순서: 코드 배포 → URL 등록 → PING 통과.

## 3. 동작 확인

허용된 userId 로 **admin 채널**에서 `/piki-admin grant` → ephemeral embed 로 원타임 링크 수신 → 접속 기기에서 3분 내 클릭 → IP 등록 + 세션 → `/admin` 이동.

- 미허용 userId → "접근 불가" embed
- admin 채널 밖 실행 → "사용 불가" embed
- 서명 무효(외부 위조) → 401

## 테스트 커버리지 메모

- **Ed25519 검증 로직**은 `DiscordInteractionVerifierTest`(단위, BC 키페어로 서명·검증, replay 3분 경계 포함)로 망라한다.
- 컨트롤러 자체는 통합테스트를 두지 않는다 — admin 빈이 `@ConditionalOnAdminEnabled`(테스트 컨텍스트에선 off)라 로드되지 않고, 켜면 컨텍스트 캐시가 깨진다. Slack 시절에도 컨트롤러 통합테스트 없이 verifier 단위테스트만 뒀던 것과 같은 결이다.
