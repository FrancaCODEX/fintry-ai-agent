package com.fintry.agent.tools;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fintry.agent.integration.*;
import com.fintry.agent.tools.ToolRequest.Kind;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class FinancialToolsTest {
  static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-07T01:00:00Z"), ZoneId.of("America/Sao_Paulo"));
  final DemoFintryGateway demo = new DemoFintryGateway(CLOCK);
  final FinancialTools tools = new FinancialTools(demo, CLOCK);

  @Test
  void executesDemoWithEvidenceAndBrasiliaDates() {
    var pending = tools.execute(ToolRequest.pending(ToolRequest.DueWindow.NEXT7), demo.authenticate(""));
    assertThat(pending.period()).contains("2026-11-07 a 2026-11-13");
    assertThat(pending.explanation()).contains("2.500,00", "2026-11-08", "Aluguel");
    assertThat(pending.source()).contains("NEXT7&type=EXPENSE");
    var summary =
        tools.execute(ToolRequest.summary("2026-11"), demo.authenticate(""));
    assertThat(summary.explanation()).contains("18.000,00", "8.500,00", "9.500,00");
    var forecast = tools.execute(ToolRequest.forecast(30), demo.authenticate(""));
    assertThat(forecast.explanation()).contains("15.000,00", "10.000,00");
  }

  @Test
  void permissionsCheckedBeforeCallingIntegration() {
    var gateway = mock(FintryGateway.class);
    var secured = new FinancialTools(gateway, CLOCK);
    var user = new UserContext("a", "b", Set.of(), "secret");
    assertThatThrownBy(() -> secured.execute(ToolRequest.of(Kind.BALANCE), user))
        .isInstanceOf(ResponseStatusException.class);
    verifyNoInteractions(gateway);
  }

  @Test
  void demoDoesNotAllowSwitchingCompanies() {
    var other = new UserContext("demo-viewer", "another-company", Set.of("READ_FINANCE"), "");
    assertThatThrownBy(() -> tools.execute(ToolRequest.of(Kind.BALANCE), other))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void rejectsInvalidAndExtraneousArguments() {
    for (var request :
        List.of(
            new ToolRequest(Kind.FORECAST, null, 91, null),
            new ToolRequest(Kind.PENDING, null, 30, null),
            new ToolRequest(Kind.PENDING, null, 7, ToolRequest.DueWindow.NEXT7),
            new ToolRequest(Kind.BALANCE, "2026-11", null, null),
            new ToolRequest(Kind.SUMMARY, "2026-99", null, null),
            new ToolRequest(Kind.SUMMARY, "2027-01", null, null),
            new ToolRequest(Kind.COMPARE, "2024-10", null, null),
            new ToolRequest(Kind.CONCEPTS, null, 1, null),
            new ToolRequest(Kind.FORECAST, "company-b", 7, null)))
      assertThatThrownBy(() -> tools.validate(request))
          .isInstanceOf(FinancialTools.InvalidRequest.class);
  }

  @Test
  void monthWithoutRecordsIsEmptyNotFailureNorZeroValues() {
    var evidence = tools.execute(ToolRequest.summary("2026-03"), demo.authenticate(""));
    assertThat(evidence.status()).isEqualTo("empty");
    assertThat(evidence.explanation()).contains("2026-03").doesNotContain("R$");
  }

  @Test
  void compareShowsBothMonthsFromOneUpstreamResponseWithoutComputingDifferences() {
    var evidence = tools.execute(ToolRequest.compare("2026-11"), demo.authenticate(""));
    assertThat(evidence.status()).isEqualTo("ok");
    assertThat(evidence.period()).contains("2026-10-01 a 2026-11-30");
    assertThat(evidence.explanation())
        .contains("18.000,00", "9.500,00", "15.000,00", "5.200,00", "maior que")
        .doesNotContain("4.300,00"); // 9.500 - 5.200 is never calculated by the Agent
  }

  @Test
  void compareFailsExplicitlyWhenUpstreamOmitsPreviousMonth() {
    var gateway = mock(FintryGateway.class);
    when(gateway.summary(any(), any()))
        .thenReturn(
            new FinancialData.Summary(
                java.math.BigDecimal.TEN, java.math.BigDecimal.ONE, java.math.BigDecimal.ONE, java.math.BigDecimal.ZERO));
    var secured = new FinancialTools(gateway, CLOCK);
    assertThatThrownBy(
            () -> secured.execute(ToolRequest.compare("2026-11"), new UserContext("a", "b", Set.of("READ_FINANCE"), "")))
        .hasMessageContaining("502");
  }

  @Test
  void overdueAndTodayWindowsHaveTheirOwnPeriodsAndEmptyState() {
    var overdue = tools.execute(ToolRequest.pending(ToolRequest.DueWindow.OVERDUE), demo.authenticate(""));
    assertThat(overdue.status()).isEqualTo("ok");
    assertThat(overdue.period()).contains("até 2026-11-05");
    assertThat(overdue.explanation()).contains("2026-11-03", "Internet", "320,00");
    assertThat(overdue.source()).contains("group=OVERDUE");
    var today = tools.execute(ToolRequest.pending(ToolRequest.DueWindow.TODAY), demo.authenticate(""));
    assertThat(today.status()).isEqualTo("empty");
    assertThat(today.period()).contains("2026-11-06");
    assertThat(today.explanation()).isEqualTo("Nenhuma conta a pagar vence hoje.");
  }

  @Test
  void conceptsNeverCallTheGatewayNorContainValues() {
    var gateway = mock(FintryGateway.class);
    var evidence =
        new FinancialTools(gateway, CLOCK)
            .execute(ToolRequest.of(Kind.CONCEPTS), new UserContext("a", "b", Set.of("READ_FINANCE"), ""));
    assertThat(evidence.status()).isEqualTo("info");
    assertThat(evidence.explanation()).contains("não considera as contas a vencer").doesNotContain("R$");
    verifyNoInteractions(gateway);
  }

  @Test
  void forecastHorizonOutsideContractIsAClarificationNotMalformed() {
    assertThatThrownBy(() -> tools.validate(ToolRequest.forecast(120)))
        .isInstanceOfSatisfying(FinancialTools.InvalidRequest.class, ex -> assertThat(ex.clarify()).isTrue());
    assertThatThrownBy(() -> tools.validate(new ToolRequest(Kind.BALANCE, "x", null, null)))
        .isInstanceOfSatisfying(FinancialTools.InvalidRequest.class, ex -> assertThat(ex.clarify()).isFalse());
  }
}
