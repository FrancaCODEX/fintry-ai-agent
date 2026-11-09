package com.fintry.agent.integration;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;

class HttpFintryGatewayTest {
  HttpServer server;
  HttpFintryGateway gateway;
  final List<String> requests = new CopyOnWriteArrayList<>();
  final AtomicInteger meCalls = new AtomicInteger();
  int status = 200;
  String financialBody = "{\"balance\":12500,\"income\":18000,\"expense\":8500,\"result\":9500}";
  String permissions = "[\"READ_FINANCE\"]";
  boolean changeCompany;
  static final String COMPANY_A = "11111111-1111-1111-1111-111111111111";
  static final String COMPANY_B = "22222222-2222-2222-2222-222222222222";

  @BeforeEach
  void setup() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          var uri = exchange.getRequestURI().toString();
          requests.add(exchange.getRequestMethod() + " " + uri);
          String body;
          int responseStatus = status;
          if (uri.equals("/api/v1/auth/me")) {
            int call = meCalls.incrementAndGet();
            String company =
                "JSESSIONID=b".equals(exchange.getRequestHeaders().getFirst("Cookie"))
                        || changeCompany && call > 1
                    ? COMPANY_B
                    : COMPANY_A;
            body =
                "{\"id\":\"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\",\"companyId\":\""
                    + company
                    + "\",\"role\":\"VIEWER\",\"permissions\":"
                    + permissions
                    + "}";
          } else {
            body = financialBody;
            if ("JSESSIONID=b".equals(exchange.getRequestHeaders().getFirst("Cookie")))
              body = "{\"balance\":42,\"income\":12,\"expense\":2,\"result\":10}";
          }
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(responseStatus, bytes.length);
          try (var out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    server.start();
    gateway =
        new HttpFintryGateway(
            "http://127.0.0.1:" + server.getAddress().getPort(), new ObjectMapper());
  }

  @AfterEach
  void cleanup() {
    if (server != null) server.stop(0);
  }

  @Test
  void authenticatesAndReadsOnlyVerifiedRoutes() {
    var user = gateway.authenticate("JSESSIONID=a");
    var result = gateway.summary(user, YearMonth.of(2026, 11));
    assertThat(result.balance()).isEqualByComparingTo("12500");
    assertThat(requests)
        .containsExactly(
            "GET /api/v1/auth/me",
            "GET /api/v1/auth/me",
            "GET /api/v1/finance/summary?month=2026-11");
    assertThat(user.toString()).doesNotContain("JSESSIONID", COMPANY_A);
  }

