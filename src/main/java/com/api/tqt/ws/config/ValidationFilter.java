package com.api.tqt.ws.config;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

import java.net.URI;

@Component
@RequiredArgsConstructor
public class ValidationFilter implements WebFilter {

    private final ReactiveRedisTemplate<String, String> redisTemplate;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        // /ticket.html 페이지 진입 시에만 검증
        if (request.getURI().getPath().equals("/ticket.html")) {
            String token = request.getQueryParams().getFirst("token");

            if (token == null || token.isBlank()) {
                return redirectToErrorPage(exchange, "/error/401.html");
            }

            return redisTemplate.hasKey("token:" + token)
                    .flatMap(hasKey -> {
                        if (hasKey) {
                            return chain.filter(exchange); // 정상 진입 허용
                        } else {
                            return redirectToErrorPage(exchange, "/error/403.html");
                        }
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