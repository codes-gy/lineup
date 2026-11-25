# 본인 확인은 대기열 세션으로 하고, 입장 토큰은 세션 주인에게 묶는다

LineUp에는 로그인이 없어서, 처음에는 `userId` 쿼리 파라미터만 알면 남의 순번과 입장 토큰을 조회할 수 있었고 토큰 URL을 넘겨받은 사람도 예매 페이지에 들어갈 수 있었다. 계정 시스템을 붙이는 대신, 대기열 등록 때 `SecureRandom` 32바이트 대기열 세션을 발급해(`X-Queue-Session` 헤더 + HttpOnly·SameSite=Strict 쿠키) 순번 조회·이탈은 세션으로만 하고, `ticket.html` 입장 검증은 토큰 주인(`token:{UUID}` → userId)과 세션 주인이 같을 때만 통과시키기로 했다. 다른 세션이 쓰는 `userId`로 등록하면 409로 막는다.

근거: 7f1a1b2, PR #9 (`QueueController`, `QueueService.registerQueue/resolveSession`, `ValidationFilter`).

## Consequences

- 입장 토큰 URL을 다른 브라우저·기기로 옮기면 세션 쿠키가 없어 403이 된다. 의도된 동작이다.
- 세션은 Redis에 TTL 2시간(조회할 때마다 연장)으로 저장되므로, 오래 자리를 비운 사용자는 다시 등록해야 한다.
- `userId`는 등록 때만 받는 이름표일 뿐이다. 이후 API는 모두 세션에서 사용자를 찾는다.
