package com.fintry.agent.ai;

import com.fintry.agent.ai.AiProvider.Decision;
import com.fintry.agent.tools.ToolRequest;
import com.fintry.agent.tools.ToolRequest.DueWindow;
import com.fintry.agent.tools.ToolRequest.Kind;
import java.text.Normalizer;
import java.time.*;
import java.util.*;
import java.util.regex.*;

/**
 * Intentionally small deterministic router: not an LLM. It exists so the whole product can run
 * and be evaluated without a paid API. When a period is ambiguous it asks instead of guessing.
 */
public final class SimulatedAiProvider implements AiProvider {
  private static final List<String> MONTHS =
      List.of(
          "janeiro", "fevereiro", "marco", "abril", "maio", "junho", "julho", "agosto", "setembro",
          "outubro", "novembro", "dezembro");
  private static final Pattern WRITE =
      Pattern.compile(
          "\\b(pague|pagar|paga|quite|quitar|baixe|baixar|registre|registrar|lance|lancar|crie"
              + "|criar|cadastre|cadastrar|apague|apagar|delete|deletar|exclua|excluir|altere"
              + "|alterar|edite|editar|emita|emitir|cobre|cobrar|transfira|transferir|estorne)\\b");
  private static final Pattern OTHER_COMPANY =
      Pattern.compile(
          "\\b(outra|outras|outro|demais|todas as) (empresa|empresas|companhia)"
              + "|\\bempresa ([a-z]|\\d+)\\b|\\b(tenant|company ?id|companyid|cnpj)\\b");
  private static final Pattern INJECTION =
      Pattern.compile(
          "\\b(ignore|ignorar|desconsidere|sql|select|prompt|credencial|senha|token|cookie"
              + "|jsessionid|admin|administrador)\\b");
  private static final Pattern CONCEPT_QUESTION =
      Pattern.compile("\\b(considera|inclui|incluem|diferenca|significa|o que e|explique|explica)\\b");
  private static final Pattern CONCEPT_TERM = Pattern.compile("\\b(saldo|resultado|projec|projet|previs)");
  private static final Pattern ISO_MONTH = Pattern.compile("\\b(\\d{4})-(\\d{2})\\b");
  private static final Pattern SLASH_MONTH = Pattern.compile("\\b(\\d{1,2})/(\\d{4})\\b");
  private static final Pattern NAMED_MONTH =
      Pattern.compile("\\b(" + String.join("|", MONTHS) + ")\\b(?:\\s+de)?(?:\\s+(\\d{4}))?");
  private static final Pattern DAYS = Pattern.compile("\\b(\\d{1,4}|sete) dias\\b");
  private static final Pattern PREVIOUS_MONTH =
      Pattern.compile("\\b(mes passado|mes anterior|ultimo mes|anterior)\\b");
  private static final Pattern UNSUPPORTED_PERIOD =
      Pattern.compile(
          "\\b(semana|semanal|ano|anual|trimestre|semestre|ontem|amanha|ultimos \\d+ dias)\\b");

  public Plan plan(String message, LocalDate today, List<ToolRequest> previous) {
    var text = normalize(message);
    if (WRITE.matcher(text.replace("a pagar", "").replace("a receber", "")).find())
      return Plan.decline(Decision.WRITE_REQUEST);
    if (OTHER_COMPANY.matcher(text).find()) return Plan.decline(Decision.OTHER_COMPANY);
    if (INJECTION.matcher(text).find()) return Plan.decline(Decision.UNSUPPORTED);
    if (CONCEPT_QUESTION.matcher(text).find() && CONCEPT_TERM.matcher(text).find())
      return Plan.of(ToolRequest.of(Kind.CONCEPTS));

    var period = Period.parse(text, today, previous);
    if (period.ambiguous() || UNSUPPORTED_PERIOD.matcher(text).find())
      return Plan.decline(Decision.CLARIFY_PERIOD);

    var calls = new ArrayList<ToolRequest>();
    boolean wantsForecast = Pattern.compile("\\b(fluxo|projec|projet|previs|futuro)").matcher(text).find();
    boolean compare = Pattern.compile("\\b(compar|versus|vs|em relacao)").matcher(text).find();

    if (Pattern.compile("\\bsaldo\\b").matcher(text).find() && !wantsForecast) {
      // Only the current balance exists in the contract: a historical balance is not guessed.
      if (period.month() != null || period.days() != null)
        return Plan.decline(Decision.CLARIFY_PERIOD);
      calls.add(ToolRequest.of(Kind.BALANCE));
    }
    if (compare) {
      calls.add(ToolRequest.compare(period.explicitMonth()));
    } else if (Pattern.compile(
            "\\b(entrou|entradas|saiu|saidas|resumo|receitas|despesas|gastei|gastos|recebi"
                + "|resultado|movimenta)")
        .matcher(text)
        .find()) calls.add(ToolRequest.summary(period.month()));
    if (Pattern.compile("\\b(venc|atrasad|em atraso|a pagar|pendente|pendenc|boleto)")
        .matcher(text)
        .find()) {
      var windows = dueWindows(text, period);
      if (windows.isEmpty()) return Plan.decline(Decision.CLARIFY_PERIOD);
      windows.forEach(window -> calls.add(ToolRequest.pending(window)));
    } else if (wantsForecast) {
      calls.add(new ToolRequest(Kind.FORECAST, null, period.days(), null));
    }
    if (!calls.isEmpty()) return new Plan(Decision.QUERY, List.copyOf(calls), period.fromContext());
    return followUp(text, period, previous);
  }

