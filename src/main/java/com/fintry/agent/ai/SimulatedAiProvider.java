package com.fintry.agent.ai;

import com.fintry.agent.tools.ToolRequest;
import com.fintry.agent.tools.ToolRequest.Kind;
import java.text.Normalizer;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

/** Intentionally small deterministic router: not an LLM. */
public final class SimulatedAiProvider implements AiProvider {
  public Plan plan(String message, LocalDate today) {
    var text =
        Normalizer.normalize(message, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .toLowerCase(Locale.ROOT);
    // Refuse unsupported instructions rather than pretending to understand arbitrary language.
    if (Pattern.compile(
            "\\b(pague|pagar|registre|registrar|crie|criar|apague|delete|altere|ignore|sql|empresa|tenant|amanha|ontem|anterior|passado|semana|ano|janeiro|fevereiro|marco|abril|maio|junho|julho|agosto|setembro|outubro|novembro|dezembro)\\b")
        .matcher(text)
        .find()) return new Plan(List.of());
    var month = YearMonth.from(today).toString();
    var explicit = Pattern.compile("\\b(\\d{4}-\\d{2})\\b").matcher(text);
    boolean hasMonth = explicit.find();
    if (hasMonth) month = explicit.group(1);
    var calls = new ArrayList<ToolRequest>();
    if (text.contains("saldo")) {
      if (hasMonth || Pattern.compile("\\b\\d+\\b").matcher(text).find())
        return new Plan(List.of());
      calls.add(new ToolRequest(Kind.BALANCE, null, null));
    }
    if (text.contains("entrou") || text.contains("saiu") || text.contains("resumo"))
      calls.add(new ToolRequest(Kind.SUMMARY, month, null));
    if (text.contains("venc") || text.contains("pendenc") || text.contains("contas")) {
      if (!text.contains("sete dias") && !text.contains("7 dias")) return new Plan(List.of());
      calls.add(new ToolRequest(Kind.PENDING, null, 7));
    }
    if (text.contains("fluxo") || text.contains("projec")) {
      var days = Pattern.compile("\\b(\\d{1,3}) dias\\b").matcher(text);
      calls.add(
          new ToolRequest(Kind.FORECAST, null, days.find() ? Integer.parseInt(days.group(1)) : 30));
    }
    return new Plan(List.copyOf(calls));
  }
}
