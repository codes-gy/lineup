package com.api.tqt.ws.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 대기열 운영 지표 모음.
 * Redis 접근이 리액티브라서 게이지 콜백 안에서 Redis를 조회(블로킹)하지 않는다.
 * 주기 작업(QueueService)이 값을 측정해 AtomicLong에 넣고, 게이지는 그 값만 읽는다.
 */
@Component
public class QueueMetrics {

    public static final String WAITING = "lineup.queue.waiting";
    public static final String ADMITTED = "lineup.queue.admitted";
    public static final String SCHEDULER_LAST_SUCCESS = "lineup.scheduler.last.success.timestamp";
    public static final String ENTRY_REJECTED = "lineup.entry.rejected";

    public static final String REASON_MISSING = "missing";
    public static final String REASON_INVALID = "invalid";

    private final AtomicLong waitingCount = new AtomicLong();
    private final AtomicLong lastSuccessEpochSeconds = new AtomicLong();
    private final Counter admittedCounter;
    private final Counter missingTokenCounter;
    private final Counter invalidTokenCounter;
    private final Clock clock;

    @Autowired
    public QueueMetrics(MeterRegistry registry) {
        this(registry, Clock.systemUTC());
    }

    QueueMetrics(MeterRegistry registry, Clock clock) {
        this.clock = clock;

        Gauge.builder(WAITING, waitingCount, AtomicLong::get)
                .description("대기열(ZSET) 대기 인원 - 5초마다 ZCARD로 측정")
                .register(registry);

        Gauge.builder(SCHEDULER_LAST_SUCCESS, lastSuccessEpochSeconds, AtomicLong::get)
                .description("입장 스케줄러가 마지막으로 오류 없이 끝난 시각(epoch 초)")
                .baseUnit("seconds")
                .register(registry);

        this.admittedCounter = Counter.builder(ADMITTED)
                .description("입장 허용된 사용자 수")
                .register(registry);

        // 토큰 없음 → 401, 만료/위조 토큰 → 403
        // (Redis 키가 TTL로 사라지면 위조 토큰과 구분이 안 되므로 둘 다 invalid로 센다)
        this.missingTokenCounter = rejectionCounter(registry, REASON_MISSING, "401");
        this.invalidTokenCounter = rejectionCounter(registry, REASON_INVALID, "403");
    }

    private static Counter rejectionCounter(MeterRegistry registry, String reason, String status) {
        return Counter.builder(ENTRY_REJECTED)
                .description("진입 토큰 검증 필터에서 거절된 요청 수")
                .tag("reason", reason)
                .tag("status", status)
                .register(registry);
    }

    public void updateWaiting(long count) {
        waitingCount.set(count);
    }

    public void recordAdmitted(long count) {
        if (count > 0) {
            admittedCounter.increment(count);
        }
    }

    public void markSchedulerSuccess() {
        lastSuccessEpochSeconds.set(clock.instant().getEpochSecond());
    }

    public void recordMissingToken() {
        missingTokenCounter.increment();
    }

    public void recordInvalidToken() {
        invalidTokenCounter.increment();
    }
}
