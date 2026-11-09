package com.fintry.agent.orchestration;

import com.fintry.agent.ai.AiProvider;
import com.fintry.agent.ai.AiProvider.Decision;
import com.fintry.agent.integration.FintryGateway;
import com.fintry.agent.integration.UserContext;
import com.fintry.agent.tools.FinancialTools;
import com.fintry.agent.tools.FinancialTools.Evidence;
import com.fintry.agent.tools.FinancialTools.InvalidRequest;
import com.fintry.agent.tools.ToolRequest;
import com.fintry.agent.tools.ToolRequest.Kind;
import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FinancialAgent {
  /**
   * outcome: answered, partial, no_data, failed, clarification, unsupported, refused, invalid_plan.
   * Every user-facing message is written here in Java; model text is never displayed.
   */
  public record Answer(
      String outcome,
      String message,
      String integration,
      String provider,
      boolean contextUsed,
      boolean retryable,
      List<String> suggestions,
      List<Evidence> evidence) {}

  /**
   * Answer for the user, the context to keep in their session (null clears it) and the resolved
   * queries that were actually executed (for evaluation and metrics; never sent to the client).
   */
  public record Turn(Answer answer, ConversationContext next, List<ToolRequest> executed) {
    Turn(Answer answer, ConversationContext next) {
      this(answer, next, List.of());
    }
  }

  static final String PERIOD_HELP =
      "Preciso de um período que eu consiga consultar sem adivinhar:\n"
          + "• Saldo: somente o atual.\n"
          + "• Resumo ou comparação: um mês com ano (ex.: 2026-10 ou “outubro de 2026”), até 24"
          + " meses atrás.\n"
          + "• Contas a pagar: vencidas, que vencem hoje ou nos próximos 7 dias.\n"
          + "• Projeção de caixa: de 1 a 90 dias a partir de hoje.\n"
          + "Semanas, anos inteiros e intervalos livres ainda não são suportados.";
  static final String CATALOG =
      "Posso consultar o saldo atual, o resumo de um mês, a comparação de um mês com o anterior,"
          + " contas a pagar vencidas, de hoje ou dos próximos 7 dias, a projeção de caixa de até 90"
          + " dias e explicar a diferença entre esses números.";
  static final List<String> DEFAULT_SUGGESTIONS =
      List.of(
          "Qual é meu saldo disponível?",
          "Compare este mês com o anterior",
          "Quais contas estão vencidas?",
          "Como está meu caixa nos próximos 30 dias?");

  private static final Logger metrics = LoggerFactory.getLogger("fintry.agent.metrics");

  private final AiProvider provider;
  private final FintryGateway gateway;
  private final FinancialTools tools;
  private final Clock clock;
  private final String integrationMode, providerMode;
  private final int timeout, maxTools;
  private final ExecutorService executor =
      new ThreadPoolExecutor(
          4,
          4,
          0,
          TimeUnit.MILLISECONDS,
          new SynchronousQueue<>(),
          Thread.ofVirtual().name("financial-query-", 0).factory());

  public FinancialAgent(
      AiProvider provider,
      FintryGateway gateway,
      FinancialTools tools,
      Clock clock,
      @Value("${agent.integration}") String integrationMode,
      @Value("${agent.provider}") String providerMode,
      @Value("${agent.timeout-seconds}") int timeout,
      @Value("${agent.max-tools}") int maxTools) {
    this.provider = provider;
    this.gateway = gateway;
    this.tools = tools;
    this.clock = clock;
    this.integrationMode = integrationMode;
    this.providerMode = providerMode;
    if (timeout < 1 || timeout > 30 || maxTools < 1 || maxTools > 3)
      throw new IllegalArgumentException("Limites inválidos");
    this.timeout = timeout;
    this.maxTools = maxTools;
  }

  public Answer chat(String message, String cookie) {
    return chat(message, cookie, null).answer();
  }

  public Turn chat(String message, String cookie, ConversationContext previous) {
    long started = System.nanoTime();
    String outcome = "error";
    int toolCount = 0, unavailable = 0;
    boolean contextUsed = false;
    try {
      var turn = submit(message, cookie, previous);
      var answer = turn.answer();
      outcome = answer.outcome();
      contextUsed = answer.contextUsed();
      toolCount = (int) answer.evidence().stream().filter(e -> !e.tool().equals("CONCEPTS")).count();
      unavailable =
          (int) answer.evidence().stream().filter(e -> e.status().equals("unavailable")).count();
      return turn;
    } catch (ResponseStatusException ex) {
      outcome = "error_" + ex.getStatusCode().value();
      throw ex;
    } finally {
      // Operational metric only: no message, prompt, cookie, identity or financial value.
      metrics.info(
          "chat outcome={} provider={} integration={} tools={} unavailable={} context={}"
              + " durationMs={}",
          outcome,
          providerMode,
          integrationMode,
          toolCount,
          unavailable,
          contextUsed,
          (System.nanoTime() - started) / 1_000_000);
    }
  }

  private Turn submit(String message, String cookie, ConversationContext previous) {
    if (message == null || message.isBlank() || message.length() > 2000)
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Envie uma mensagem de 1 a 2000 caracteres.");
    Future<Turn> task;
    try {
      task = executor.submit(() -> run(message, cookie, previous));
    } catch (RejectedExecutionException ex) {
      throw new ResponseStatusException(
          HttpStatus.TOO_MANY_REQUESTS, "Assistente ocupado. Tente novamente em instantes.");
    }
    try {
      return task.get(timeout, TimeUnit.SECONDS);
    } catch (TimeoutException ex) {
      task.cancel(true);
      throw new ResponseStatusException(
          HttpStatus.GATEWAY_TIMEOUT, "Tempo de consulta excedido. Nenhum valor foi estimado.");
    } catch (InterruptedException ex) {
      task.cancel(true);
      Thread.currentThread().interrupt();
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Consulta interrompida.");
    } catch (ExecutionException ex) {
      if (ex.getCause() instanceof ResponseStatusException failure) throw failure;
      throw new ResponseStatusException(
          HttpStatus.BAD_GATEWAY,
          "Não foi possível concluir a consulta. Nenhum valor foi estimado.");
    }
  }

  private Answer answer(
      String outcome, String message, boolean contextUsed, boolean retryable, List<Evidence> ev) {
    var suggestions =
        switch (outcome) {
          case "answered", "partial", "no_data" -> List.<String>of();
          default -> DEFAULT_SUGGESTIONS;
        };
    return new Answer(
        outcome,
        message,
        integrationMode,
        providerMode,
        contextUsed,
        retryable,
        suggestions,
        List.copyOf(ev));
  }

  private Turn run(String message, String cookie, ConversationContext previous) {
    var user = gateway.authenticate(cookie);
    user.requireRead();
    var now = Instant.now(clock);
    // Context from another user/company or older than the TTL is discarded, not reinterpreted.
    var usable = previous != null && previous.usableBy(user, now) ? previous : null;
    var context = usable == null ? List.<ToolRequest>of() : usable.queries();
    AiProvider.Plan plan;
    try {
      plan = provider.plan(message, LocalDate.now(clock), context);
    } catch (RuntimeException ex) {
      throw new ResponseStatusException(
          HttpStatus.BAD_GATEWAY, "Provedor de IA indisponível ou com resposta inválida.");
    }
    var declined = decline(plan);
    if (declined != null) return new Turn(declined, usable);

    var queries = plan.queries().stream().distinct().toList();
    var notes = new ArrayList<String>();
    try {
      for (var query : queries) {
        tools.validate(query);
        var note = tools.assumption(query);
        if (note != null && !notes.contains(note)) notes.add(note);
      }
    } catch (InvalidRequest ex) {
      if (ex.clarify())
        return new Turn(
            answer("clarification", ex.getMessage() + "\n\n" + PERIOD_HELP, false, false, List.of()),
            usable);
      return new Turn(invalidPlan(), usable);
    }
    // Follow-up interpretation is flagged; values are always queried again below.
    boolean contextUsed = plan.followUp() && !context.isEmpty();
    var evidence = execute(queries, user);
    var answer = summarize(evidence, notes, contextUsed);
    var executed = queries.stream().map(tools::resolve).toList();
    var remembered = executed.stream().filter(q -> q.kind() != Kind.CONCEPTS).toList();
    var next = remembered.isEmpty() ? usable : remember(user, remembered, now);
    return new Turn(answer, next, executed);
  }

  /** Validates the decision shape. Returns the final answer when nothing must be executed. */
  private Answer decline(AiProvider.Plan plan) {
    if (plan == null || plan.decision() == null || plan.queries() == null) return invalidPlan();
    if (plan.decision() != Decision.QUERY)
      return plan.queries().isEmpty() ? declined(plan.decision()) : invalidPlan();
    if (plan.queries().isEmpty() || plan.queries().size() > maxTools) return invalidPlan();
    return null;
  }

  private Answer declined(Decision decision) {
    return switch (decision) {
      case CLARIFY_PERIOD -> answer("clarification", PERIOD_HELP, false, false, List.of());
      case CLARIFY_TOPIC ->
          answer(
              "clarification",
              "Não ficou claro qual consulta você quer. " + CATALOG,
              false,
              false,
              List.of());
      case WRITE_REQUEST ->
          answer(
              "refused",
              "Sou um assistente somente de consulta: não crio lançamentos, não pago contas, não"
                  + " emito cobranças e não altero dados. Faça essas operações no Fintry. "
                  + CATALOG,
              false,
              false,
              List.of());
      case OTHER_COMPANY ->
          answer(
              "refused",
              "Consulto apenas a empresa da sua sessão no Fintry, identificada pelo backend. A"
                  + " empresa não pode ser escolhida pela conversa. Para outra empresa, entre no"
                  + " Fintry com um usuário dela.",
              false,
              false,
              List.of());
      case UNSUPPORTED ->
          answer("unsupported", "Esse pedido está fora do que eu consigo fazer. " + CATALOG, false, false, List.of());
      case QUERY -> invalidPlan();
    };
  }

  private Answer invalidPlan() {
    return answer(
        "invalid_plan",
        "Não executei nenhuma consulta: o plano proposto pelo provedor de IA não passou na"
            + " validação do backend. Reformule a pergunta usando uma das opções abaixo.",
        false,
        true,
        List.of());
  }

  private List<Evidence> execute(List<ToolRequest> queries, UserContext user) {
    var evidence = new ArrayList<Evidence>();
    for (var request : queries) {
      if (Thread.currentThread().isInterrupted())
        throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Consulta interrompida.");
      try {
        evidence.add(tools.execute(request, user));
      } catch (ResponseStatusException ex) {
        int status = ex.getStatusCode().value();
        if (status == 401 || status == 403) throw ex; // identity problems end the whole turn
        evidence.add(unavailable(request, ex.getReason()));
      } catch (RuntimeException ex) {
        evidence.add(unavailable(request, "Falha inesperada nesta consulta."));
      }
    }
    return evidence;
  }

  private Evidence unavailable(ToolRequest request, String reason) {
    return new Evidence(
        request.kind().name(),
        "Consulta indisponível",
        "Período solicitado não confirmado",
        "Consulta sem resultado",
        "Não foi possível obter os dados desta consulta. Nenhum valor foi estimado ou"
            + " substituído por dados fictícios.",
        reason == null ? "Falha na integração." : reason,
        "unavailable");
  }

  private Answer summarize(List<Evidence> evidence, List<String> notes, boolean contextUsed) {
    long failed = evidence.stream().filter(e -> e.status().equals("unavailable")).count();
    long empty = evidence.stream().filter(e -> e.status().equals("empty")).count();
    int total = evidence.size();
    String outcome;
    var text = new StringBuilder();
    if (failed == total) {
      outcome = "failed";
      text.append(
          "Nenhuma consulta foi concluída: a integração com o Fintry falhou. Nenhum valor foi"
              + " estimado. Tente novamente em instantes.");
    } else if (failed > 0) {
      outcome = "partial";
      text.append("Resposta parcial: ")
          .append(total - failed)
          .append(" de ")
          .append(total)
          .append(
              " consultas concluídas. A que falhou está marcada como indisponível e não teve"
                  + " valores estimados.");
    } else if (empty == total) {
      outcome = "no_data";
      text.append("O Fintry respondeu normalmente, mas não há registros para o período consultado.");
    } else {
      outcome = "answered";
      text.append(total == 1 ? "Consulta concluída." : "Consultas concluídas: " + total + " de " + total + ".");
    }
    if (contextUsed)
      text.append(
          " Interpretei a pergunta a partir da consulta anterior; os valores foram consultados"
              + " novamente agora.");
    notes.forEach(note -> text.append(' ').append(note));
    return answer(outcome, text.toString(), contextUsed, failed > 0, evidence);
  }

  private ConversationContext remember(UserContext user, List<ToolRequest> executed, Instant now) {
    return new ConversationContext(user.userId(), user.companyId(), executed, now);
  }

  @PreDestroy
  public void close() {
    executor.shutdownNow();
  }
}
