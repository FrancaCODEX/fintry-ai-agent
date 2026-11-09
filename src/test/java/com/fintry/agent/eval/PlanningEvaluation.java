package com.fintry.agent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintry.agent.ai.AiProvider;
import com.fintry.agent.integration.DemoFintryGateway;
import com.fintry.agent.orchestration.ConversationContext;
import com.fintry.agent.orchestration.FinancialAgent;
import com.fintry.agent.tools.FinancialTools;
import com.fintry.agent.tools.ToolRequest;
import com.fintry.agent.tools.ToolRequest.DueWindow;
import com.fintry.agent.tools.ToolRequest.Kind;
import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.web.server.ResponseStatusException;

/**
 * Runs the versioned question set through the whole agent (planner + backend validation + demo
 * gateway) and compares the final outcome and the executed queries. Evaluating the end-to-end
 * behavior, not only the raw plan, means a case passes whether a clarification comes from the
 * model or from Java validation.
 */
public final class PlanningEvaluation {
  public record Case(
      String id,
      String category,
      String message,
      List<String> previous,
      String outcome,
      List<String> tools) {}

  public record Suite(int version, String today, String notation, List<Case> cases) {}

  public record Result(Case expected, String outcome, List<String> tools, long millis) {
    public boolean passed() {
      return expected.outcome().equals(outcome) && expected.tools().equals(tools);
    }
  }

  private PlanningEvaluation() {}

  public static Suite load() {
    try (var in = PlanningEvaluation.class.getResourceAsStream("/eval/planning-cases.json")) {
      return new ObjectMapper().readValue(in, Suite.class);
    } catch (IOException ex) {
      throw new IllegalStateException("Conjunto de avaliação inválido", ex);
    }
  }

  public static List<Result> run(Suite suite, AiProvider provider, String providerName) {
    var clock =
        Clock.fixed(
            LocalDate.parse(suite.today()).atTime(12, 0).atZone(ZoneId.of("America/Sao_Paulo")).toInstant(),
            ZoneId.of("America/Sao_Paulo"));
    var demo = new DemoFintryGateway(clock);
    var agent =
        new FinancialAgent(
            provider, demo, new FinancialTools(demo, clock), clock, "demo", providerName, 30, 3);
    var results = new ArrayList<Result>();
    try {
      for (var c : suite.cases()) {
        var previous =
            c.previous() == null
                ? null
                : new ConversationContext(
                    "demo-viewer",
                    "demo-company",
                    c.previous().stream().map(PlanningEvaluation::parse).toList(),
                    Instant.now(clock));
        long started = System.nanoTime();
        String outcome;
        List<String> tools;
        try {
          var turn = agent.chat(c.message(), "", previous);
          outcome = turn.answer().outcome();
          tools = turn.executed().stream().map(PlanningEvaluation::notation).toList();
        } catch (ResponseStatusException ex) {
          outcome = "error_" + ex.getStatusCode().value();
          tools = List.of();
        }
        results.add(new Result(c, outcome, tools, (System.nanoTime() - started) / 1_000_000));
      }
    } finally {
      agent.close();
    }
    return results;
  }

  public static String notation(ToolRequest q) {
    return switch (q.kind()) {
      case SUMMARY, COMPARE -> q.kind() + ":" + q.month();
      case PENDING -> q.kind() + ":" + q.window();
      case FORECAST -> q.kind() + ":" + q.days();
      default -> q.kind().name();
    };
  }

  public static ToolRequest parse(String notation) {
    var parts = notation.split(":", 2);
    var kind = Kind.valueOf(parts[0]);
    return switch (kind) {
      case SUMMARY, COMPARE -> new ToolRequest(kind, parts[1], null, null);
      case PENDING -> ToolRequest.pending(DueWindow.valueOf(parts[1]));
      case FORECAST -> ToolRequest.forecast(Integer.parseInt(parts[1]));
      default -> ToolRequest.of(kind);
    };
  }

  /** Aggregate report: no message text from the model, only ids, outcomes and tool notation. */
  public static String report(List<Result> results) {
    var out = new StringBuilder();
    long passed = results.stream().filter(Result::passed).count();
    out.append(String.format("Avaliação: %d/%d casos corretos (%.0f%%)%n", passed, results.size(), 100.0 * passed / results.size()));
    results.stream()
        .collect(Collectors.groupingBy(r -> r.expected().category(), TreeMap::new, Collectors.toList()))
        .forEach(
            (category, list) ->
                out.append(
                    String.format(
                        "  %-14s %d/%d%n",
                        category, list.stream().filter(Result::passed).count(), list.size())));
    var durations = results.stream().mapToLong(Result::millis).sorted().toArray();
    out.append(String.format("  duração p50=%dms máx=%dms%n", durations[durations.length / 2], durations[durations.length - 1]));
    results.stream()
        .filter(r -> !r.passed())
        .forEach(
            r ->
                out.append(
                    String.format(
                        "  FALHOU %s: esperado %s %s, obtido %s %s%n",
                        r.expected().id(), r.expected().outcome(), r.expected().tools(), r.outcome(), r.tools())));
    return out.toString();
  }
}
