package com.lumora.pos.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns rejections into something a till can act on.
 *
 * <p>The distinction that matters to the terminal is retry-or-not. Everything here is a 4xx: the
 * request will not succeed if sent again unchanged. Anything that might succeed on a retry must not
 * be handled here — it should surface as a 5xx so the terminal knows to try again.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(RejectedException.class)
    public ResponseEntity<Map<String, Object>> rejected(RejectedException e) {
        return ResponseEntity.unprocessableEntity().body(body(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e) {
        String detail =
                e.getBindingResult().getFieldErrors().stream()
                        .map(f -> f.getField() + " " + f.getDefaultMessage())
                        .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(body(HttpStatus.BAD_REQUEST, detail));
    }

    private Map<String, Object> body(HttpStatus status, String detail) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("detail", detail);
        return body;
    }
}
