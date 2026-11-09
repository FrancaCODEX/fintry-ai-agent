package com.fintry.agent.ai;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fintry.agent.tools.ToolRequest.Kind;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;

class SpringAiProviderTest {
  @Test
  void convertsModelOutputToTypedPlan() {
    ChatModel model = mock(ChatModel.class);
    when(model.call(any(Prompt.class)))
        .thenReturn(
            new ChatResponse(
                List.of(
                    new Generation(
                        new AssistantMessage(
                            "{\"decision\":\"QUERY\",\"queries\":[{\"kind\":\"SUMMARY\",\"month\":\"2026-11\",\"days\":null,\"window\":null}]}")))));
    var provider = new SpringAiProvider(ChatClient.create(model));
    var plan = provider.plan("Quanto entrou e saiu neste mês?", LocalDate.of(2026, 11, 6), List.of());
    assertThat(plan.queries())
        .singleElement()
        .satisfies(q -> assertThat(q.kind()).isEqualTo(Kind.SUMMARY));
    var prompt = ArgumentCaptor.forClass(Prompt.class);
    verify(model).call(prompt.capture());
    assertThat(prompt.getValue().getContents())
        .contains("2026-11-06", "SOMENTE LEITURA")
        .doesNotContain("JSESSIONID", "demo-company", "12500");
  }

  @Test
  void unknownToolCannotBecomeExecutable() {
    ChatModel model = mock(ChatModel.class);
    when(model.call(any(Prompt.class)))
        .thenReturn(
            new ChatResponse(
                List.of(
                    new Generation(
                        new AssistantMessage("{\"decision\":\"QUERY\",\"queries\":[{\"kind\":\"PAY_ENTRY\"}]}")))));
    var provider = new SpringAiProvider(ChatClient.create(model));
    assertThatThrownBy(() -> provider.plan("Pague", LocalDate.now(), List.of()))
        .isInstanceOf(RuntimeException.class);
  }
}
