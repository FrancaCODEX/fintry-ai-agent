package com.fintry.agent.chat;

import com.fintry.agent.integration.FintryGateway;
import com.fintry.agent.orchestration.FinancialAgent;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
public class ChatController {
  public record Message(@NotBlank @Size(max = 2000) String message) {}

  private final FinancialAgent agent;
  private final FintryGateway gateway;
  private final String integration, provider;

  public ChatController(
      FinancialAgent agent,
      FintryGateway gateway,
      @Value("${agent.integration}") String integration,
      @Value("${agent.provider}") String provider) {
    this.agent = agent;
    this.gateway = gateway;
    this.integration = integration;
    this.provider = provider;
  }

  private String cookie(HttpServletRequest request) {
    if (request.getCookies() == null) return "";
    var sessions =
        Arrays.stream(request.getCookies()).filter(c -> c.getName().equals("JSESSIONID")).toList();
    return sessions.size() == 1 ? "JSESSIONID=" + sessions.getFirst().getValue() : "";
  }

  @GetMapping("/api/csrf")
  Map<String, String> csrf(CsrfToken token) {
    return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
  }

  @GetMapping("/api/context")
  Map<String, String> context(HttpServletRequest request) {
    var user = gateway.authenticate(cookie(request));
    user.requireRead();
    return Map.of("integration", integration, "provider", provider, "companyId", user.companyId());
  }

  @PostMapping("/api/chat")
  FinancialAgent.Answer chat(@Valid @RequestBody Message message, HttpServletRequest request) {
    return agent.chat(message.message(), cookie(request));
  }

  @GetMapping("/health")
  Map<String, String> health() {
    return Map.of("status", "UP");
  }
}
