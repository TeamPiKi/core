# core

같이 고르는 쇼핑 토너먼트 서비스 **PiKi** 의 백엔드 API 서버. 위시리스트·토너먼트·사용자를 소유하고, 상품 추출 파이프라인을 오케스트레이션한다.

## 시스템 구성

| repo | 역할 |
|---|---|
| [client](https://github.com/TeamPiKi/client) | 앱 클라이언트 |
| **core** (이 repo) | 백엔드 API 서버 |
| [extractor](https://github.com/TeamPiKi/extractor) | 상품 추출 서비스. URL 을 fetch·구조화 파싱하고 LLM 으로 보완한다 |
| renderer (private) | 페이지 렌더링 내부 서비스 |
| [infra](https://github.com/TeamPiKi/infra) | 여러 repo 에 걸치는 공통 자산의 SSOT (배포 블록·개발 규약) |

호출 흐름은 client → core → extractor → renderer.

## 버전

![version](https://img.shields.io/github/v/release/TeamPiKi/core?label=version&color=blue)

최신 변경사항은 [릴리즈 노트](https://github.com/TeamPiKi/core/releases/latest)에서 확인한다. `Promote` 워크플로는 `dev` 커밋을 `main`(prod)으로 fast-forward 승격하며, 선택한 단위(patch/minor/major)의 semver 태그와 릴리즈는 배포 성공 후 자동 생성된다.
