package com.api.tqt.ws;

import com.api.tqt.ws.controller.QueueController;
import com.api.tqt.ws.service.QueueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 Redis에 붙어 대기열 API 흐름 전체를 검증한다.
 * - 로컬: Redis(localhost:6379)가 떠 있어야 한다. CI: GitHub Actions의 redis 서비스 컨테이너를 쓴다.
 * - 개발용 데이터와 섞이지 않게 DB 15를 쓰고 테스트마다 비운다.
 * - 스케줄러가 테스트 도중 입장 처리를 하지 않도록 주기를 길게 잡고, 입장은 admitUsers()를 직접 호출한다.
 */
@SpringBootTest(properties = {
        "spring.data.redis.database=15",
        "lineup.queue.admit-interval-ms=3600000",
        "lineup.queue.admit-initial-delay-ms=3600000",
})
class QueueFlowIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ReactiveRedisTemplate<String, String> redisTemplate;

    @Autowired
    private QueueService queueService;

    private WebTestClient client;

    /** 응답 JSON (QueueResponse와 같은 필드) */
    record Status(String userId, Long rank, String status, String token) {
    }

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToApplicationContext(context).build();
        redisTemplate.execute(connection -> connection.serverCommands().flushDb()).blockLast(Duration.ofSeconds(5));
    }

    @Test
    void 등록하면_세션을_받고_세션_없이는_순번을_조회할_수_없다() {
        EntityExchangeResult<Status> registered = register("alice", null);
        String session = sessionOf(registered);

        assertThat(session).isNotBlank();
        assertThat(registered.getResponseHeaders().getFirst("Set-Cookie"))
                .contains(QueueController.SESSION_COOKIE + "=" + session)
                .contains("HttpOnly")
                .contains("SameSite=Strict");

        client.get().uri("/api/status").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/status?userId=alice").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/status").header(QueueController.SESSION_HEADER, "forged-session-id-0000000000")
                .exchange().expectStatus().isUnauthorized();

        Status status = status(session);
        assertThat(status.userId()).isEqualTo("alice");
        assertThat(status.status()).isEqualTo("WAITING");
        assertThat(status.rank()).isZero();
    }

    @Test
    void 남이_쓰는_userId로는_등록할_수_없어_입장_토큰을_가로챌_수_없다() {
        String aliceSession = sessionOf(register("alice", null));
        queueService.admitUsers().block();

        client.post().uri("/api/register?userId=alice").exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT);

        // 다른 사람으로 등록한 세션으로는 alice의 상태(토큰)가 보이지 않는다
        String bobSession = sessionOf(register("bob", null));
        Status bob = status(bobSession);
        assertThat(bob.userId()).isEqualTo("bob");
        assertThat(bob.token()).isNull();
        assertThat(status(aliceSession).token()).isNotNull();
    }

    @Test
    void userId_없이_등록하면_서버가_만들고_같은_세션으로_다시_등록하면_순번이_유지된다() {
        String first = sessionOf(register(null, null));
        register("bob", null);

        EntityExchangeResult<Status> again = register(null, first);

        assertThat(sessionOf(again)).isEqualTo(first);
        assertThat(again.getResponseBody().userId()).startsWith("user_");
        assertThat(again.getResponseBody().rank()).isZero(); // ZADD NX: 뒤로 밀리지 않는다
        assertThat(redisTemplate.opsForZSet().size("queue:ticket:wait").block()).isEqualTo(2L);
    }

    @Test
    void 형식이_잘못된_userId는_400() {
        client.post().uri("/api/register?userId={id}", "a b<script>").exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void 입장_허용_후_여러_번_조회해도_같은_토큰을_돌려주고_토큰에는_TTL이_있다() {
        String session = sessionOf(register("alice", null));
        queueService.admitUsers().block();

        Status first = status(session);
        Status second = status(session);

        assertThat(first.status()).isEqualTo("ALLOWED");
        assertThat(second.token()).isEqualTo(first.token());
        assertThat(redisTemplate.keys(QueueService.TOKEN_KEY_PREFIX + "*").collectList().block()).hasSize(1);
        Duration ttl = redisTemplate.getExpire("queue:ticket:allow:alice").block();
        assertThat(ttl).isPositive().isLessThanOrEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void 티켓_페이지는_토큰_주인의_세션으로만_들어갈_수_있다() {
        String aliceSession = sessionOf(register("alice", null));
        String bobSession = sessionOf(register("bob", null));
        queueService.admitUsers().block();
        String aliceToken = status(aliceSession).token();

        client.get().uri("/ticket.html?token={t}", aliceToken)
                .cookie(QueueController.SESSION_COOKIE, aliceSession)
                .exchange().expectStatus().isOk();

        client.get().uri("/ticket.html?token={t}", aliceToken)
                .cookie(QueueController.SESSION_COOKIE, bobSession)
                .exchange().expectStatus().isSeeOther()
                .expectHeader().location("/error/403.html");

        client.get().uri("/ticket.html?token={t}", aliceToken)
                .exchange().expectStatus().isSeeOther()
                .expectHeader().location("/error/403.html");
    }

    @Test
    void 이탈하면_대기열_입장_토큰_세션이_모두_사라진다() {
        String session = sessionOf(register("alice", null));
        queueService.admitUsers().block();
        String token = status(session).token();

        client.post().uri("/api/leave").header(QueueController.SESSION_HEADER, session)
                .exchange().expectStatus().isOk();

        assertThat(redisTemplate.hasKey(QueueService.TOKEN_KEY_PREFIX + token).block()).isFalse();
        assertThat(redisTemplate.hasKey("queue:ticket:allow:alice").block()).isFalse();
        client.get().uri("/api/status").header(QueueController.SESSION_HEADER, session)
                .exchange().expectStatus().isUnauthorized();
        // 이탈 후에는 같은 userId로 다시 등록할 수 있다
        register("alice", null);
    }

    @Test
    void 동시에_여러_번_입장_처리해도_한_사람에게_토큰은_하나만_발급된다() {
        List<String> sessions = IntStream.range(0, 20)
                .mapToObj(i -> sessionOf(register("user" + i, null)))
                .toList();

        Long admitted = Flux.range(0, 8)
                .flatMap(i -> queueService.admitUsers())
                .reduce(0L, Long::sum)
                .block();

        assertThat(admitted).isEqualTo(20L);
        List<String> tokens = sessions.stream().map(s -> status(s).token()).toList();
        assertThat(tokens).doesNotContainNull().doesNotHaveDuplicates();
        assertThat(redisTemplate.keys(QueueService.TOKEN_KEY_PREFIX + "*").collectList().block()).hasSize(20);
    }

    @Test
    void 테스트용_오류_엔드포인트는_노출되지_않는다() {
        client.get().uri("/api/test-error").exchange().expectStatus().isNotFound();
    }

    private EntityExchangeResult<Status> register(String userId, String session) {
        WebTestClient.RequestBodySpec request = client.post()
                .uri(userId == null ? "/api/register" : "/api/register?userId=" + userId);
        if (session != null) {
            request.header(QueueController.SESSION_HEADER, session);
        }
        return request.exchange()
                .expectStatus().isOk()
                .expectBody(Status.class)
                .returnResult();
    }

    private Status status(String session) {
        return client.get().uri("/api/status").header(QueueController.SESSION_HEADER, session)
                .exchange()
                .expectStatus().isOk()
                .expectBody(Status.class)
                .returnResult().getResponseBody();
    }

    private static String sessionOf(EntityExchangeResult<?> result) {
        return Objects.requireNonNull(result.getResponseHeaders().getFirst(QueueController.SESSION_HEADER));
    }
}
