package com.api.tqt.ws.service;

import com.api.tqt.ws.metrics.QueueMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveSetOperations;
import org.springframework.data.redis.core.ReactiveZSetOperations;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.data.redis.RedisConnectionFailureException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static com.api.tqt.ws.service.QueueService.ADMIT_BATCH_SIZE;
import static com.api.tqt.ws.service.QueueService.ALLOW_KEY;
import static com.api.tqt.ws.service.QueueService.WAIT_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 입장 스케줄러(admitUsers)와 대기 인원 측정이 지표를 올바르게 남기는지 Redis 없이 검증한다.
 */
@SuppressWarnings("unchecked")
class QueueServiceMetricsTest {

    private ReactiveZSetOperations<String, String> zSetOps;
    private ReactiveSetOperations<String, String> setOps;
    private SimpleMeterRegistry registry;
    private QueueService queueService;

    @BeforeEach
    void setUp() {
        ReactiveRedisTemplate<String, String> redisTemplate = mock(ReactiveRedisTemplate.class);
        zSetOps = mock(ReactiveZSetOperations.class);
        setOps = mock(ReactiveSetOperations.class);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.add(eq(ALLOW_KEY), any())).thenReturn(Mono.just(1L));

        registry = new SimpleMeterRegistry();
        queueService = new QueueService(redisTemplate, new QueueMetrics(registry));
    }

    @Test
    void 틱이_성공하면_입장_수와_마지막_성공_시각이_기록된다() {
        when(zSetOps.popMin(WAIT_KEY, ADMIT_BATCH_SIZE)).thenReturn(Flux.just(
                TypedTuple.of("user-1", 1.0),
                TypedTuple.of("user-2", 2.0),
                TypedTuple.of("user-3", 3.0)));

        StepVerifier.create(queueService.admitUsers())
                .expectNext(3L)
                .verifyComplete();

        assertThat(counter(QueueMetrics.ADMITTED)).isEqualTo(3.0);
        assertThat(gauge(QueueMetrics.SCHEDULER_LAST_SUCCESS)).isPositive();
    }

    @Test
    void 대기열이_비어도_성공한_틱이면_마지막_성공_시각은_갱신된다() {
        when(zSetOps.popMin(WAIT_KEY, ADMIT_BATCH_SIZE)).thenReturn(Flux.empty());

        StepVerifier.create(queueService.admitUsers())
                .expectNext(0L)
                .verifyComplete();

        assertThat(counter(QueueMetrics.ADMITTED)).isZero();
        assertThat(gauge(QueueMetrics.SCHEDULER_LAST_SUCCESS)).isPositive();
    }

    @Test
    void Redis_오류로_틱이_실패하면_마지막_성공_시각은_갱신되지_않는다() {
        when(zSetOps.popMin(WAIT_KEY, ADMIT_BATCH_SIZE))
                .thenReturn(Flux.error(new RedisConnectionFailureException("연결 실패")));

        StepVerifier.create(queueService.admitUsers())
                .verifyError(RedisConnectionFailureException.class);

        assertThat(counter(QueueMetrics.ADMITTED)).isZero();
        assertThat(gauge(QueueMetrics.SCHEDULER_LAST_SUCCESS)).isZero();
    }

    @Test
    void 입장_허용_저장이_실패하면_마지막_성공_시각은_갱신되지_않는다() {
        when(zSetOps.popMin(WAIT_KEY, ADMIT_BATCH_SIZE)).thenReturn(Flux.just(TypedTuple.of("user-1", 1.0)));
        when(setOps.add(eq(ALLOW_KEY), any())).thenReturn(Mono.error(new RedisConnectionFailureException("연결 실패")));

        StepVerifier.create(queueService.admitUsers())
                .verifyError(RedisConnectionFailureException.class);

        assertThat(counter(QueueMetrics.ADMITTED)).isZero();
        assertThat(gauge(QueueMetrics.SCHEDULER_LAST_SUCCESS)).isZero();
    }

    @Test
    void 대기_인원은_ZCARD_값으로_게이지에_반영된다() {
        when(zSetOps.size(WAIT_KEY)).thenReturn(Mono.just(17L));

        StepVerifier.create(queueService.measureWaitingCount())
                .expectNext(17L)
                .verifyComplete();

        assertThat(gauge(QueueMetrics.WAITING)).isEqualTo(17.0);
    }

    @Test
    void 대기_인원_측정이_실패하면_게이지는_이전_값을_유지한다() {
        when(zSetOps.size(WAIT_KEY)).thenReturn(Mono.just(4L), Mono.error(new RedisConnectionFailureException("연결 실패")));

        queueService.sampleWaitingCount();
        queueService.sampleWaitingCount();

        assertThat(gauge(QueueMetrics.WAITING)).isEqualTo(4.0);
    }

    @Test
    void allowUsers는_오류가_나도_예외를_던지지_않는다() {
        when(zSetOps.popMin(WAIT_KEY, ADMIT_BATCH_SIZE))
                .thenReturn(Flux.error(new RedisConnectionFailureException("연결 실패")));

        queueService.allowUsers();

        assertThat(gauge(QueueMetrics.SCHEDULER_LAST_SUCCESS)).isZero();
    }

    private double counter(String name) {
        return registry.get(name).counter().count();
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }
}
