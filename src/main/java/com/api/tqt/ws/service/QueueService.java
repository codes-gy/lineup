package com.api.tqt.ws.service;

import com.api.tqt.ws.dto.QueueResponse;
import com.api.tqt.ws.metrics.QueueMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueueService {

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final QueueMetrics queueMetrics;

    static final String WAIT_KEY = "queue:ticket:wait";
    static final String ALLOW_KEY = "queue:ticket:allow";
    static final long ADMIT_BATCH_SIZE = 5;

    // 1. 대기열 등록
    public Mono<QueueResponse> registerQueue(String userId) {
        long timestamp = Instant.now().toEpochMilli();
        return redisTemplate.opsForZSet()
                .add(WAIT_KEY, userId, timestamp)
                .flatMap(success -> getQueueStatus(userId));
    }

    // 2. 대기 순번 조회
    public Mono<QueueResponse> getQueueStatus(String userId) {
        // 이미 승인(입장)된 유저인지 확인
        return redisTemplate.opsForSet().isMember(ALLOW_KEY, userId)
                .flatMap(isAllowed -> {
                    if (isAllowed) {
                        return generateToken(userId)
                                .map(token -> new QueueResponse(userId, 0L, "ALLOWED", token));
                    }
                    // 대기열 순번 조회 (0부터 시작)
                    return redisTemplate.opsForZSet().rank(WAIT_KEY, userId)
                            .map(rank -> new QueueResponse(userId, rank, "WAITING", null))
                            .defaultIfEmpty(new QueueResponse(userId, -1L, "NOT_FOUND", null));
                });
    }

    // 3. 대기열 이탈 (취소)
    public Mono<Long> leaveQueue(String userId) {
        return redisTemplate.opsForZSet().remove(WAIT_KEY, userId);
    }

    // 4. 스케줄러: 3초마다 대기열 상위 5명을 입장 허용(ALLOW_KEY로 이동)
    @Scheduled(fixedRate = 3000)
    public void allowUsers() {
        // 스케줄러 스레드를 막지 않도록 구독만 하고 바로 반환한다
        admitUsers().subscribe(
                null,
                error -> log.error("[스케줄러] 입장 처리 실패 - waitKey={}, allowKey={}, batchSize={}",
                        WAIT_KEY, ALLOW_KEY, ADMIT_BATCH_SIZE, error));
    }

    // 한 틱: 상위 N명을 입장 허용하고 입장 수를 기록한다
    // 오류 없이 끝났을 때만 마지막 성공 시각을 갱신한다
    Mono<Long> admitUsers() {
        return redisTemplate.opsForZSet().popMin(WAIT_KEY, ADMIT_BATCH_SIZE)
                .flatMap(tuple -> {
                    String userId = tuple.getValue();
                    return redisTemplate.opsForSet().add(ALLOW_KEY, userId);
                })
                .count()
                .doOnNext(admitted -> {
                    queueMetrics.recordAdmitted(admitted);
                    queueMetrics.markSchedulerSuccess();
                    log.debug("[스케줄러] 입장 허용 {}명", admitted);
                });
    }

    // 5. 대기 인원 측정: 5초마다 ZCARD 값을 게이지에 반영
    // 게이지 콜백에서 Redis를 조회(블로킹)하지 않으려고 따로 측정해 둔다
    // 입장 스케줄러와 분리해서, 입장 처리가 멈춰도 대기 인원 변화는 계속 보이게 한다
    @Scheduled(fixedRate = 5000)
    public void sampleWaitingCount() {
        measureWaitingCount().subscribe(
                null,
                error -> log.warn("[지표] 대기 인원 측정 실패 - waitKey={}", WAIT_KEY, error));
    }

    Mono<Long> measureWaitingCount() {
        return redisTemplate.opsForZSet().size(WAIT_KEY)
                .doOnNext(queueMetrics::updateWaiting);
    }

    // 승인 처리 시 토큰 생성 및 저장
    public Mono<String> generateToken(String userId) {
        String token = UUID.randomUUID().toString();
        return redisTemplate.opsForValue()
                .set("token:" + token, userId, Duration.ofMinutes(5))
                .thenReturn(token);
    }
}