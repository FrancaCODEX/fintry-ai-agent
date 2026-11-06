package com.fintry.agent.orchestration;

import com.fintry.agent.ai.AiProvider;
import com.fintry.agent.integration.FintryGateway;
import com.fintry.agent.tools.FinancialTools;
import com.fintry.agent.tools.FinancialTools.Evidence;
import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FinancialAgent {
  public record Answer(
      String message, String integration, String provider, List<Evidence> evidence) {}

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
    if (message == null || message.isBlank() || message.length() > 2000)
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Envie uma mensagem de 1 a 2000 caracteres.");
    Future<Answer> task;
    try {
      task = executor.submit(() -> run(message, cookie));
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

  private Answer run(String message, String cookie) {
    var user = gateway.authenticate(cookie);
    user.requireRead();
    AiProvider.Plan plan;
    try {
      plan = provider.plan(message, LocalDate.now(clock));
    } catch (RuntimeException ex) {
      throw new ResponseStatusException(
          HttpStatus.BAD_GATEWAY, "Provedor de IA indisponível ou com resposta inválida.");
    }
    if (plan == null || plan.queries() == null || plan.queries().size() > maxTools)
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Plano de consultas inválido ou acima do limite de ferramentas.");
    plan.queries().forEach(tools::validate);
    if (plan.queries().isEmpty())
      return new Answer(
          "Posso consultar saldo atual, entradas e saídas de um mês (AAAA-MM), contas que vencem"
              + " nos próximos sete dias e projeção de caixa de até 90 dias. Reformule sua pergunta"
              + " usando uma dessas opções. Operações de escrita e consultas a outras empresas não"
              + " estão disponíveis.",
          integrationMode,
          providerMode,
          List.of());
    var evidence = new ArrayList<Evidence>();
    for (var request : plan.queries()) {
      if (Thread.currentThread().isInterrupted())
        throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Consulta interrompida.");
      try {
        evidence.add(tools.execute(request, user));
      } catch (ResponseStatusException ex) {
        if (ex.getStatusCode().value() == 401 || ex.getStatusCode().value() == 403) throw ex;
        evidence.add(
            new Evidence(
                request.kind().name(),
                "Período solicitado não confirmado",
                "Consulta sem resultado",
                "Não foi possível obter os dados desta consulta.",
                ex.getReason(),
                "unavailable"));
      }
    }
    return new Answer(
        "Consultas concluídas: "
            + evidence.stream().filter(e -> e.status().equals("ok")).count()
            + " de "
            + evidence.size()
            + ".",
        integrationMode,
        providerMode,
        List.copyOf(evidence));
  }

  @PreDestroy
  public void close() {
    executor.shutdownNow();
  }
}
