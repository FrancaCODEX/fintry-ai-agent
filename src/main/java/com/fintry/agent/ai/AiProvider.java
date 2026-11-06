package com.fintry.agent.ai;

import com.fintry.agent.tools.ToolRequest;
import java.time.LocalDate;
import java.util.List;

/** A model proposes a plan. It never gets identity, credentials or financial payloads. */
public interface AiProvider {
  record Plan(List<ToolRequest> queries) {}

  Plan plan(String message, LocalDate today);
}
