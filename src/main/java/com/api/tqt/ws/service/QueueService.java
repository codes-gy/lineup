package com.api.tqt.ws.service;

import com.api.tqt.ws.dto.QueueResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class QueueService {

    private final ReactiveRedisTemplate<String, String> redisTemplate;

    private static final String WAIT_KEY = "queue:ticket:wait";
    private static final String ALLOW_KEY = "queue:ticket:allow";

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
                    if (Boolean.TRUE.equals(isAllowed)) {
                        return Mono.just(new QueueResponse(userId, 0L, "ALLOWED"));
                    }
                    // 대기열 순번 조회 (0부터 시작)
                    return redisTemplate.opsForZSet().rank(WAIT_KEY, userId)
                            .map(rank -> new QueueResponse(userId, rank, "WAITING"))
                            .defaultIfEmpty(new QueueResponse(userId, -1L, "NOT_FOUND"));
                });
    }

    // 3. 대기열 이탈 (취소)
    public Mono<Long> leaveQueue(String userId) {
        return redisTemplate.opsForZSet().remove(WAIT_KEY, userId);
    }

    // 4. 스케줄러: 3초마다 대기열 상위 5명을 입장 허용(ALLOW_KEY로 이동)
    @Scheduled(fixedRate = 3000)
    public void allowUsers() {
        redisTemplate.opsForZSet().popMin(WAIT_KEY, 5)
                .flatMap(tuple -> {
                    String userId = tuple.getValue();
                    return redisTemplate.opsForSet().add(ALLOW_KEY, userId);
                })
                .subscribe();
    }
}