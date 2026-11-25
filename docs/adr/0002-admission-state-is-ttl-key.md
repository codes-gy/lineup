# 입장 허용 상태는 사용자별 TTL 키로만 표현한다

처음에는 입장 허용 여부를 Redis Set(`ALLOW_KEY`) 멤버십으로 판단했는데, Set 멤버는 개별 TTL을 가질 수 없어 한 번 허용된 사용자가 영원히 `ALLOWED`로 남았고, 순번을 조회할 때마다 새 UUID 토큰을 발급했다. 이를 버리고 입장 허용 상태를 `queue:ticket:allow:{userId}` 키(값 = 입장 토큰, TTL 5분) 하나로 표현하고, 역방향 `token:{UUID}` → userId도 같은 TTL로 둔다. 토큰이 만료되면 허용 상태도 함께 사라지므로 따로 정리하는 작업이 없고, 순번 조회는 스케줄러가 발급해 둔 같은 토큰을 돌려준다.

근거: 7f1a1b2, PR #9 (`QueueService`의 `ALLOW_KEY_PREFIX` 주석, `QueueRedisScripts.ADMIT/STATUS`). ticket-rush도 같은 결정을 했다(ticket-rush ADR 0001).
