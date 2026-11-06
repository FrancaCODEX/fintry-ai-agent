package com.fintry.agent.integration;

import com.fintry.agent.integration.FinancialData.*;
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

  public Summary summary(UserContext user, YearMonth month) {
    check(user);
    if (!month.equals(YearMonth.now(clock)))
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY, "A demonstração só tem dados do mês atual.");
    return new Summary(money("12500.00"), money("18000.00"), money("8500.00"), money("9500.00"));
  }

  public Pending pending(UserContext user) {
    check(user);
    var today = LocalDate.now(clock);
    return new Pending(
        today,
        List.of(
            new PendingItem("Aluguel do escritório", today.plusDays(2), money("1800.00")),
            new PendingItem("Serviços e ferramentas", today.plusDays(5), money("700.00"))),
        2,
        money("2500.00"));
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
