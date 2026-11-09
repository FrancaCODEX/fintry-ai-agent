package com.fintry.agent.tools;

import com.fintry.agent.integration.*;
import com.fintry.agent.integration.FinancialData.*;
import com.fintry.agent.tools.ToolRequest.DueWindow;
import com.fintry.agent.tools.ToolRequest.Kind;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Typed read-only catalog. Values come from {@link FintryGateway}; this class only checks
 * arguments and formats evidence. It performs no financial arithmetic.
 */
@Component
public class FinancialTools {
  public static final int MAX_MONTHS_BACK = 24;
  public static final int MAX_FORECAST_DAYS = 90;
  public static final int DEFAULT_FORECAST_DAYS = 30;

  /** status: ok, empty (Fintry answered, no records), unavailable (failed), info (no data used). */
  public record Evidence(
      String tool,
      String title,
      String period,
      String source,
      String explanation,
      String limitation,
      String status) {}

  /** A plan argument the backend refuses. {@code clarify} marks a valid shape but unsupported period. */
  public static final class InvalidRequest extends RuntimeException {
    private final boolean clarify;

    InvalidRequest(String message, boolean clarify) {
      super(message, null, false, false);
      this.clarify = clarify;
    }

    public boolean clarify() {
      return clarify;
    }
  }

  private final FintryGateway gateway;
  private final Clock clock;

  public FinancialTools(FintryGateway gateway, Clock clock) {
    this.gateway = gateway;
    this.clock = clock;
  }

  private static InvalidRequest malformed() {
    return new InvalidRequest("Argumentos fora do contrato da ferramenta.", false);
  }

  public void validate(ToolRequest request) {
    if (request == null || request.kind() == null) throw malformed();
    var noMonth = request.month() == null;
    var noDays = request.days() == null;
    var noWindow = request.window() == null;
    switch (request.kind()) {
      case BALANCE, CONCEPTS -> {
        if (!noMonth || !noDays || !noWindow) throw malformed();
      }
      case SUMMARY, COMPARE -> {
        if (!noDays || !noWindow) throw malformed();
        if (!noMonth) checkMonth(request.month());
      }
      case PENDING -> {
        if (!noMonth || !noDays || noWindow) throw malformed();
      }
      case FORECAST -> {
        if (!noMonth || !noWindow) throw malformed();
        if (!noDays && (request.days() < 1 || request.days() > MAX_FORECAST_DAYS))
          throw new InvalidRequest(
              "A projeção aceita de 1 a "
                  + MAX_FORECAST_DAYS
                  + " dias a partir de hoje. Informe um horizonte nesse intervalo.",
              true);
      }
    }
  }

  private void checkMonth(String value) {
    if (!value.matches("\\d{4}-\\d{2}")) throw malformed();
    YearMonth month;
    try {
      month = YearMonth.parse(value);
    } catch (DateTimeException ex) {
      throw malformed();
    }
    var now = YearMonth.now(clock);
    if (month.isBefore(now.minusMonths(MAX_MONTHS_BACK)) || month.isAfter(now))
      throw new InvalidRequest(
          "Consigo consultar meses de "
              + now.minusMonths(MAX_MONTHS_BACK)
              + " até "
              + now
              + ". "
              + value
              + " está fora desse intervalo; meses futuros ainda não têm valores realizados"
              + " (use a projeção).",
          true);
  }

  /** Fills Java-side defaults so the stored context and the evidence show the real period. */
  public ToolRequest resolve(ToolRequest request) {
    validate(request);
    return switch (request.kind()) {
      case SUMMARY, COMPARE ->
          request.month() != null
              ? request
              : new ToolRequest(request.kind(), YearMonth.now(clock).toString(), null, null);
      case FORECAST ->
          request.days() != null ? request : ToolRequest.forecast(DEFAULT_FORECAST_DAYS);
      default -> request;
    };
  }

  /** Human-readable note when {@link #resolve} chose a default. Null when nothing was assumed. */
  public String assumption(ToolRequest original) {
    return switch (original.kind()) {
      case SUMMARY, COMPARE ->
          original.month() == null
              ? "Período não informado: considerei o mês atual (" + YearMonth.now(clock) + ")."
              : null;
      case FORECAST ->
          original.days() == null
              ? "Horizonte não informado: considerei " + DEFAULT_FORECAST_DAYS + " dias."
              : null;
      default -> null;
    };
  }

