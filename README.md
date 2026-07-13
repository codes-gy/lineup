# 🎟️ 트래픽 대기열 티켓 시스템

> **Spring WebFlux**와 **Reactive Redis**를 기반으로 구축된 고성능·비동기 분산 대기열 및 진입 검증 시스템입니다.

---

## 📌 1. 프로젝트 개요

티켓 오픈이나 한정판 상품 판매 시 발생하는 **순간적인 대규모 동시 요청(Flash Traffic)** 환경에서 백엔드 핵심 서비스(예매/결제 데이터베이스)의 과부하 및 다운을 방지하기 위해 설계되었습니다.

클라이언트의 요청을 핵심 데이터베이스로 직접 전달하지 않고 **Redis In-Memory Data Store** 기반의 버퍼 대기열로 수용하며, 대기 순번에 도달한 사용자에게만 암호화된 **진입 토큰(UUID Token)**을 발급하여 안정적인 서비스 이용을 보장합니다.

### 🎯 핵심 목표
- **Non-blocking I/O 아키텍처**를 통한 최소한의 서버 자원(스레드)으로 고밀도 동시 접속 수용
- **Redis Sorted Set(ZSET)**을 이용한 정확하고 신속한 순번 추적 및 대기 시간 계산
- **어뷰징 및 우회 접근 차단**: 유효한 진입 토큰 미소지자의 핵심 페이지 진입 엄격 통제
- **사용자 친화적 예외 처리**: 시스템 상태 및 응답 코드에 맞춘 전용 에러 화면으로 사용자 이탈 방지

---

## 🛠️ 기술 스택

### 백엔드 및 핵심 기술
- **자바**: Java 21
- **프레임워크**: Spring Boot 4.1.0, Spring WebFlux
- **리액티브 엔진**: Netty
- **빌드 도구**: Gradle

### 데이터 저장소
- **데이터베이스**: Redis

### 성능 검증 및 테스트
- **부하 테스트 도구**: Grafana k6

---

## 시스템 아키텍처 및 데이터 흐름

```text
                                        [ 클라이언트 ]
                                              │
      ┌───────────────────────────────────────┼───────────────────────────────────────┐
      │                                       │                                       │
      ▼ (1. 대기열 등록)                       ▼ (2. 순번 주기적 조회)                    ▼ (3. 티켓 페이지 진입)
POST /api/register                      GET /api/status                        GET /ticket.html?token=...
      │                                       │                                       │
      ▼                                       ▼                                       ▼
┌──────────────────┐                    ┌──────────────────┐                    ┌──────────────────┐
│ Queue Controller │                    │ Queue Controller │                    │ ValidationFilter │
└─────────┬────────┘                    └─────────┬────────┘                    └─────────┬────────┘
          │                                       │                                       │
          ▼                                       ▼                                       │
┌──────────────────┐                    ┌──────────────────┐                              │
│  Queue Service   │                    │  Queue Service   │                              │
└─────────┬────────┘                    └─────────┬────────┘                              │
          │                                       │                                       │
          ▼                                       ▼                                       ▼
┌──────────────────────────────────────────────────────────┐                    ┌──────────────────┐
│                   Redis Data Structure                   │                    │ Redis Key Check  │
│  - Active Queue (ZSET) : [ User ID / Timestamp ]         │ ◄──(Token Issue)───┤ "token:{UUID}"   │
│  - Pass Token (String) : "token:{UUID}" (TTL: 5m)        │                    └─────────┬────────┘
└──────────────────────────────────────────────────────────┘                              │
                                                                                          ├─ Valid   ─► [ 200 OK : ticket.html ]
                                                                                          └─ Invalid ─► [ 303 Redirect : /error/401.html or /error/403.html ]
```
## 3. 부하 테스트 결과 및 분석
대규모 순간 트래픽 환경에서의 시스템 처리 한계 및 안정성을 검증하기 위해 Grafana k6를 활용해 부하 테스트를 실시했습니다.

### 1) 테스트 환경 및 시나리오
- **테스트 도구**: Grafana k6
- **시나리오**: 최대 300명의 가상 사용자(VUs)가 50초 동안 대기열 등록(`POST`) 및 순번 조회(`GET`)를 지속적으로 반복 수행
- **목표 지표 (Thresholds)**:
    - 요청 실패율 (`http_req_failed`): 1.0% 미만
    - 95% 응답 시간 (`http_req_duration p(95)`): 200ms 미만

### 2) 부하 테스트 결과 요약

| 구분                             | 목표치  | 측정 결과  | 달성 여부 |
|:-------------------------------| :--- | :--- | :---: |
| **총 요청 수**                     | - | 6,696건 (초당 ~131.6 RPS) | - |
| **요청 실패율**                     | 1.0% 미만 | **0.00%** (0 / 6,696) | PASS |
| **평균 응답 시간**                   | - | **91.84 ms** | - |
| **95% 응답 시간** | 200 ms 미만 | **113.37 ms** | PASS |
| **최대 응답 시간**    | - | 126.22 ms | - |
| **최대 가상 사용자**        | - | 300 VUs | - |

#### 성능 평가 및 분석
* **안정성**: 총 6,696건의 요청 동안 **실패율 0.00%**를 기록하며 동시 접속 요청을 안정적으로 처리했습니다.
* **응답 속도**: 95%의 요청이 **113.37ms** 내에 완료되어 목표 지표(200ms 미만)를 여유 있게 충족했습니다.


<details>
<summary>
    <b>k6 로그 접기/펼치기</b>
</summary>

```text

          /\      Grafana   /‾‾/
         /  \    /  \     |\  __   /  /
        /    \  /    \    | |/ /  /   ‾‾\
       /      \/      \   |   (  |  (‾)  |
      / __________ \  |_|\_\  \_____/

     execution: local
        script: load-test.js
        output: -

     scenarios: (100.00%) 1 scenario, 300 max VUs, 1m20s max duration (incl. graceful stop):
              * default: Up to 300 looping VUs for 50s over 3 stages (gracefulRampDown: 30s, gracefulStop: 30s)

█ THRESHOLDS

    http_req_duration
    ✓ 'p(95)<200' p(95)=113.37ms

    http_req_failed
    ✓ 'rate<0.01' rate=0.00%

█ TOTAL RESULTS

    checks_total.......: 6696    131.587138/s
    checks_succeeded...: 100.00% 6696 out of 6696
    checks_failed......: 0.00%   0 out of 6696

    ✓ 등록 성공 (HTTP 200)
    ✓ 순번 조회 성공 (HTTP 200)

    HTTP
    http_req_duration..............: avg=91.84ms min=51.77µs med=77.88ms max=126.22ms p(90)=112.29ms p(95)=113.37ms
      { expected_response:true }...: avg=91.84ms min=51.77µs med=77.88ms max=126.22ms p(90)=112.29ms p(95)=113.37ms
    http_req_failed................: 0.00%  0 out of 6696
    http_reqs......................: 6696   131.587138/s

    EXECUTION
    iteration_duration.............: avg=2.18s   min=2.15s   med=2.18s   max=2.2s     p(90)=2.19s    p(95)=2.19s   
    iterations.....................: 3348   65.793569/s
    vus............................: 10     min=5       max=299
    vus_max........................: 300    min=300     max=300

    NETWORK
    data_received..................: 833 kB 16 kB/s
    data_sent......................: 788 kB 16 kB/s

running (0m50.9s), 000/300 VUs, 3348 complete and 0 interrupted iterations
```

</details>