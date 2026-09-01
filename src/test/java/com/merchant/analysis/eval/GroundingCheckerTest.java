package com.merchant.analysis.eval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GroundingChecker 误报修复的回归测试：
 * - 千分位数字（10,997）不应判为无出处；
 * - 否定形态（"未出现增长"）不应触发符号不一致；
 * - 行首编号（"1. "）与步骤编号（S3）不应判为无出处数字；
 * - 客单价结论不应拿销售额 delta 判符号（指标归属）；
 * - 单品问题不强制点名店铺级主因。
 */
class GroundingCheckerTest {

    private final GroundingChecker checker = new GroundingChecker();

    private static CheckResultView run(GroundingChecker checker, String conclusion, double metricDelta) {
        Map<String, Map<String, Object>> stepResults = Map.of("S1", Map.of(
                "current_total", 10997.0, "baseline_total", 17370.0, "metric_delta", metricDelta));
        List<GroundingChecker.CheckResult> results =
                checker.check(conclusion, stepResults, false, "query", "sales_amount", null);
        boolean numberOk = results.stream().filter(r -> r.name().equals("number_grounding")).findFirst().orElseThrow().passed();
        boolean signOk = results.stream().filter(r -> r.name().equals("sign_consistency")).findFirst().orElseThrow().passed();
        return new CheckResultView(numberOk, signOk);
    }

    private record CheckResultView(boolean numberOk, boolean signOk) {
    }

    @Test
    void thousandsSeparatorIsNotSplitIntoUnsourcedNumbers() {
        CheckResultView r = run(checker, "上月订单量为10,997单，较基准期17,370单下滑。", -6373.0);
        assertTrue(r.numberOk(), "千分位数字 10,997 / 17,370 应有出处");
    }

    @Test
    void negatedGrowthDoesNotTriggerSignMismatch() {
        CheckResultView r = run(checker, "下架后销售额减少，同品类其他商品未出现增长。", -6373.0);
        assertTrue(r.signOk(), "“未出现增长”是否定形态，不应判为只表述上升");
    }

    @Test
    void listMarkersAreNotTreatedAsBusinessNumbers() {
        CheckResultView r = run(checker, "上月订单量为10997单，较基准期17370单下滑。\n1. 检查库存\n2. 优化页面", -6373.0);
        assertTrue(r.numberOk(), "行首编号 1. / 2. 是列表排版，不应判为无出处数字");
    }

    @Test
    void stepReferenceIsNotTreatedAsBusinessNumber() {
        CheckResultView r = run(checker, "上月订单量为10997单，较基准期17370单下滑。步骤 S3 显示无下架记录。", -6373.0);
        assertTrue(r.numberOk(), "步骤编号 S3 不应被当成业务数字");
    }

    @Test
    void aovConclusionIsJudgedByAovDeltaNotSalesDelta() {
        // 销售额下滑但客单价上升是合法剧情：用户问客单价时，符号应对 aov_delta 而非 metric_delta
        Map<String, Map<String, Object>> stepResults = Map.of("S1", Map.of(
                "current_aov", 25.97, "baseline_aov", 14.28, "aov_delta", 11.68,
                "metric_delta", -99576.87));
        List<GroundingChecker.CheckResult> results = checker.check(
                "本月客单价为25.97元，较基准期14.28元提升11.68元，显著上升。",
                stepResults, false, "本月平均客单价帮我算一下", "aov", null);
        boolean signOk = results.stream().filter(r -> r.name().equals("sign_consistency")).findFirst().orElseThrow().passed();
        assertTrue(signOk, "客单价结论应对 aov_delta 判符号，销售额下滑不应误伤");
    }

    @Test
    void singleSkuQueryDoesNotRequireNamingShopLevelMainCause() {
        Map<String, Map<String, Object>> stepResults = Map.of("S2", Map.of(
                "top_contributors", List.of(Map.of("name", "无糖可乐", "delta", -16793.0))));
        List<GroundingChecker.CheckResult> results = checker.check(
                "上月巧克力销售额下滑，价格上调是影响因素之一。",
                stepResults, false, "上月巧克力销售额为什么没起来", "sales_amount", "巧克力");
        boolean causeOk = results.stream().filter(r -> r.name().equals("coverage_main_cause")).findFirst().orElseThrow().passed();
        assertTrue(causeOk, "单品问题不强制点名店铺级主因");
    }

    @Test
    void numbersFromUserQueryAreNotFlaggedAsUnsourced() {
        Map<String, Map<String, Object>> stepResults = Map.of("S1", Map.of(
                "current_total", 10997.0, "baseline_total", 17370.0, "metric_delta", -6373.0));
        List<GroundingChecker.CheckResult> results = checker.check(
                "上月订单量为10997单，较基准期17370单下滑。满50减10活动两期实际均未执行。",
                stepResults, false, "上个月满50减10活动效果如何", "sales_amount", null);
        boolean numberOk = results.stream().filter(r -> r.name().equals("number_grounding")).findFirst().orElseThrow().passed();
        assertTrue(numberOk, "用户 query 中提到的数字（满50减10）应视为有出处");
    }
}
