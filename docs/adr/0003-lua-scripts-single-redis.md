# 대기열 상태 전이는 Lua 스크립트 하나씩으로 처리한다 (단일 Redis 전제)

등록·세션 확인·상태 조회·입장 허용·이탈을 Redis 명령 여러 개로 나눠 보내면, 그 사이에 스케줄러나 다른 요청이 끼어들어 "대기열에서는 빠졌는데 토큰은 없는" 같은 중간 상태가 생긴다. 그래서 각 전이를 `QueueRedisScripts`의 Lua 스크립트 하나(`REGISTER`, `RESOLVE_SESSION`, `STATUS`, `ADMIT`, `LEAVE`)로 묶어 원자적으로 실행한다. 입장 허용은 `ZPOPMIN`과 토큰 `SET PX`를 같은 스크립트에서 한다.

근거: 7f1a1b2, PR #9.

## Consequences

- 스크립트가 `KEYS`로 선언하지 않은 키를 `ARGV`의 접두사로 조합해 쓴다. 단일 Redis(Upstash 포함)에서만 맞고, Redis Cluster로 옮기려면 키 설계부터 바꿔야 한다.
- 키 접두사는 Java 상수(`QueueService`)와 스크립트 인자 양쪽에 걸쳐 있다. 한쪽만 바꾸면 조용히 다른 키를 쓴다.
