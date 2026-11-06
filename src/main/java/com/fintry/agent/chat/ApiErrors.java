package com.fintry.agent.chat;

import java.util.Map;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiErrors {
  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<Map<String, String>> status(ResponseStatusException ex) {
    return ResponseEntity.status(ex.getStatusCode())
        .body(
            Map.of("message", ex.getReason() == null ? "Consulta indisponível." : ex.getReason()));
  }

  @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
  ResponseEntity<Map<String, String>> badRequest(Exception ignored) {
    return ResponseEntity.badRequest()
        .body(Map.of("message", "Envie somente o campo message, com 1 a 2000 caracteres."));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Map<String, String>> unexpected(Exception ignored) {
    return ResponseEntity.internalServerError()
        .body(Map.of("message", "Não foi possível concluir a solicitação."));
  }
}
