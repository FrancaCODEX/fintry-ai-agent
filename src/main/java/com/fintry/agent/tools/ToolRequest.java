package com.fintry.agent.tools;

import java.io.Serializable;

/**
 * One read-only query proposed by the planner. Every field is untrusted until {@link
 * FinancialTools#validate} accepts it. There is deliberately no user, company, URL or SQL field.
 */
public record ToolRequest(Kind kind, String month, Integer days, DueWindow window)
    implements Serializable {
  public enum Kind {
    BALANCE,
    SUMMARY,
    COMPARE,
    PENDING,
    FORECAST,
    CONCEPTS
  }

  /** Windows that map 1:1 to existing Fintry pending groups. */
  public enum DueWindow {
    OVERDUE,
    TODAY,
    NEXT7
  }

  public static ToolRequest of(Kind kind) {
    return new ToolRequest(kind, null, null, null);
  }

  public static ToolRequest summary(String month) {
    return new ToolRequest(Kind.SUMMARY, month, null, null);
  }

  public static ToolRequest compare(String month) {
    return new ToolRequest(Kind.COMPARE, month, null, null);
  }

  public static ToolRequest pending(DueWindow window) {
    return new ToolRequest(Kind.PENDING, null, null, window);
  }

  public static ToolRequest forecast(int days) {
    return new ToolRequest(Kind.FORECAST, null, days, null);
  }
}