  private String money(BigDecimal value) {
    return NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pt-BR")).format(value);
  }

  private String period(LocalDate start, LocalDate end) {
    return start + " a " + end + " (Brasília, inclusive)";
  }

  private static boolean isZero(BigDecimal value) {
    return value.signum() == 0;
  }

  public Evidence execute(ToolRequest original, UserContext user) {
    user.requireRead();
    var request = resolve(original);
    var today = LocalDate.now(clock);
    return switch (request.kind()) {
      case BALANCE -> {
        var data = gateway.summary(user, YearMonth.from(today));
        yield new Evidence(
            "BALANCE",
            "Saldo atual",
            "Saldo atual em " + today + " (Brasília)",
            "/api/v1/finance/summary?month=" + YearMonth.from(today),
            "Seu saldo disponível é " + money(data.balance()) + ".",
            "Soma dos saldos atuais das contas; não é um saldo histórico nem desconta vencimentos"
                + " futuros.",
            "ok");
      }
      case SUMMARY -> summary(YearMonth.parse(request.month()), user);
      case COMPARE -> compare(YearMonth.parse(request.month()), user);
      case PENDING -> pending(request.window(), user);
      case FORECAST -> forecast(request.days(), user);
      case CONCEPTS -> concepts();
    };
  }

  private Evidence summary(YearMonth month, UserContext user) {
    var data = gateway.summary(user, month);
    var source = "/api/v1/finance/summary?month=" + month;
    var limitation =
        "Regime de caixa: pagamentos efetivados no mês consultado, conforme registros"
            + " disponíveis no momento da consulta.";
    if (isZero(data.income()) && isZero(data.expense()))
      return new Evidence(
          "SUMMARY",
          "Resumo do mês " + month,
          period(month.atDay(1), month.atEndOfMonth()),
          source,
          "O Fintry não tem entradas nem saídas efetivadas registradas em " + month + ".",
          "Sem registros não é falha: a consulta funcionou. Lançamentos ainda pendentes não"
              + " aparecem no realizado.",
          "empty");
    return new Evidence(
        "SUMMARY",
        "Resumo do mês " + month,
        period(month.atDay(1), month.atEndOfMonth()),
        source,
        "Entraram "
            + money(data.income())
            + " e saíram "
            + money(data.expense())
            + ". Resultado realizado: "
            + money(data.result())
            + ".",
        limitation,
        "ok");
  }

  private Evidence compare(YearMonth month, UserContext user) {
    var data = gateway.summary(user, month);
    if (!data.hasPrevious())
      throw new ResponseStatusException(
          HttpStatus.BAD_GATEWAY, "O Fintry não informou os valores do mês anterior.");
    var previous = month.minusMonths(1);
    var source = "/api/v1/finance/summary?month=" + month + " (campos previous*)";
    var title = "Comparação " + previous + " → " + month;
    var period = period(previous.atDay(1), month.atEndOfMonth());
    if (isZero(data.income())
        && isZero(data.expense())
        && isZero(data.previousIncome())
        && isZero(data.previousExpense()))
      return new Evidence(
          "COMPARE",
          title,
          period,
          source,
          "Não há entradas nem saídas efetivadas em " + previous + " nem em " + month + ".",
          "Sem registros não é falha: a consulta funcionou.",
          "empty");
    // Ordering only (compareTo); differences are not computed here to keep arithmetic in Fintry.
    var trend =
        switch (Integer.signum(data.result().compareTo(data.previousResult()))) {
          case 1 -> "maior que";
          case -1 -> "menor que";
          default -> "igual ao";
        };
    return new Evidence(
        "COMPARE",
        title,
        period,
        source,
        month
            + ": entradas "
            + money(data.income())
            + ", saídas "
            + money(data.expense())
            + ", resultado "
            + money(data.result())
            + ".\n"
            + previous
            + ": entradas "
            + money(data.previousIncome())
            + ", saídas "
            + money(data.previousExpense())
            + ", resultado "
            + money(data.previousResult())
            + ".\nO resultado de "
            + month
            + " é "
            + trend
            + " o de "
            + previous
            + ".",
        "Regime de caixa nos dois meses, valores calculados pelo Fintry na mesma consulta. Um mês"
            + " em andamento ainda pode receber pagamentos.",
        "ok");
  }

