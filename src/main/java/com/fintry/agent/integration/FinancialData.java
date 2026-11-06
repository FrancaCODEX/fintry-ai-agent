package com.fintry.agent.integration;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class FinancialData {
  private FinancialData() {}

  public record Summary(
      BigDecimal balance, BigDecimal income, BigDecimal expense, BigDecimal result) {}

  public record PendingItem(String description, LocalDate dueDate, BigDecimal remainingAmount) {}

  public record Pending(
      LocalDate today, List<PendingItem> items, long totalElements, BigDecimal expense) {}

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
