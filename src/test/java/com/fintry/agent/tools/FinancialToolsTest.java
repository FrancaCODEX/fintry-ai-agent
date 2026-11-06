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
    var pending = tools.execute(new ToolRequest(Kind.PENDING, null, 7), demo.authenticate(""));
    assertThat(pending.period()).contains("2026-11-07 a 2026-11-13");
    assertThat(pending.explanation()).contains("2.500,00", "2026-11-08", "Aluguel");
    assertThat(pending.source()).contains("NEXT7&type=EXPENSE");
    var summary =
        tools.execute(new ToolRequest(Kind.SUMMARY, "2026-11", null), demo.authenticate(""));
    assertThat(summary.explanation()).contains("18.000,00", "8.500,00", "9.500,00");
    var forecast = tools.execute(new ToolRequest(Kind.FORECAST, null, 30), demo.authenticate(""));
    assertThat(forecast.explanation()).contains("15.000,00", "10.000,00");
  }

  @Test
  void permissionsCheckedBeforeCallingIntegration() {
    var gateway = mock(FintryGateway.class);
    var secured = new FinancialTools(gateway, CLOCK);
    var user = new UserContext("a", "b", Set.of(), "secret");
    assertThatThrownBy(() -> secured.execute(new ToolRequest(Kind.BALANCE, null, null), user))
        .isInstanceOf(ResponseStatusException.class);
    verifyNoInteractions(gateway);
  }

  @Test
  void demoDoesNotAllowSwitchingCompanies() {
    var other = new UserContext("demo-viewer", "another-company", Set.of("READ_FINANCE"), "");
    assertThatThrownBy(() -> tools.execute(new ToolRequest(Kind.BALANCE, null, null), other))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void rejectsInvalidAndExtraneousArguments() {
    for (var request :
        List.of(
            new ToolRequest(Kind.FORECAST, null, 91),
            new ToolRequest(Kind.PENDING, null, 30),
            new ToolRequest(Kind.BALANCE, "2026-11", null),
            new ToolRequest(Kind.SUMMARY, "2026-99", null),
            new ToolRequest(Kind.SUMMARY, "2027-01", null),
            new ToolRequest(Kind.FORECAST, "company-b", 7)))
      assertThatThrownBy(() -> tools.validate(request)).isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void unavailableMonthIsNotInvented() {
    assertThatThrownBy(
            () ->
                tools.execute(
                    new ToolRequest(Kind.SUMMARY, "2026-10", null), demo.authenticate("")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("mês atual");
  }
}
