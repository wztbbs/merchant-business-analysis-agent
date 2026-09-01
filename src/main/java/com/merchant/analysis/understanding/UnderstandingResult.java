package com.merchant.analysis.understanding;

/**
 * 意图识别 + 追问判定的统一结果（一次 LLM 调用产出）。
 *
 * @param understanding 组装后的意图（追问时已继承所承接 run 的槽位、只覆盖变化部分）
 * @param followUp      是否追问
 * @param followUpType  metric_switch / time_compare / factor_detail / advice / explain
 * @param refRunId      追问承接的 run（非追问为 null）
 * @param needsRerun    true=需要重新分析（切换指标/时间等）；false=引用该 run 已有结果即可回答
 */
public record UnderstandingResult(QueryUnderstanding understanding, boolean followUp,
                                  String followUpType, String refRunId, boolean needsRerun) {

    public static UnderstandingResult fresh(QueryUnderstanding understanding) {
        return new UnderstandingResult(understanding, false, null, null, true);
    }
}
