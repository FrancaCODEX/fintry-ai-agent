package com.fintry.agent.tools;

import com.fintry.agent.integration.*;
import java.text.NumberFormat;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class FinancialTools {
  public record Evidence(
      String tool,
      String period,
      String source,
      String explanation,
      String limitation,
      String status) {}

  private final FintryGateway gateway;
  private final Clock clock;

  public FinancialTools(FintryGateway gateway, Clock clock) {
    this.gateway = gateway;
    this.clock = clock;
  }

  private ResponseStatusException invalid() {
    return new ResponseStatusException(
        HttpStatus.BAD_REQUEST,
        "Consulta fora do catálogo ou período inválido. Use mês AAAA-MM, vencimentos em sete dias"
            + " ou projeção de 1 a 90 dias.");
  }

  public void validate(ToolRequest request) {
    if (request == null || request.kind() == null) throw invalid();
    try {
      switch (request.kind()) {
        case BALANCE -> {
          if (request.month() != null || request.days() != null) throw invalid();
        }
        case SUMMARY -> {
          if (request.month() == null
              || !request.month().matches("\\d{4}-\\d{2}")
              || request.days() != null) throw invalid();
          var month = YearMonth.parse(request.month());
          var now = YearMonth.now(clock);
          if (month.isBefore(now.minusMonths(24)) || month.isAfter(now)) throw invalid();
        }
        case PENDING -> {
          if (request.month() != null || !Integer.valueOf(7).equals(request.days()))
            throw invalid();
        }
        case FORECAST -> {
          if (request.month() != null
              || request.days() == null
              || request.days() < 1
              || request.days() > 90) throw invalid();
        }
      }
    } catch (DateTimeException ex) {
      throw invalid();
    }
  }

  private String money(java.math.BigDecimal value) {
    return NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pt-BR")).format(value);
  }

  private String period(LocalDate start, LocalDate end) {
    return start + " a " + end + " (Brasília, inclusive)";
  }

  public Evidence execute(ToolRequest request, UserContext user) {
    user.requireRead();
    validate(request);
    var today = LocalDate.now(clock);
    return switch (request.kind()) {
      case BALANCE -> {
        var data = gateway.summary(user, YearMonth.from(today));
        yield new Evidence(
            "BALANCE",
            "Saldo atual em " + today + " (Brasília)",
            "/api/v1/finance/summary?month=" + YearMonth.from(today),
            "Seu saldo disponível é " + money(data.balance()) + ".",
            "Soma dos saldos atuais das contas; não é um saldo histórico nem desconta vencimentos"
                + " futuros.",
            "ok");
      }
      case SUMMARY -> {
        var month = YearMonth.parse(request.month());
        var data = gateway.summary(user, month);
        yield new Evidence(
            "SUMMARY",
            period(month.atDay(1), month.atEndOfMonth()),
            "/api/v1/finance/summary?month=" + month,
            "Entraram "
                + money(data.income())
                + " e saíram "
                + money(data.expense())
                + ". Resultado realizado: "
                + money(data.result())
                + ".",
            "Regime de caixa: pagamentos efetivados no mês consultado, conforme registros"
                + " disponíveis no momento da consulta.",
            "ok");
      }
      case PENDING -> {
        var data = gateway.pending(user);
        var lines =
            new StringBuilder(
                "Contas a pagar: "
                    + data.totalElements()
                    + ". Total pendente: "
                    + money(data.expense())
                    + ".");
        for (var item : data.items())
          lines
              .append("\n• ")
              .append(item.dueDate())
              .append(" — ")
              .append(item.description())
              .append(": ")
              .append(money(item.remainingAmount()));
        yield new Evidence(
            "PENDING",
            period(data.today().plusDays(1), data.today().plusDays(7)),
            "/api/v1/finance/pending?group=NEXT7&type=EXPENSE&page=0&size=20",
            lines.toString(),
            "Inclui pendentes e parciais, exclui transferências e contas que vencem hoje. "
                + (data.totalElements() > data.items().size()
                    ? "Lista parcial: exibidos os primeiros 20; total informado pelo Fintry abrange"
                          + " toda a janela."
                    : "Lista completa da janela."),
            "ok");
      }
      case FORECAST -> {
        var data = gateway.forecast(user, request.days());
        yield new Evidence(
            "FORECAST",
            period(data.start(), data.end()),
            "/api/v1/reports/forecast?days=" + request.days() + "&includeOverdue=false",
            "Saldo inicial: "
                + money(data.openingBalance())
                + ". Entradas previstas: "
                + money(data.pendingIncome())
                + ". Saídas previstas: "
                + money(data.pendingExpense())
                + ". Saldo final previsto: "
                + money(data.closingBalance())
                + ". Menor saldo: "
                + money(data.minimumBalance())
                + "."
                + (data.firstNegativeDate() == null
                    ? " Sem saldo negativo previsto na janela."
                    : " Primeiro saldo negativo: " + data.firstNegativeDate() + "."),
            "Projeção por vencimento dos registros existentes, sem atrasados. Não garante"
                + " recebimentos ou pagamentos futuros.",
            "ok");
      }
    };
  }
}
