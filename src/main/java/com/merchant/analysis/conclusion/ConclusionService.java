package com.merchant.analysis.conclusion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.merchant.analysis.execution.AnalysisContext;
import com.merchant.analysis.exception.LlmException;
import com.merchant.analysis.llm.LlmClient;
import com.merchant.analysis.plan.Plan;
import com.merchant.analysis.plan.PlanStep;
import com.merchant.analysis.playbook.PlaybookLoader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 结论生成：把 playbook 因子文档 + 各步骤真实查询结果（JSON）塞进 conclusion prompt 调 LLM。
 * 另承载追问回答：读历史型追问（factor_detail/advice/explain）不新跑 plan，
 * 用上次 run 的结论与步骤数据调 followup_answer prompt 生成回答。
 */
@Service
public class ConclusionService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LlmClient llm;
    private final PlaybookLoader playbookLoader;
    private final String promptTemplate;
    private final String followUpPromptTemplate;
    /** prompt 内容哈希前 8 位：grounding 评测的 eval_run.prompt_version 来源 */
    private final String promptVersion;

    public ConclusionService(LlmClient llm, PlaybookLoader playbookLoader,
                             @Value("classpath:prompts/conclusion.txt") Resource promptResource,
                             @Value("classpath:prompts/followup_answer.txt") Resource followUpPromptResource) {
        this.llm = llm;
        this.playbookLoader = playbookLoader;
        try {
            this.promptTemplate = promptResource.getContentAsString(StandardCharsets.UTF_8);
            this.followUpPromptTemplate = followUpPromptResource.getContentAsString(StandardCharsets.UTF_8);
            this.promptVersion = DigestUtils.md5DigestAsHex(this.promptTemplate.getBytes(StandardCharsets.UTF_8))
                    .substring(0, 8);
        } catch (IOException e) {
            throw new IllegalStateException("加载 conclusion prompt 失败", e);
        }
    }

    public String promptVersion() {
        return promptVersion;
    }

    public String conclude(String query, Plan plan, AnalysisContext ctx) {
        String prompt = promptTemplate
                .replace("{{query}}", query)
                .replace("{{output_requirement}}", outputRequirement(plan, ctx))
                .replace("{{factor_docs}}", factorDocs(plan))
                .replace("{{step_results}}", stepResultsJson(ctx));
        return llm.chat(prompt);
    }

    /**
     * 输出结构按场景与证伪状态分流：建议段只在有归因依据时存在——
     * 报表查询没有排查数据、假设证伪后建议无的放矢，这两类都不输出建议段；
     * 诊断类场景的建议必须挂在具体原因的量化结果上，不允许泛泛的通用话术。
     */
    private static String outputRequirement(Plan plan, AnalysisContext ctx) {
        if ("metric_query_report".equals(plan.scene())) {
            return "1. 本问题是指标查询：只输出「结论」和「数据支撑」两部分，直接回答数值与对比，"
                    + "不要输出可执行建议，不要展开经营动作；";
        }
        if (ctx.hypothesisBroken()) {
            return "1. 用户假设已被数据证伪：输出「结论」（明确说明假设不成立）和「数据支撑」两部分，"
                    + "不要输出经营建议；结尾可用一句话说明可以换个方向继续分析；";
        }
        return "1. 先给结论，再给数据支撑，最后给可执行建议；每条建议必须对应某个已排查原因的量化结果"
                + "（说明是哪个原因、影响多大），不要给泛泛的通用建议；";
    }

    /** 读历史型追问：不新跑 plan，用上次 run 的结论与步骤产出回答 */
    public String answerFollowUp(String query, String followUpType, String prevQuery, String prevConclusion,
                                 Map<String, Map<String, Object>> stepResults) {
        String prompt = followUpPromptTemplate
                .replace("{{query}}", query)
                .replace("{{follow_up_type}}", followUpType)
                .replace("{{prev_query}}", prevQuery)
                .replace("{{prev_conclusion}}", prevConclusion == null ? "" : prevConclusion)
                .replace("{{step_results}}", toJson(stepResults));
        return llm.chat(prompt);
    }

    /** 拼接本 plan 实际挂载的因子模块的分析思路文档 */
    private String factorDocs(Plan plan) {
        Set<String> factorNames = new LinkedHashSet<>();
        for (PlanStep step : plan.steps()) {
            if (step.factor() != null) {
                factorNames.add(step.factor());
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String name : factorNames) {
            sb.append(playbookLoader.factor(name).thinkingDoc()).append('\n');
        }
        return sb.isEmpty() ? "（本场景无因子模块文档）" : sb.toString();
    }

    /** step_results JSON：各步骤产出 + _meta（假设是否被证伪等执行期标记） */
    private String stepResultsJson(AnalysisContext ctx) {
        Map<String, Object> payload = new LinkedHashMap<>(ctx.stepResults());
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("hypothesis_broken", ctx.hypothesisBroken());
        meta.put("hypothesis_note", ctx.hypothesisNote());
        payload.put("_meta", meta);
        return toJson(payload);
    }

    private static String toJson(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            throw new LlmException("step_results 序列化失败", e);
        }
    }
}
