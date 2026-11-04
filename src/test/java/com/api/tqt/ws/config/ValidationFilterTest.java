package com.api.tqt.ws.config;

import com.api.tqt.ws.metrics.QueueMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 진입 토큰 필터의 거절 사유별 카운터를 검증한다.
 */
@SuppressWarnings("unchecked")
class ValidationFilterTest {

    private ReactiveRedisTemplate<String, String> redisTemplate;
    private SimpleMeterRegistry registry;
    private ValidationFilter filter;
    private AtomicBoolean chainCalled;
    private WebFilterChain chain;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(ReactiveRedisTemplate.class);
        registry = new SimpleMeterRegistry();
        filter = new ValidationFilter(redisTemplate, new QueueMetrics(registry));
        chainCalled = new AtomicBoolean(false);
        chain = exchange -> {
            chainCalled.set(true);
            return Mono.empty();
        };
    }

    @Test
    void 토큰이_없으면_401로_보내고_missing으로_센다() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/ticket.html"));

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SEE_OTHER);
        assertThat(exchange.getResponse().getHeaders().getLocation()).hasPath("/error/401.html");
        assertThat(rejected("missing", "401")).isEqualTo(1.0);
        assertThat(rejected("invalid", "403")).isZero();
        assertThat(chainCalled).isFalse();
    }

    @Test
    void 만료되었거나_유효하지_않은_토큰이면_403으로_보내고_invalid로_센다() {
        when(redisTemplate.hasKey("token:expired")).thenReturn(Mono.just(false));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/ticket.html").queryParam("token", "expired"));

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getHeaders().getLocation()).hasPath("/error/403.html");
        assertThat(rejected("invalid", "403")).isEqualTo(1.0);
        assertThat(rejected("missing", "401")).isZero();
        assertThat(chainCalled).isFalse();
    }

    @Test
    void 유효한_토큰이면_통과시키고_거절로_세지_않는다() {
        when(redisTemplate.hasKey("token:valid")).thenReturn(Mono.just(true));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/ticket.html").queryParam("token", "valid"));

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chainCalled).isTrue();
        assertThat(rejected("missing", "401")).isZero();
        assertThat(rejected("invalid", "403")).isZero();
    }

    private double rejected(String reason, String status) {
        return registry.get(QueueMetrics.ENTRY_REJECTED)
                .tags("reason", reason, "status", status)
                .counter().count();
    }
}
