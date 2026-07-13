package com.api.tqt.ws.error;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import java.net.URI;
import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler  {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> handleNotFoundException(ResponseStatusException e, ServerWebExchange exchange) {
        if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
            if (isHtmlRequest(exchange)) {
                // 브라우저 요청이면 /error/404.html로 이동
                return redirect("/error/404.html");
            }
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "요청하신 리소스를 찾을 수 없습니다."));
        }
        return handleException(e, exchange);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleException(Exception e, ServerWebExchange exchange) {
        if (isHtmlRequest(exchange)) {
            // 브라우저 요청이면 /error/500.html로 이동
            return redirect("/error/500.html");
        }

        // REST API(JSON) 요청이면 기존처럼 JSON 반환
        String message = (e.getMessage() != null) ? e.getMessage() : "서버 내부 오류가 발생했습니다.";
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", message));
    }

    private boolean isHtmlRequest(ServerWebExchange exchange) {
        List<MediaType> acceptHeaders = exchange.getRequest().getHeaders().getAccept();
        return acceptHeaders.stream()
                .anyMatch(mediaType -> mediaType.isCompatibleWith(MediaType.TEXT_HTML));
    }

    private ResponseEntity<Void> redirect(String location) {
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .location(URI.create(location))
                .build();
    }
}