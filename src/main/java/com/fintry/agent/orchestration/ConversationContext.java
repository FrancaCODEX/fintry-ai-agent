package com.fintry.agent.orchestration;

import com.fintry.agent.integration.UserContext;
import com.fintry.agent.tools.ToolRequest;
import java.io.Serializable;
import java.time.*;
import java.util.List;

/**
 * Short-lived context of the previous turn, stored only in the caller's own HTTP session. It keeps
 * the <em>structure</em> of the last validated queries (kind and period), never financial values,
 * message text or the Fintry cookie. It is bound to the user and company that produced it.
 */
public record ConversationContext(
    String userId, String companyId, List<ToolRequest> queries, Instant createdAt)
    implements Serializable {
  public static final Duration TTL = Duration.ofMinutes(10);
  public static final int MAX_QUERIES = 3;

  public ConversationContext {
    queries = List.copyOf(queries.subList(0, Math.min(queries.size(), MAX_QUERIES)));
  }

  public boolean usableBy(UserContext user, Instant now) {
    return userId.equals(user.userId())
        && companyId.equals(user.companyId())
        && !createdAt.isAfter(now)
        && Duration.between(createdAt, now).compareTo(TTL) <= 0;
  }

  @Override
  public String toString() {
    return "ConversationContext[queries=" + queries.size() + "]";
  }
}