  @Test
  void concurrentUsersNeverShareSessionOrCompany() throws Exception {
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var a =
          executor.submit(
              () -> gateway.summary(gateway.authenticate("JSESSIONID=a"), YearMonth.of(2026, 11)));
      var b =
          executor.submit(
              () -> gateway.summary(gateway.authenticate("JSESSIONID=b"), YearMonth.of(2026, 11)));
      assertThat(a.get().balance()).isEqualByComparingTo("12500");
      assertThat(b.get().balance()).isEqualByComparingTo("42");
    }
  }

  @Test
  void fabricatedCompanyIsRejectedBeforeFinanceQuery() {
    var fake =
        new UserContext(
            "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
            COMPANY_B,
            Set.of("READ_FINANCE"),
            "JSESSIONID=a");
    assertThatThrownBy(() -> gateway.summary(fake, YearMonth.of(2026, 11)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("403");
    assertThat(requests).containsExactly("GET /api/v1/auth/me");
  }

  @Test
  void companyChangedDuringRequestFailsClosed() {
    var user = gateway.authenticate("JSESSIONID=a");
    changeCompany = true;
    assertThatThrownBy(() -> gateway.summary(user, YearMonth.of(2026, 11)))
        .hasMessageContaining("403");
    assertThat(requests).allMatch(r -> r.equals("GET /api/v1/auth/me"));
  }

  @Test
  void revokedPermissionIsCheckedAgainBeforeEachTool() {
    var user = gateway.authenticate("JSESSIONID=a");
    permissions = "[]";
    assertThatThrownBy(() -> gateway.summary(user, YearMonth.of(2026, 11)))
        .hasMessageContaining("403");
  }

  @Test
  void expiredSessionIsNotReplacedWithDemo() {
    status = 401;
    assertThatThrownBy(() -> gateway.authenticate("JSESSIONID=a")).hasMessageContaining("401");
  }

  @Test
  void missingCookieIsRejectedWithoutNetwork() {
    assertThatThrownBy(() -> gateway.authenticate("")).hasMessageContaining("401");
    assertThat(requests).isEmpty();
  }

  @Test
  void integrationFailureIsSanitized() {
    var user = gateway.authenticate("JSESSIONID=a");
    status = 500;
    financialBody = "secret-stacktrace";
    assertThatThrownBy(() -> gateway.summary(user, YearMonth.of(2026, 11)))
        .hasMessageContaining("502")
        .hasMessageNotContaining("secret-stacktrace");
  }

  @Test
  void missingFinancialValuesAreNeverTreatedAsZero() {
    var user = gateway.authenticate("JSESSIONID=a");
    financialBody = "{\"balance\":null}";
    assertThatThrownBy(() -> gateway.summary(user, YearMonth.of(2026, 11)))
        .hasMessageContaining("502");
  }

  @Test
  void oversizedResponseIsRejected() {
    var user = gateway.authenticate("JSESSIONID=a");
    financialBody = "a".repeat(1_048_577);
    assertThatThrownBy(() -> gateway.summary(user, YearMonth.of(2026, 11)))
        .hasMessageContaining("502");
  }

  @Test
  void pendingPreservesServerTotalsAndMarksPaginationViaCount() {
    var user = gateway.authenticate("JSESSIONID=a");
    financialBody =
        """
        {"today":"2026-11-06","group":"NEXT7","totalElements":21,"totals":{"expense":2500},
         "content":[{"description":"Aluguel","dueDate":"2026-11-08","remainingAmount":1800}]}
        """;
    var result = gateway.pending(user, com.fintry.agent.tools.ToolRequest.DueWindow.NEXT7);
    assertThat(result.items()).hasSize(1);
    assertThat(result.totalElements()).isEqualTo(21);
    assertThat(result.expense()).isEqualByComparingTo("2500");
    assertThat(requests.getLast())
        .isEqualTo("GET /api/v1/finance/pending?group=NEXT7&type=EXPENSE&page=0&size=20");
  }

  @Test
  void forecastIsReadFromExistingGetApiWithoutRecalculation() {
    var user = gateway.authenticate("JSESSIONID=a");
    financialBody =
        """
        {"start":"2026-11-06","end":"2026-12-06","openingBalance":12500,"closingBalance":15000,
         "pendingIncome":5000,"pendingExpense":2500,"minimumBalance":10000,"firstNegativeDate":null}
        """;
    var result = gateway.forecast(user, 30);
    assertThat(result.closingBalance()).isEqualByComparingTo("15000");
    assertThat(requests.getLast())
        .isEqualTo("GET /api/v1/reports/forecast?days=30&includeOverdue=false");
  }

  @Test
  void summaryReadsPreviousMonthFieldsWhenPresent() {
    var user = gateway.authenticate("JSESSIONID=a");
    financialBody =
        "{\"balance\":1,\"income\":2,\"expense\":3,\"result\":-1,\"previousIncome\":5,"
            + "\"previousExpense\":4,\"previousResult\":1}";
    var result = gateway.summary(user, YearMonth.of(2026, 11));
    assertThat(result.hasPrevious()).isTrue();
    assertThat(result.previousResult()).isEqualByComparingTo("1");
    financialBody = "{\"balance\":1,\"income\":2,\"expense\":3,\"result\":-1,\"previousIncome\":\"5\"}";
    assertThatThrownBy(() -> gateway.summary(user, YearMonth.of(2026, 11))).hasMessageContaining("502");
  }

  @Test
  void pendingUsesRequestedWindowAndRejectsAMismatchedGroup() {
    var user = gateway.authenticate("JSESSIONID=a");
    financialBody =
        """
        {"today":"2026-11-06","group":"OVERDUE","totalElements":0,"totals":{"expense":0},"content":[]}
        """;
    var overdue = gateway.pending(user, com.fintry.agent.tools.ToolRequest.DueWindow.OVERDUE);
    assertThat(overdue.totalElements()).isZero();
    assertThat(requests.getLast())
        .isEqualTo("GET /api/v1/finance/pending?group=OVERDUE&type=EXPENSE&page=0&size=20");
    assertThatThrownBy(() -> gateway.pending(user, com.fintry.agent.tools.ToolRequest.DueWindow.TODAY))
        .hasMessageContaining("502");
  }
}
