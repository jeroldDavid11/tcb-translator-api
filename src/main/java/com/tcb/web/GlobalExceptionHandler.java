package com.tcb.web;

import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException ex, ServerHttpRequest req) {
    return ResponseEntity.badRequest().body(Map.of(
        "timestamp", Instant.now().toString(),
        "status", 400,
        "error", "Bad Request",
        "message", ex.getMessage(),
        "path", req.getPath().toString()
    ));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, Object>> unknown(Exception ex, ServerHttpRequest req) {
    return ResponseEntity.status(500).body(Map.of(
        "timestamp", Instant.now().toString(),
        "status", 500,
        "error", "Internal Server Error",
        "message", ex.getMessage(),
        "path", req.getPath().toString()
    ));
  }
}