  /** Short follow-ups ("E no mês anterior?") reuse only the previous query structure. */
  private Plan followUp(String text, Period period, List<ToolRequest> previous) {
    var last = previous.isEmpty() ? null : previous.getLast();
    if (period.month() != null) {
      if (last != null && (last.kind() == Kind.SUMMARY || last.kind() == Kind.COMPARE))
        return Plan.followUpOf(new ToolRequest(last.kind(), period.month(), null, null));
      if (last != null) return Plan.decline(Decision.CLARIFY_PERIOD);
      return Plan.decline(Decision.CLARIFY_TOPIC);
    }
    if (period.days() != null) {
      if (last != null && last.kind() == Kind.FORECAST) return Plan.followUpOf(ToolRequest.forecast(period.days()));
      if (last != null && last.kind() == Kind.PENDING)
        return period.days() == 7
            ? Plan.followUpOf(ToolRequest.pending(DueWindow.NEXT7))
            : Plan.decline(Decision.CLARIFY_PERIOD);
      return Plan.decline(Decision.CLARIFY_TOPIC);
    }
    if (last != null && last.kind() == Kind.PENDING && text.matches(".*\\bhoje\\b.*"))
      return Plan.followUpOf(ToolRequest.pending(DueWindow.TODAY));
    if (Pattern.compile("\\b(financ|dinheiro|caixa|conta|contas|valor|valores)").matcher(text).find())
      return Plan.decline(Decision.CLARIFY_TOPIC);
    return Plan.decline(Decision.UNSUPPORTED);
  }

  /** Every window the user named; none means the horizon is not one Fintry offers. */
  private List<DueWindow> dueWindows(String text, Period period) {
    var windows = new ArrayList<DueWindow>();
    if (Pattern.compile("\\b(vencid|atrasad|em atraso)").matcher(text).find()) windows.add(DueWindow.OVERDUE);
    if (text.matches(".*\\bhoje\\b.*")) windows.add(DueWindow.TODAY);
    if (Integer.valueOf(7).equals(period.days())) windows.add(DueWindow.NEXT7);
    else if (period.days() != null) return List.of(); // e.g. 30 days: ask instead of choosing
    return windows;
  }

  private static String normalize(String message) {
    return Normalizer.normalize(message, Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "")
        .toLowerCase(Locale.ROOT)
        .replaceAll("\\s+", " ");
  }

  /**
   * @param month resolved month (explicit or relative), null when none was mentioned
   * @param explicitMonth month written by the user; relative words are not counted for comparisons
   */
  private record Period(
      String month, String explicitMonth, Integer days, boolean ambiguous, boolean fromContext) {
    static Period parse(String text, LocalDate today, List<ToolRequest> previous) {
      String explicit = null;
      boolean ambiguous = false;
      var iso = ISO_MONTH.matcher(text);
      var slash = SLASH_MONTH.matcher(text);
      var named = NAMED_MONTH.matcher(text);
      if (iso.find()) explicit = iso.group(1) + "-" + iso.group(2);
      else if (slash.find())
        explicit = slash.group(2) + "-" + String.format("%02d", Integer.parseInt(slash.group(1)));
      else if (named.find()) {
        if (named.group(2) == null) ambiguous = true; // "outubro" without a year: ask
        else
          explicit =
              named.group(2) + "-" + String.format("%02d", MONTHS.indexOf(named.group(1)) + 1);
      }
      String month = explicit;
      boolean fromContext = false;
      boolean compare = Pattern.compile("\\b(compar|versus|vs|em relacao)").matcher(text).find();
      if (month == null && PREVIOUS_MONTH.matcher(text).find() && !compare) {
        var base =
            previous.stream()
                .filter(q -> q.month() != null)
                .map(q -> YearMonth.parse(q.month()))
                .reduce((first, second) -> second);
        fromContext = base.isPresent();
        month = base.orElse(YearMonth.from(today)).minusMonths(1).toString();
      } else if (month == null
          && Pattern.compile("\\b(este|neste|esse|nesse) mes|mes atual\\b").matcher(text).find())
        month = YearMonth.from(today).toString();
      var days = DAYS.matcher(text);
      Integer horizon = null;
      if (days.find()) horizon = days.group(1).equals("sete") ? 7 : Integer.parseInt(days.group(1));
      return new Period(month, explicit, horizon, ambiguous, fromContext);
    }
  }
}
