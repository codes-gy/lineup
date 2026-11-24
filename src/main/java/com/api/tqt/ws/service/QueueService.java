package com.api.tqt.ws.service;

import com.api.tqt.ws.dto.QueueResponse;
import com.api.tqt.ws.metrics.QueueMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueueService {

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final QueueMetrics queueMetrics;

    static final String WAIT_KEY = "queue:ticket:wait";
    // 입장 허용 상태: 사용자별 키에 입장 토큰을 값으로 담고 TTL을 건다
    // (예전 ALLOW_KEY Set은 멤버별 TTL이 없어서 한 번 허용되면 영원히 ALLOWED로 남았다)
    static final String ALLOW_KEY_PREFIX = "queue:ticket:allow:";
    // 입장 토큰 → userId (ticket.html 진입 검증에서 사용)
    public static final String TOKEN_KEY_PREFIX = "token:";
    // 대기열 세션: 세션 ID → userId, userId → 세션 ID
    public static final String SESSION_KEY_PREFIX = "queue:session:";
    static final String USER_SESSION_KEY_PREFIX = "queue:user-session:";

    static final long ADMIT_BATCH_SIZE = 5;
    static final Duration TOKEN_TTL = Duration.ofMinutes(5);
    static final Duration SESSION_TTL = Duration.ofHours(2);

    private static final Pattern USER_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern SESSION_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{16,64}");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 등록 결과. sessionId는 이후 순번 조회 · 이탈 · 티켓 페이지 진입 때 본인 확인에 쓴다. */
    public record Registration(String sessionId, QueueResponse response) {
    }

    // 1. 대기열 등록
    // - userId를 주지 않으면 서버가 만든다. 유효한 세션을 들고 오면 그 세션의 userId를 그대로 쓴다(새로고침 시 순번 유지).
    // - 다른 세션이 이미 쓰는 userId로는 등록할 수 없다(409). 남의 userId로 등록해 세션을 가로채는 것을 막는다.
    // - ZADD NX라서 이미 대기 중이면 순번이 뒤로 밀리지 않는다.
    public Mono<Registration> registerQueue(String requestedUserId, String presentedSessionId) {
        if (requestedUserId != null && !USER_ID_PATTERN.matcher(requestedUserId).matches()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "userId는 영문 · 숫자 · '_' · '-'로 된 1~64자여야 합니다."));
        }

        return resolveSession(presentedSessionId)
                .defaultIfEmpty("")
                .flatMap(sessionUserId -> {
                    String userId = requestedUserId != null ? requestedUserId
                            : !sessionUserId.isEmpty() ? sessionUserId
                            : "user_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
                    String ownedSession = userId.equals(sessionUserId) ? presentedSessionId : "";

                    List<String> keys = List.of(
                            USER_SESSION_KEY_PREFIX + userId, WAIT_KEY, ALLOW_KEY_PREFIX + userId);
                    List<String> args = List.of(
                            userId, newSessionId(), String.valueOf(SESSION_TTL.toMillis()),
                            String.valueOf(Instant.now().toEpochMilli()), ownedSession, SESSION_KEY_PREFIX);

                    return collect(redisTemplate.execute(QueueRedisScripts.REGISTER, keys, args))
                            .flatMap(reply -> {
                                if (!"OK".equals(first(reply))) {
                                    return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,
                                            "이미 다른 세션에서 사용 중인 userId입니다."));
                                }
                                String sessionId = reply.get(1);
                                return getQueueStatus(userId).map(status -> new Registration(sessionId, status));
                            });
                });
    }

    // 세션 ID → userId. 형식이 틀리거나, 만료됐거나, 이탈로 지워진 세션이면 empty
    public Mono<String> resolveSession(String sessionId) {
        if (sessionId == null || !SESSION_ID_PATTERN.matcher(sessionId).matches()) {
            return Mono.empty();
        }
        return redisTemplate.execute(QueueRedisScripts.RESOLVE_SESSION,
                        List.of(SESSION_KEY_PREFIX + sessionId),
                        List.of(String.valueOf(SESSION_TTL.toMillis()), USER_SESSION_KEY_PREFIX, sessionId))
                .next();
    }

    // 2. 대기 순번 조회 (세션으로 확인한 본인 것만)
    public Mono<QueueResponse> getQueueStatusBySession(String sessionId) {
        return resolveSession(sessionId)
                .switchIfEmpty(Mono.error(unauthorized()))
                .flatMap(this::getQueueStatus);
    }

    // 입장 허용 상태면 스케줄러가 발급해 둔 토큰을 그대로 돌려준다 (조회할 때마다 새로 만들지 않는다)
    Mono<QueueResponse> getQueueStatus(String userId) {
        return collect(redisTemplate.execute(QueueRedisScripts.STATUS,
                        List.of(ALLOW_KEY_PREFIX + userId, WAIT_KEY), List.of(userId)))
                .map(reply -> switch (first(reply)) {
                    case "ALLOWED" -> new QueueResponse(userId, 0L, "ALLOWED", reply.get(1));
                    // 대기열 순번 (0부터 시작)
                    case "WAITING" -> new QueueResponse(userId, Long.parseLong(reply.get(1)), "WAITING", null);
                    default -> new QueueResponse(userId, -1L, "NOT_FOUND", null);
                });
    }

    // 3. 대기열 이탈 (취소): 대기열 · 입장 허용 상태 · 토큰 · 세션을 모두 지운다
    public Mono<Long> leaveQueueBySession(String sessionId) {
        return resolveSession(sessionId)
                .switchIfEmpty(Mono.error(unauthorized()))
                .flatMap(userId -> redisTemplate.execute(QueueRedisScripts.LEAVE,
                                List.of(WAIT_KEY, ALLOW_KEY_PREFIX + userId,
                                        USER_SESSION_KEY_PREFIX + userId, SESSION_KEY_PREFIX + sessionId),
                                List.of(userId, TOKEN_KEY_PREFIX))
                        .next()
                        .defaultIfEmpty(0L));
    }

    // 4. 스케줄러: 3초마다 대기열 상위 5명을 입장 허용(토큰 발급)
    // 주기는 설정으로 바꿀 수 있다 (테스트에서 스케줄러가 끼어들지 않게 길게 잡는 용도)
    @Scheduled(fixedRateString = "${lineup.queue.admit-interval-ms:3000}",
            initialDelayString = "${lineup.queue.admit-initial-delay-ms:0}")
    public void allowUsers() {
        // 스케줄러 스레드를 막지 않도록 구독만 하고 바로 반환한다
        admitUsers().subscribe(
                null,
                error -> log.error("[스케줄러] 입장 처리 실패 - waitKey={}, batchSize={}",
                        WAIT_KEY, ADMIT_BATCH_SIZE, error));
    }

    // 한 틱: 상위 N명을 꺼내 토큰을 발급하는 것까지 Lua 스크립트 하나로 처리하고 입장 수를 기록한다
    // (꺼낸 뒤 저장 전에 실패해서 사용자가 사라지는 중간 상태가 없다)
    // 오류 없이 끝났을 때만 마지막 성공 시각을 갱신한다
    public Mono<Long> admitUsers() {
        List<String> args = new ArrayList<>();
        args.add(String.valueOf(TOKEN_TTL.toMillis()));
        args.add(ALLOW_KEY_PREFIX);
        args.add(TOKEN_KEY_PREFIX);
        for (int i = 0; i < ADMIT_BATCH_SIZE; i++) {
            args.add(UUID.randomUUID().toString());
        }
        return redisTemplate.execute(QueueRedisScripts.ADMIT, List.of(WAIT_KEY), args)
                .next()
                .defaultIfEmpty(0L)
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

    // 스크립트가 돌려준 배열 응답을 문자열 목록으로 모은다
    // (드라이버에 따라 배열 하나로 오거나 원소별로 나눠 오므로 둘 다 처리한다)
    private static Mono<List<String>> collect(Flux<?> reply) {
        return reply.flatMapIterable(element -> element instanceof List<?> list
                        ? list.stream().map(String::valueOf).toList()
                        : Stream.of(element).map(String::valueOf).toList())
                .collectList();
    }

    private static String first(List<String> reply) {
        return reply.isEmpty() ? "" : reply.get(0);
    }

    private static String newSessionId() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "대기열 세션이 없거나 만료되었습니다. 다시 등록해 주세요.");
    }
}
