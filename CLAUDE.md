# lineup

Spring WebFlux + Reactive Redis로 만든 대기열 관문. 로그인 없이 대기열 세션으로 본인을 확인한다.

## 먼저 읽을 것

- `GLOSSARY.md`: 클래스·메서드·API·UI 문구·문서에 이름을 붙일 때. 대기열·입장·토큰·세션 용어는 여기 정한 말을 쓰고 `_Avoid_` 목록의 동의어는 쓰지 않는다.
- `docs/adr/`: 세션·토큰 키 구조, Lua 스크립트, 입장 스케줄러, 리액티브 스택을 바꾸려 할 때. 각 결정의 이유와 알려진 한계(단일 Redis, 단일 인스턴스)가 있다.

## 빌드 · 테스트

- `./gradlew test`는 `localhost:6379`에 Redis가 떠 있어야 한다(예: `docker run -d -p 6379:6379 redis:7-alpine`). 통합 테스트는 DB 15를 쓰고 테스트마다 `FLUSHDB`한다.
- JDK 21 툴체인, Spring Boot 4.x.
- 통합 테스트는 `lineup.queue.admit-interval-ms`를 길게 잡아 스케줄러를 멈추고 `admitUsers()`를 직접 호출해 입장시킨다. 새 테스트도 이 패턴을 따른다.

## 지켜야 할 것

- 대기열 상태를 바꾸는 Redis 작업은 `QueueRedisScripts`의 Lua 스크립트 하나로 끝낸다. 키 접두사를 바꾸면 `QueueService` 상수와 스크립트 인자를 함께 바꾼다.
- 등록 이후의 API는 `QueueController.sessionOf()`로 꺼낸 대기열 세션에서 사용자를 찾는다. `userId` 파라미터는 등록에서만 받는다.
- 리액티브 체인 안에서는 논블로킹 호출만 쓴다. 지표 게이지는 Redis를 직접 조회하지 않고 `sampleWaitingCount()`가 측정해 둔 값을 읽는다.
- 입장 주기(3초)나 배치 크기(5명)를 바꾸면 `monitoring/prometheus-alerts.yml` 임계값(분당 100명 기준)과 `index.html`의 `BATCH_SIZE`/`BATCH_INTERVAL_SEC`도 같이 고친다.

## 헷갈리기 쉬운 것

- `/api/status`의 `rank`는 0부터 시작하는 "내 앞 인원"이다. 화면의 대기 번호는 `rank + 1`. (ticket-rush는 1부터 시작하므로 코드를 옮길 때 주의)
- 입장 검증 필터는 거절할 때 401/403 상태가 아니라 `303` 리다이렉트(`/error/401.html`, `/error/403.html`)로 응답한다. 거절 수는 `lineup_entry_rejected_total`로 본다.
- API 오류가 JSON이 될지 에러 페이지 리다이렉트가 될지는 `Accept`에 `text/html`이 명시됐는지로 갈린다(`GlobalExceptionHandler.isHtmlRequest`). `*/*`는 JSON이다.
- README의 k6 수치는 대기열 세션·Lua 스크립트 도입 전에 측정한 값이다. 현재 성능의 근거로 인용하려면 다시 측정한다.

## 커밋 · 브랜치

- `feature/gy/<주제>` → `develop` PR, 릴리스는 `develop` → `main` PR. CI(`.github/workflows/ci.yml`)는 `develop`/`main` 대상 PR·push에서만 돈다.
- 커밋 메시지는 `feat:`/`fix:`/`test:`/`docs:`/`chore:` + 한국어 요약. 여러 항목은 ` · `로 잇는다.
