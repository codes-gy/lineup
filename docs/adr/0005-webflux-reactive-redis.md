# 논블로킹 스택(Spring WebFlux + Reactive Redis)을 쓴다

순간 트래픽을 적은 스레드로 받으려고 처음부터 WebFlux(Netty)와 Reactive Redis(Lettuce)로 만들었다. 컨트롤러·서비스·필터·지표 수집이 모두 `Mono`/`Flux` 체인이라 Spring MVC로 되돌리려면 거의 전부 다시 써야 한다.

근거: 185dd3e, README "측정 범위와 한계".

## Consequences

- 이 선택이 MVC보다 낫다는 측정 근거는 없다. README가 밝히듯 MVC와 비교 실험을 하지 않았고, 측정한 부하(초당 약 2,500건, 요청당 Redis 1~2회)는 MVC로도 처리 가능한 범위일 수 있다. 같은 대기열을 옮겨 간 ticket-rush는 Spring MVC를 쓴다.
- 리액티브 체인 안에서 블로킹 호출을 하면 이벤트 루프가 멈춘다. 그래서 스케줄러는 구독만 하고 바로 반환하고, 대기 인원 게이지는 Redis를 직접 조회하지 않고 따로 측정해 둔 값을 읽는다.
