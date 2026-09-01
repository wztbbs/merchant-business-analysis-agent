package com.merchant.analysis.plan;

import com.merchant.analysis.understanding.QueryUnderstanding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** plan 结构打印：编织完成后完整输出一次，便于跟踪骨架+因子模块实例化后的步骤编排 */
final class PlanPrinter {

    private static final Logger log = LoggerFactory.getLogger(PlanPrinter.class);

    private PlanPrinter() {
    }

    static void print(Plan plan, QueryUnderstanding understanding) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n========== Plan Generated ==========\n");
        sb.append("scene: ").append(plan.scene())
          .append(" | metric: ").append(understanding.metric())
          .append(" | time_range: ").append(understanding.time_range())
          .append(" | baseline: ").append(understanding.comparison_baseline())
          .append(" | sku: ").append(understanding.skuOrNull())
          .append(" | candidate_factor: ").append(understanding.candidate_factor())
          .append(" | steps: ").append(plan.steps().size()).append('\n');
        for (PlanStep step : plan.steps()) {
            sb.append(String.format("  [%s] %s%n", step.stepId(), step.name()));
            sb.append("      factor:       ").append(step.factor()).append('\n');
            sb.append("      capabilities: ").append(step.capabilities()).append('\n');
            sb.append("      params:       ").append(step.params()).append('\n');
            sb.append("      inputs:       ").append(step.inputs()).append('\n');
            sb.append("      depends_on:   ").append(step.dependsOn()).append('\n');
            sb.append("      outputs:      ").append(step.outputs()).append('\n');
        }
        sb.append("====================================");
        log.info("{}", sb);
    }
}
