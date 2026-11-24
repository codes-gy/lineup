package com.api.tqt.ws.config;

import com.api.tqt.ws.controller.QueueController;
import com.api.tqt.ws.metrics.QueueMetrics;
import com.api.tqt.ws.service.QueueService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 진입 토큰 필터: 토큰이 요청한 사람(대기열 세션)의 것인지와 거절 사유별 카운터를 검증한다.
 */
@SuppressWarnings("unchecked")
class ValidationFilterTest {

    private ReactiveValueOperations<String, String> valueOps;
    private QueueService queueService;
    private SimpleMeterRegistry registry;
    private ValidationFilter filter;
    private AtomicBoolean chainCalled;
    private WebFilterChain chain;

    @BeforeEach
    void setUp() {
        ReactiveRedisTemplate<String, String> redisTemplate = mock(ReactiveRedisTemplate.class);
        valueOps = mock(ReactiveValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(any())).thenReturn(Mono.empty());

        queueService = mock(QueueService.class);
        when(queueService.resolveSession(any())).thenReturn(Mono.empty());
        when(queueService.resolveSession("session-of-alice")).thenReturn(Mono.just("alice"));
        when(queueService.resolveSession("session-of-bob")).thenReturn(Mono.just("bob"));

        registry = new SimpleMeterRegistry();
        filter = new ValidationFilter(redisTemplate, queueService, new QueueMetrics(registry));
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
        MockServerWebExchange exchange = ticketRequest("expired", "session-of-alice");

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getHeaders().getLocation()).hasPath("/error/403.html");
        assertThat(rejected("invalid", "403")).isEqualTo(1.0);
        assertThat(rejected("missing", "401")).isZero();
        assertThat(chainCalled).isFalse();
    }

    @Test
    void 본인_세션의_토큰이면_통과시키고_거절로_세지_않는다() {
        when(valueOps.get(QueueService.TOKEN_KEY_PREFIX + "alice-token")).thenReturn(Mono.just("alice"));
        MockServerWebExchange exchange = ticketRequest("alice-token", "session-of-alice");

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chainCalled).isTrue();
        assertThat(rejected("missing", "401")).isZero();
        assertThat(rejected("invalid", "403")).isZero();
    }

    @Test
    void 다른_사람의_토큰을_가져오면_403으로_막는다() {
        when(valueOps.get(QueueService.TOKEN_KEY_PREFIX + "alice-token")).thenReturn(Mono.just("alice"));
        MockServerWebExchange exchange = ticketRequest("alice-token", "session-of-bob");

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getHeaders().getLocation()).hasPath("/error/403.html");
        assertThat(rejected("invalid", "403")).isEqualTo(1.0);
        assertThat(chainCalled).isFalse();
    }

    @Test
    void 세션_없이_토큰만_들고_오면_403으로_막는다() {
        when(valueOps.get(QueueService.TOKEN_KEY_PREFIX + "alice-token")).thenReturn(Mono.just("alice"));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/ticket.html").queryParam("token", "alice-token"));

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getHeaders().getLocation()).hasPath("/error/403.html");
        assertThat(chainCalled).isFalse();
    }

    @Test
    void 다른_경로는_검증하지_않는다() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/index.html"));

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chainCalled).isTrue();
    }

    private MockServerWebExchange ticketRequest(String token, String session) {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/ticket.html")
                .queryParam("token", token)
                .cookie(new HttpCookie(QueueController.SESSION_COOKIE, session)));
    }

    private double rejected(String reason, String status) {
        return registry.get(QueueMetrics.ENTRY_REJECTED)
                .tags("reason", reason, "status", status)
                .counter().count();
    }
}
