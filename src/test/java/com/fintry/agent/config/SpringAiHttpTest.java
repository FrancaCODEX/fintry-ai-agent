package com.fintry.agent.config;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintry.agent.tools.ToolRequest.Kind;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SpringAiHttpTest {
  @Test
  void configuredRealProviderCallsChatCompletionsWithBoundedRequest() throws Exception {
    var captured = new AtomicReference<String>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          captured.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          String content = "{\"queries\":[{\"kind\":\"BALANCE\",\"month\":null,\"days\":null}]}";
          var json = new ObjectMapper();
          String body =
              "{\"id\":\"test\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"contract-model\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":"
                  + json.writeValueAsString(content)
                  + "},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":10,\"total_tokens\":20}}";
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, bytes.length);
          try (var out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    server.start();
    try {
      var provider =
          new AgentConfiguration()
              .aiProvider(
                  "openai",
                  "contract-test-placeholder",
                  "contract-model",
                  "http://127.0.0.1:" + server.getAddress().getPort());
      var plan = provider.plan("Qual é meu saldo disponível?", LocalDate.of(2026, 11, 6));
      assertThat(plan.queries())
          .singleElement()
          .satisfies(q -> assertThat(q.kind()).isEqualTo(Kind.BALANCE));
      var request = new ObjectMapper().readTree(captured.get());
      assertThat(request.path("max_tokens").asInt()).isEqualTo(600);
      assertThat(request.path("store").asBoolean()).isFalse();
      assertThat(captured.get())
          .contains("2026-11-06")
          .doesNotContain("JSESSIONID", "READ_FINANCE", "12500");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void requiresExplicitValidProviderAndCredential() {
    var config = new AgentConfiguration();
    assertThatThrownBy(() -> config.aiProvider("openai", "", "model", "https://api.openai.com"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> config.aiProvider("typo", "", "model", "https://api.openai.com"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
