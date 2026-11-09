package com.fintry.agent.integration;

import com.fintry.agent.tools.ToolRequest.DueWindow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class FinancialData {
  private FinancialData() {}

  /**
   * Values exactly as calculated by Fintry. {@code previous*} describe the month before {@code
   * month}; they are null when the upstream response does not contain them, never zero-filled.
   */
  public record Summary(
      BigDecimal balance,
      BigDecimal income,
      BigDecimal expense,
      BigDecimal result,
      BigDecimal previousIncome,
      BigDecimal previousExpense,
      BigDecimal previousResult) {
    public Summary(BigDecimal balance, BigDecimal income, BigDecimal expense, BigDecimal result) {
      this(balance, income, expense, result, null, null, null);
    }

    public boolean hasPrevious() {
      return previousIncome != null && previousExpense != null && previousResult != null;
    }
  }

  public record PendingItem(String description, LocalDate dueDate, BigDecimal remainingAmount) {}

  public record Pending(
      DueWindow window,
      LocalDate today,
      List<PendingItem> items,
      long totalElements,
      BigDecimal expense) {}

  public record Forecast(
      LocalDate start,
      LocalDate end,
      BigDecimal openingBalance,
      BigDecimal closingBalance,
      BigDecimal pendingIncome,
      BigDecimal pendingExpense,
      BigDecimal minimumBalance,
      LocalDate firstNegativeDate) {}
}
