package com.api.tqt.ws.config;

import com.api.tqt.ws.controller.QueueController;
import com.api.tqt.ws.metrics.QueueMetrics;
import com.api.tqt.ws.service.QueueService;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.net.URI;

@Slf4j
@Component
@RequiredArgsConstructor
public class ValidationFilter implements WebFilter {

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final QueueService queueService;
    private final QueueMetrics queueMetrics;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        // /ticket.html 페이지 진입 시에만 검증
        if (request.getURI().getPath().equals("/ticket.html")) {
            String token = request.getQueryParams().getFirst("token");

            if (token == null || token.isBlank()) {
                queueMetrics.recordMissingToken();
                log.debug("[진입 검증] 토큰 없음 → 401");
                return redirectToErrorPage(exchange, "/error/401.html");
            }

            // 토큰이 가리키는 userId와, 요청한 브라우저의 대기열 세션이 가리키는 userId가 같아야 통과시킨다
            // (토큰 URL만 다른 사람에게 넘겨서는 들어갈 수 없다)
            Mono<String> tokenOwner = redisTemplate.opsForValue().get(QueueService.TOKEN_KEY_PREFIX + token);
            Mono<String> sessionOwner = queueService.resolveSession(QueueController.sessionOf(request));

            return Mono.zip(tokenOwner, sessionOwner)
                    .map(owners -> owners.getT1().equals(owners.getT2()))
                    .defaultIfEmpty(false)
                    .flatMap(valid -> {
                        if (valid) {
                            return chain.filter(exchange); // 정상 진입 허용
                        }
                        queueMetrics.recordInvalidToken();
                        log.debug("[진입 검증] 만료 · 위조되었거나 본인 세션의 토큰이 아님 → 403");
                        return redirectToErrorPage(exchange, "/error/403.html");
                    });
        }

        return chain.filter(exchange);
    }

    private Mono<Void> redirectToErrorPage(ServerWebExchange exchange, String location) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.SEE_OTHER);
        response.getHeaders().setLocation(URI.create(location));
        return response.setComplete();
    }
}