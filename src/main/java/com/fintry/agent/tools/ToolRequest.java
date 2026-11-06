package com.fintry.agent.tools;

public record ToolRequest(Kind kind, String month, Integer days) {
  public enum Kind {
    BALANCE,
    SUMMARY,
    PENDING,
    FORECAST
  }
}
