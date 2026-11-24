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
        if (e.getStatusCode().is4xxClientError()) {
            // 400 · 401 · 409 등 클라이언트 오류는 500으로 바꾸지 않고 그대로 돌려준다
            String reason = (e.getReason() != null) ? e.getReason() : e.getStatusCode().toString();
            return ResponseEntity.status(e.getStatusCode()).body(Map.of("error", reason));
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
        // */*(fetch · curl 기본값)까지 HTML로 보면 API 오류가 에러 페이지 리다이렉트로 바뀌므로,
        // text/html을 명시한 요청(브라우저 페이지 이동)만 HTML 요청으로 본다
        return acceptHeaders.stream()
                .anyMatch(mediaType -> !mediaType.isWildcardType() && mediaType.isCompatibleWith(MediaType.TEXT_HTML));
    }

    private ResponseEntity<Void> redirect(String location) {
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .location(URI.create(location))
                .build();
    }
}