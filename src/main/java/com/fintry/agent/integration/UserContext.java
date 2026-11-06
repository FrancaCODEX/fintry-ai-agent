package com.fintry.agent.integration;

import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Request-scoped verified identity. Never serialized or sent to the model. */
public record UserContext(
    String userId, String companyId, Set<String> permissions, String sessionCookie) {
  public UserContext {
    if (userId == null
        || userId.isBlank()
        || companyId == null
        || companyId.isBlank()
        || permissions == null)
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Identidade inválida.");
    permissions = Set.copyOf(permissions);
  }

  public void requireRead() {
    if (!permissions.contains("READ_FINANCE"))
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Seu perfil não permite consultar finanças.");
  }

  @Override
  public String toString() {
    return "UserContext[redacted]";
  }
}
