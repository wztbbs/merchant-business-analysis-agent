package com.merchant.analysis.playbook;

import java.util.List;

/**
 * 因子模块：一组可插拔的步骤模板 + 配套的中文分析思路/结论文档（供结论生成阶段拼 prompt）。
 * falsification：证伪规则，null 表示该因子不可证伪；仅当本因子是用户假设（candidate_factor）时生效。
 */
public record FactorModule(
        String factor,
        List<StepTemplate> steps,
        String thinkingDoc,
        Falsification falsification) {

    /**
     * 证伪规则：本因子步骤产出中 output 字段为 null 即判定用户假设不成立，
     * 执行期跳过本因子后续步骤，note 写入结论输入的 hypothesis_note。
     */
    public record Falsification(String output, String note) {
    }
}
