package com.fintry.agent.chat;

import com.fintry.agent.integration.FintryGateway;
import com.fintry.agent.orchestration.ConversationContext;
import com.fintry.agent.orchestration.FinancialAgent;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ChatController {
  static final String CONTEXT = "fintry.agent.conversation";

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
    // The Agent's own session (FINTRY_AGENT_SESSION) already exists for CSRF. Context lives only
    // there, so it is per browser session and disappears when the session expires or is reset.
    var session = request.getSession(true);
    var previous =
        session.getAttribute(CONTEXT) instanceof ConversationContext context ? context : null;
    try {
      var turn = agent.chat(message.message(), cookie(request), previous);
      if (turn.next() == null) session.removeAttribute(CONTEXT);
      else session.setAttribute(CONTEXT, turn.next());
      return turn.answer();
    } catch (ResponseStatusException ex) {
      int status = ex.getStatusCode().value();
      if (status == 401 || status == 403) session.removeAttribute(CONTEXT);
      throw ex;
    }
  }

  @PostMapping("/api/conversation/reset")
  Map<String, String> reset(HttpServletRequest request) {
    var session = request.getSession(false);
    if (session != null) session.removeAttribute(CONTEXT);
    return Map.of("status", "cleared");
  }

  @GetMapping("/health")
  Map<String, String> health() {
    return Map.of("status", "UP");
  }
}
