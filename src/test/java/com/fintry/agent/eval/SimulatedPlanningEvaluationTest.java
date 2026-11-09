package com.fintry.agent.eval;

import static org.assertj.core.api.Assertions.*;

import com.fintry.agent.ai.SimulatedAiProvider;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

/** Runs on every build: no network, no paid API. */
class SimulatedPlanningEvaluationTest {
  @Test
  void suiteIsWellFormedAndCoversRequiredCategories() {
    var suite = PlanningEvaluation.load();
    var ids = new HashSet<String>();
    assertThat(suite.cases()).allSatisfy(c -> assertThat(ids.add(c.id())).as(c.id()).isTrue());
    assertThat(suite.cases())
        .extracting(PlanningEvaluation.Case::category)
        .contains(
            "basic", "period", "ambiguity", "follow-up", "no-data", "out-of-scope", "write",
            "other-company", "injection");
  }

  @Test
  void simulatedProviderPassesEveryCase() {
    var suite = PlanningEvaluation.load();
    var results = PlanningEvaluation.run(suite, new SimulatedAiProvider(), "simulated");
    var report = PlanningEvaluation.report(results);
    System.out.print(report);
    assertThat(results).as(report).allMatch(PlanningEvaluation.Result::passed);
  }
}
