package com.fintry.agent.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintry.agent.integration.FinancialData.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Only these fixed GET routes can leave this adapter. No arbitrary paths, SQL or writes. */
public final class HttpFintryGateway implements FintryGateway {
  private final URI base;
  private final ObjectMapper json;
  private final HttpClient http;

  public HttpFintryGateway(String base, ObjectMapper json) {
    this.base = URI.create(base);
    if (!Set.of("http", "https").contains(this.base.getScheme())
        || this.base.getHost() == null
        || this.base.getUserInfo() != null
        || this.base.getQuery() != null
        || this.base.getFragment() != null
        || !Set.of("", "/").contains(this.base.getPath()))
      throw new IllegalArgumentException(
          "FINTRY_BASE_URL deve ser uma origem HTTP(S) sem caminho ou credenciais.");
    this.json = json;
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  private ResponseStatusException unavailable() {
    return new ResponseStatusException(
        HttpStatus.BAD_GATEWAY,
        "Fintry indisponível ou com resposta incompatível. Nenhum valor foi estimado.");
  }

  private JsonNode get(String path, String cookie) {
    if (cookie == null || !cookie.matches("JSESSIONID=[A-Za-z0-9._-]{1,256}"))
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Entre no Fintry para continuar.");
    if (Thread.currentThread().isInterrupted())
      throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Consulta interrompida.");
    var request =
        HttpRequest.newBuilder(base.resolve(path))
            .timeout(Duration.ofSeconds(5))
            .header("Cookie", cookie)
            .header("Accept", "application/json")
            .GET()
            .build();
    try {
      // The byte-array subscriber is capped during transport, not after allocating the full body.
      var response = http.send(request, info -> new LimitedBodySubscriber(1_048_576));
      if (response.statusCode() == 401 || response.statusCode() == 403)
        throw new ResponseStatusException(
            HttpStatus.valueOf(response.statusCode()), "Sessão ou permissão do Fintry inválida.");
      if (response.statusCode() != 200) throw unavailable();
      return json.readTree(response.body());
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Consulta interrompida.");
    } catch (IOException | IllegalArgumentException ex) {
      throw unavailable();
    }
  }

  private String text(JsonNode node, String field) {
    var value = node.get(field);
    if (value == null || !value.isTextual() || value.asText().isBlank()) throw unavailable();
    return value.asText();
  }

  private java.math.BigDecimal money(JsonNode node, String field) {
    var value = node.get(field);
    if (value == null || !value.isNumber()) throw unavailable();
    return value.decimalValue();
  }

  public UserContext authenticate(String cookie) {
    var me = get("/api/v1/auth/me", cookie);
    try {
      var id = UUID.fromString(text(me, "id")).toString();
      var company = UUID.fromString(text(me, "companyId")).toString();
      if (!Set.of("ADMIN", "FINANCE", "VIEWER").contains(text(me, "role"))) throw unavailable();
      var values = me.get("permissions");
      if (values == null || !values.isArray()) throw unavailable();
      Set<String> permissions = new HashSet<>();
      values.forEach(
          value -> {
            if (!value.isTextual()) throw unavailable();
            permissions.add(value.asText());
          });
      var user = new UserContext(id, company, permissions, cookie);
      user.requireRead();
      return user;
    } catch (IllegalArgumentException ex) {
      throw unavailable();
    }
  }

  private void verify(UserContext user) {
    user.requireRead();
    var fresh = authenticate(user.sessionCookie());
    if (!fresh.userId().equals(user.userId()) || !fresh.companyId().equals(user.companyId()))
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "A identidade mudou. Inicie outra consulta.");
  }

  public Summary summary(UserContext user, YearMonth month) {
    verify(user);
    var data = get("/api/v1/finance/summary?month=" + month, user.sessionCookie());
    return new Summary(
        money(data, "balance"),
        money(data, "income"),
        money(data, "expense"),
        money(data, "result"));
  }

  public Pending pending(UserContext user) {
    verify(user);
    var data =
        get(
            "/api/v1/finance/pending?group=NEXT7&type=EXPENSE&page=0&size=20",
            user.sessionCookie());
    try {
      var items = data.get("content");
      if (items == null
          || !items.isArray()
          || items.size() > 20
          || !data.path("totalElements").isIntegralNumber()) throw unavailable();
      var out = new ArrayList<PendingItem>();
      for (var item : items)
        out.add(
            new PendingItem(
                text(item, "description"),
                LocalDate.parse(text(item, "dueDate")),
                money(item, "remainingAmount")));
      return new Pending(
          LocalDate.parse(text(data, "today")),
          List.copyOf(out),
          data.get("totalElements").longValue(),
          money(data.path("totals"), "expense"));
    } catch (DateTimeException ex) {
      throw unavailable();
    }
  }

  public Forecast forecast(UserContext user, int days) {
    verify(user);
    if (days < 1 || days > 90) throw new IllegalArgumentException("Horizonte inválido");
    var data =
        get(
            "/api/v1/reports/forecast?days=" + days + "&includeOverdue=false",
            user.sessionCookie());
    try {
      return new Forecast(
          LocalDate.parse(text(data, "start")),
          LocalDate.parse(text(data, "end")),
          money(data, "openingBalance"),
          money(data, "closingBalance"),
          money(data, "pendingIncome"),
          money(data, "pendingExpense"),
          money(data, "minimumBalance"),
          data.path("firstNegativeDate").isNull()
              ? null
              : LocalDate.parse(text(data, "firstNegativeDate")));
    } catch (DateTimeException ex) {
      throw unavailable();
    }
  }
}
