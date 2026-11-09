package com.fintry.agent.ai;

import com.fintry.agent.tools.ToolRequest;
import java.time.LocalDate;
import java.util.List;

/**
 * A model proposes a plan. It never gets identity, credentials or financial payloads. The only
 * conversational context it may receive is the structure of the previous validated queries.
 */
public interface AiProvider {
  /**
   * What the planner decided. Java maps each value to a fixed message: text written by the model is
   * never shown to the user.
   */
  enum Decision {
    QUERY,
    CLARIFY_PERIOD,
    CLARIFY_TOPIC,
    UNSUPPORTED,
    WRITE_REQUEST,
    OTHER_COMPANY
  }

  /**
   * @param followUp the planner says it interpreted the message using the previous queries. Only
   *     informational (shown to the user); ignored when no context was provided.
   */
  record Plan(Decision decision, List<ToolRequest> queries, boolean followUp) {
    public Plan(Decision decision, List<ToolRequest> queries) {
      this(decision, queries, false);
    }

    public static Plan of(ToolRequest... queries) {
      return new Plan(Decision.QUERY, List.of(queries), false);
    }

    public static Plan followUpOf(ToolRequest... queries) {
      return new Plan(Decision.QUERY, List.of(queries), true);
    }

    public static Plan decline(Decision decision) {
      return new Plan(decision, List.of(), false);
    }
  }

  /**
   * @param previous queries executed in the previous turn of the same session, user and company;
   *     empty when there is no usable context. Contains no financial values.
   */
  Plan plan(String message, LocalDate today, List<ToolRequest> previous);
}