  private Evidence pending(DueWindow window, UserContext user) {
    var data = gateway.pending(user, window);
    var today = data.today();
    var title =
        switch (window) {
          case OVERDUE -> "Contas a pagar vencidas";
          case TODAY -> "Contas a pagar que vencem hoje";
          case NEXT7 -> "Contas a pagar nos próximos 7 dias";
        };
    var period =
        switch (window) {
          case OVERDUE -> "Vencimento até " + today.minusDays(1) + " (antes de hoje, Brasília)";
          case TODAY -> today + " (hoje, Brasília)";
          case NEXT7 -> period(today.plusDays(1), today.plusDays(7));
        };
    var source = "/api/v1/finance/pending?group=" + window + "&type=EXPENSE&page=0&size=20";
    var scope =
        switch (window) {
          case OVERDUE -> "Pendentes e parciais com vencimento anterior a hoje";
          case TODAY -> "Pendentes e parciais com vencimento hoje";
          case NEXT7 -> "Pendentes e parciais de amanhã até hoje+7; exclui as que vencem hoje";
        };
    if (data.totalElements() == 0)
      return new Evidence(
          "PENDING",
          title,
          period,
          source,
          switch (window) {
            case OVERDUE -> "Nenhuma conta a pagar vencida.";
            case TODAY -> "Nenhuma conta a pagar vence hoje.";
            case NEXT7 -> "Nenhuma conta a pagar vence nos próximos 7 dias.";
          },
          scope + ". Exclui transferências e recebimentos.",
          "empty");
    var lines =
        new StringBuilder(
            "Contas: " + data.totalElements() + ". Total restante: " + money(data.expense()) + ".");
    for (var item : data.items())
      lines
          .append("\n• ")
          .append(item.dueDate())
          .append(" — ")
          .append(item.description())
          .append(": ")
          .append(money(item.remainingAmount()));
    return new Evidence(
        "PENDING",
        title,
        period,
        source,
        lines.toString(),
        scope
            + ". Exclui transferências e recebimentos. "
            + (data.totalElements() > data.items().size()
                ? "Lista parcial: exibidas as primeiras "
                    + data.items().size()
                    + "; o total informado pelo Fintry abrange toda a janela."
                : "Lista completa da janela."),
        "ok");
  }

  private Evidence forecast(int days, UserContext user) {
    var data = gateway.forecast(user, days);
    return new Evidence(
        "FORECAST",
        "Projeção de caixa em " + days + " dias",
        period(data.start(), data.end()),
        "/api/v1/reports/forecast?days=" + days + "&includeOverdue=false",
        "Saldo inicial (saldo atual): "
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

  /** Fixed definitions taken from the inspected Fintry contract. No values, no gateway call. */
  private Evidence concepts() {
    return new Evidence(
        "CONCEPTS",
        "Saldo atual, resultado realizado e saldo projetado",
        "Conceitos (não consulta valores)",
        "Definições fixas do Agent, baseadas no contrato do Fintry 0.39.1",
        "• Saldo atual: soma dos saldos das contas agora. Não desconta contas a vencer nem soma"
            + " recebimentos futuros.\n"
            + "• Resultado realizado do mês: entradas menos saídas efetivamente pagas no mês"
            + " (regime de caixa). É diferente do saldo, que acumula todos os meses.\n"
            + "• Saldo projetado: parte do saldo atual e aplica entradas e saídas previstas por"
            + " vencimento, de hoje até o horizonte escolhido.\n"
            + "Resumindo: o saldo atual não considera as contas a vencer; a projeção considera,"
            + " exceto as já vencidas (atrasadas).",
        "Explicação conceitual, sem valores. Para números, peça saldo, resumo ou projeção.",
        "info");
  }
}
