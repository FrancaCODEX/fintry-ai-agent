package com.fintry.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintry.agent.ai.*;
import com.fintry.agent.integration.*;
import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;

@Configuration
public class AgentConfiguration {
  @Bean
  Clock businessClock() {
    return Clock.system(ZoneId.of("America/Sao_Paulo"));
  }

  @Bean
  FintryGateway fintryGateway(
      @Value("${agent.integration}") String mode,
      @Value("${agent.fintry-base-url}") String url,
      ObjectMapper json,
      Clock clock) {
    return switch (mode) {
      case "demo" -> new DemoFintryGateway(clock);
      case "real" -> new HttpFintryGateway(url, json);
      default -> throw new IllegalArgumentException("FINTRY_MODE deve ser demo ou real");
    };
  }

  @Bean
  AiProvider aiProvider(
      @Value("${agent.provider}") String mode,
      @Value("${agent.ai-api-key}") String key,
      @Value("${agent.ai-model}") String model,
      @Value("${agent.ai-base-url}") String baseUrl) {
    if (mode.equals("simulated")) return new SimulatedAiProvider();
    if (!mode.equals("openai"))
      throw new IllegalArgumentException("AI_PROVIDER deve ser simulated ou openai");
    if (key.isBlank())
      throw new IllegalArgumentException("AI_API_KEY é obrigatória para AI_PROVIDER=openai");
    return SpringAiProvider.openAiCompatible(key, model, baseUrl);
  }
}
