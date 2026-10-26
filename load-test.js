import http from 'k6/http';
import { check, sleep } from 'k6';

const PEAK = Number(__ENV.PEAK || 300);
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080/api';

export const options = {
    // 부하 시나리오 설정
    // 최고 인원은 PEAK 환경 변수로 바꾼다(기본 300). 예: k6 run -e PEAK=3000 load-test.js
    stages: [
        { duration: '10s', target: Math.round(PEAK / 6) }, // 10초 동안 최고 인원의 1/6까지 증가
        { duration: '30s', target: PEAK },                // 30초 동안 최고 인원까지 증가 (트래픽 폭발)
        { duration: '10s', target: 0 },                   // 10초 동안 유저 줄이며 종료
    ],
    // 성공 기준 설정 (SLA)
    thresholds: {
        http_req_failed: ['rate<0.01'],   // 에러율 1% 미만 유지
        http_req_duration: ['p(95)<200'], // 95%의 요청이 200ms 이내에 응답해야 성공
    },
};

export default function () {
    // 유저마다 고유한 ID 생성 (예: user_1_1, user_2_1 ...)
    const userId = `user_${__VU}_${__ITER}`;

    // 1. 대기열 등록 (POST)
    const regRes = http.post(`${BASE_URL}/register?userId=${userId}`);
    check(regRes, {
        '등록 성공 (HTTP 200)': (r) => r.status === 200,
    });

    sleep(1); // 1초 대기

    // 2. 대기 순번 조회 (GET)
    const statusRes = http.get(`${BASE_URL}/status?userId=${userId}`);
    check(statusRes, {
        '순번 조회 성공 (HTTP 200)': (r) => r.status === 200,
    });

    sleep(1);
}