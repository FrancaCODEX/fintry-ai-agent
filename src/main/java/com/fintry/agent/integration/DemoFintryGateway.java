package com.fintry.agent.integration;

import com.fintry.agent.integration.FinancialData.*;
import com.fintry.agent.tools.ToolRequest.DueWindow;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** A fixed fictional company, not a development backdoor into real integration. */
public class DemoFintryGateway implements FintryGateway {
  private final Clock clock;

  public DemoFintryGateway(Clock clock) {
    this.clock = clock;
  }

  public UserContext authenticate(String ignored) {
    return new UserContext("demo-viewer", "demo-company", Set.of("READ_FINANCE"), "");
  }

  private void check(UserContext user) {
    user.requireRead();
    if (!user.companyId().equals("demo-company") || !user.userId().equals("demo-viewer"))
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Empresa indisponível na demonstração.");
  }

  private BigDecimal money(String value) {
    return new BigDecimal(value);
  }

  /**
   * Mirrors the upstream contract: a month without records returns zeros (HTTP 200), not an error.
   * Only the current and previous months have fictional movements.
   */
  public Summary summary(UserContext user, YearMonth month) {
    check(user);
    var current = YearMonth.now(clock);
    var balance = money("12500.00");
    var zero = money("0.00");
    if (month.equals(current))
      return new Summary(
          balance,
          money("18000.00"),
          money("8500.00"),
          money("9500.00"),
          money("15000.00"),
          money("9800.00"),
          money("5200.00"));
    if (month.equals(current.minusMonths(1)))
      return new Summary(
          balance, money("15000.00"), money("9800.00"), money("5200.00"), zero, zero, zero);
    return new Summary(balance, zero, zero, zero, zero, zero, zero);
  }

  public Pending pending(UserContext user, DueWindow window) {
    check(user);
    var today = LocalDate.now(clock);
    return switch (window) {
      case OVERDUE ->
          new Pending(
              window,
              today,
              List.of(new PendingItem("Internet e telefone", today.minusDays(3), money("320.00"))),
              1,
              money("320.00"));
      case TODAY -> new Pending(window, today, List.of(), 0, money("0.00"));
      case NEXT7 ->
          new Pending(
              window,
              today,
              List.of(
                  new PendingItem("Aluguel do escritório", today.plusDays(2), money("1800.00")),
                  new PendingItem("Serviços e ferramentas", today.plusDays(5), money("700.00"))),
              2,
              money("2500.00"));
    };
  }

  public Forecast forecast(UserContext user, int days) {
    check(user);
    var today = LocalDate.now(clock);
    var income = days >= 7 ? money("5000.00") : money("0.00");
    var expense = money(days >= 5 ? "2500.00" : days >= 2 ? "1800.00" : "0.00");
    var opening = money("12500.00");
    return new Forecast(
        today,
        today.plusDays(days),
        opening,
        opening.add(income).subtract(expense),
        income,
        expense,
        opening.subtract(expense),
        null);
  }
}
