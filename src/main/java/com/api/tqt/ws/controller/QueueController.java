package com.api.tqt.ws.controller;

import com.api.tqt.ws.dto.QueueResponse;
import com.api.tqt.ws.service.QueueService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class QueueController {

    private final QueueService queueService;

    // 대기열 등록
    @PostMapping("/register")
    public Mono<QueueResponse> register(@RequestParam String userId) {
        return queueService.registerQueue(userId);
    }

    // 순번 확인
    @GetMapping("/status")
    public Mono<QueueResponse> getStatus(@RequestParam String userId) {
        return queueService.getQueueStatus(userId);
    }

    // 대기열 이탈
    @PostMapping("/leave")
    public Mono<Long> leave(@RequestParam String userId) {
        return queueService.leaveQueue(userId);
    }

    @GetMapping("/test-error")
    public Mono<Void> testError() {
        return Mono.error(new RuntimeException("에러 핸들러 테스트"));
    }
}