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
## ▶️ 실행 방법

Redis 접속 정보는 환경변수로 넣습니다. 값이 없으면 로컬 Redis(`localhost:6379`)에 붙습니다.

| 환경변수 | 기본값 | 설명 |
| --- | --- | --- |
| `REDIS_HOST` | `localhost` | Redis 호스트 |
| `REDIS_PORT` | `6379` | Redis 포트 |
| `REDIS_PASSWORD` | (없음) | Redis 비밀번호 |
| `REDIS_SSL` | `false` | Upstash 등 TLS가 필요한 Redis는 `true` |
| `PORT` | `8080` | 서버 포트 |

```bash
# 로컬 Redis
./gradlew bootRun

# Upstash
REDIS_HOST=<host>.upstash.io REDIS_PASSWORD=<password> REDIS_SSL=true ./gradlew bootRun
```

## 3. 부하 테스트 결과 및 분석
대규모 순간 트래픽 환경에서의 시스템 처리 한계 및 안정성을 검증하기 위해 Grafana k6를 활용해 부하 테스트를 실시했습니다.

### 1) 테스트 환경 및 시나리오
- **테스트 도구**: Grafana k6 (`load-test.js`)
- **시나리오**: 최고 인원(`PEAK`)까지 가상 사용자(VUs)를 늘렸다 줄이는 50초 동안 대기열 등록(`POST /api/register`)과 순번 조회(`GET /api/status`)를 1초 간격으로 반복
    - 10초 동안 최고 인원의 1/6까지 → 30초 동안 최고 인원까지 → 10초 동안 0명
    - 최고 인원 300 · 1,000 · 3,000 · 5,000명으로 네 번 실행 (`k6 run -e PEAK=5000 load-test.js`), 실행마다 Redis를 비움
- **목표 지표 (Thresholds)**:
    - 요청 실패율 (`http_req_failed`): 1.0% 미만
    - 95% 응답 시간 (`http_req_duration p(95)`): 200ms 미만

### 2) 부하 테스트 결과 요약

| 최고 인원 | 총 요청 수 | 초당 요청 | 평균 응답 | 95% 응답 | 최대 응답 | 실패율 | 달성 여부 |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: | :---: |
| 300 VUs | 7,276건 | 147.3 | 1.32 ms | **2.43 ms** | 17.80 ms | **0.00%** | PASS |
| 1,000 VUs | 24,294건 | 479.8 | 1.04 ms | **1.47 ms** | 19.04 ms | **0.00%** | PASS |
| 3,000 VUs | 72,852건 | 1,472.3 | 1.39 ms | **2.20 ms** | 63.10 ms | **0.00%** | PASS |
| 5,000 VUs | 121,420건 | 2,454.5 | 1.61 ms | **3.26 ms** | 38.85 ms | **0.00%** | PASS |

#### 성능 평가 및 분석
* **안정성**: 가상 사용자 5,000명이 몰린 실행에서도 총 121,420건의 요청을 **실패 0건**으로 처리했습니다.
* **응답 속도**: 인원을 300명에서 5,000명으로 약 17배 늘리는 동안 95% 응답 시간은 모두 **4ms 이내**로, 목표(200ms 미만)를 크게 밑돌았습니다.
* **처리량**: 초당 요청이 인원에 비례해 늘어(5,000명에서 초당 약 2,454건), 이 범위에서는 처리 한계에 닿지 않았습니다.

<details>
<summary>
    <b>k6 로그 접기/펼치기 (최고 인원 5,000명)</b>
</summary>

```text
  █ THRESHOLDS 

    http_req_duration
    ✓ 'p(95)<200' p(95)=3.25ms

    http_req_failed
    ✓ 'rate<0.01' rate=0.00%


  █ TOTAL RESULTS 

    checks_total.......: 121420  2454.489709/s
    checks_succeeded...: 100.00% 121420 out of 121420
    checks_failed......: 0.00%   0 out of 121420

    ✓ 등록 성공 (HTTP 200)
    ✓ 순번 조회 성공 (HTTP 200)

    HTTP
    http_req_duration..............: avg=1.61ms min=32.81µs med=1.14ms max=38.85ms p(90)=2.15ms p(95)=3.25ms
      { expected_response:true }...: avg=1.61ms min=32.81µs med=1.14ms max=38.85ms p(90)=2.15ms p(95)=3.25ms
    http_req_failed................: 0.00%  0 out of 121420
    http_reqs......................: 121420 2454.489709/s

    EXECUTION
    iteration_duration.............: avg=2s     min=2s      med=2s     max=2.04s   p(90)=2s     p(95)=2s    
    iterations.....................: 60710  1227.244854/s
    vus............................: 181    min=64          max=4967
    vus_max........................: 5000   min=5000        max=5000

    NETWORK
    data_received..................: 17 MB  343 kB/s
    data_sent......................: 13 MB  270 kB/s
```
</details>