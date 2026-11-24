package com.api.tqt.ws.controller;

import com.api.tqt.ws.dto.QueueResponse;
import com.api.tqt.ws.service.QueueService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpCookie;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

/**
 * 대기열 API.
 * 등록할 때 서버가 추측할 수 없는 대기열 세션 ID를 발급하고, 순번 조회 · 이탈은 이 세션으로만 할 수 있다.
 * (예전에는 userId 쿼리 파라미터만 알면 남의 순번과 입장 토큰을 조회할 수 있었다)
 * 세션은 브라우저용 HttpOnly 쿠키와, API 클라이언트(k6 등)용 X-Queue-Session 헤더로 함께 내려준다.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class QueueController {

    public static final String SESSION_COOKIE = "LINEUP_QUEUE_SESSION";
    public static final String SESSION_HEADER = "X-Queue-Session";

    private final QueueService queueService;

    // 대기열 등록 (userId는 선택. 없으면 서버가 만든다)
    @PostMapping("/register")
    public Mono<ResponseEntity<QueueResponse>> register(@RequestParam(required = false) String userId,
                                                        ServerHttpRequest request) {
        return queueService.registerQueue(userId, sessionOf(request))
                .map(registration -> ResponseEntity.ok()
                        .header(SESSION_HEADER, registration.sessionId())
                        .header("Set-Cookie", sessionCookie(registration.sessionId(), request).toString())
                        .body(registration.response()));
    }

    // 순번 확인 (본인 세션으로만)
    @GetMapping("/status")
    public Mono<QueueResponse> getStatus(ServerHttpRequest request) {
        return queueService.getQueueStatusBySession(sessionOf(request));
    }

    // 대기열 이탈 (본인 세션으로만)
    @PostMapping("/leave")
    public Mono<ResponseEntity<Long>> leave(ServerHttpRequest request) {
        return queueService.leaveQueueBySession(sessionOf(request))
                .map(removed -> ResponseEntity.ok()
                        .header("Set-Cookie", expiredCookie(request).toString())
                        .body(removed));
    }

    /** 헤더를 먼저 보고, 없으면 쿠키를 본다. */
    public static String sessionOf(ServerHttpRequest request) {
        String header = request.getHeaders().getFirst(SESSION_HEADER);
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        HttpCookie cookie = request.getCookies().getFirst(SESSION_COOKIE);
        return cookie != null ? cookie.getValue() : null;
    }

    private static ResponseCookie sessionCookie(String sessionId, ServerHttpRequest request) {
        return baseCookie(sessionId, request).build();
    }

    private static ResponseCookie expiredCookie(ServerHttpRequest request) {
        return baseCookie("", request).maxAge(0).build();
    }

    private static ResponseCookie.ResponseCookieBuilder baseCookie(String value, ServerHttpRequest request) {
        return ResponseCookie.from(SESSION_COOKIE, value)
                .httpOnly(true)
                .secure("https".equalsIgnoreCase(request.getURI().getScheme()))
                .sameSite("Strict")
                .path("/");
    }
}
