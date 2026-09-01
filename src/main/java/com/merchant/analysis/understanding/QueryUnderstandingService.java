package com.merchant.analysis.understanding;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.merchant.analysis.exception.LlmException;
import com.merchant.analysis.llm.LlmClient;
import com.merchant.analysis.llm.LlmJson;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 意图识别：渲染 query_understanding prompt → LLM → 解析为 {@link UnderstandingResult}。
 *
 * 追问判定合并在这一次调用里：prompt 注入候选 run chain 的链尾上下文（chain 尾 run 的
 * understanding JSON + 问题原文），LLM 一次输出「是否追问 + 承接哪个 run + 是否需重跑 +
 * 组装后的 understanding」。无会话上下文时 chains 为空，退化为纯意图识别（eval 走这条路）。
 */
@Service
public class QueryUnderstandingService {

    /** QueryUnderstanding 只映射意图字段；is_follow_up 等追问字段从 JSON 上单独读取 */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final LlmClient llm;
    private final String promptTemplate;
    /** prompt 内容哈希前 8 位：eval_run.prompt_version 的来源，覆盖未提交的调优中间态 */
    private final String promptVersion;

    public QueryUnderstandingService(LlmClient llm,
                                     @Value("classpath:prompts/query_understanding.txt") Resource promptResource) {
        this.llm = llm;
        try {
            this.promptTemplate = promptResource.getContentAsString(StandardCharsets.UTF_8);
            this.promptVersion = DigestUtils.md5DigestAsHex(this.promptTemplate.getBytes(StandardCharsets.UTF_8))
                    .substring(0, 8);
        } catch (IOException e) {
            throw new IllegalStateException("加载 query_understanding prompt 失败", e);
        }
    }

    public String promptVersion() {
        return promptVersion;
    }

    /** 纯意图识别（无会话上下文）：eval 与独立问题走这里 */
    public UnderstandingResult understand(String query) {
        return understand(query, List.of());
    }

    public UnderstandingResult understand(String query, List<ChainContext> chains) {
        String prompt = promptTemplate
                .replace("{{query}}", query)
                .replace("{{follow_up_section}}", renderChainSection(chains));
        JsonNode node = LlmJson.parse(llm.chat(prompt));
        try {
            QueryUnderstanding u = MAPPER.treeToValue(node, QueryUnderstanding.class);
            if (u == null || u.primaryScene() == null) {
                throw new LlmException("意图识别结果缺少 scene.primary_scene: " + node);
            }
            boolean followUp = node.path("is_follow_up").asBoolean(false);
            String refRunId = node.path("ref_run_id").isTextual() ? node.path("ref_run_id").asText() : null;
            String followUpType = node.path("follow_up_type").isTextual()
                    ? node.path("follow_up_type").asText() : null;
            boolean needsRerun = !node.has("needs_rerun") || node.path("needs_rerun").asBoolean(true);
            if (!followUp) {
                return UnderstandingResult.fresh(u);
            }
            return new UnderstandingResult(u, true, followUpType, refRunId, needsRerun);
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("意图识别 JSON 映射失败: " + node, e);
        }
    }

    /** 候选链尾上下文注入 prompt；无候选时输出占位说明（保持 prompt 结构稳定）。每链一行，JSON 压缩去换行 */
    private static String renderChainSection(List<ChainContext> chains) {
        if (chains == null || chains.isEmpty()) {
            return "会话上下文：无（本会话还没有历史分析）";
        }
        StringBuilder sb = new StringBuilder("会话上下文（本会话最近的相关分析，供追问判定）：\n<CHAIN_CONTEXT>\n");
        for (ChainContext c : chains) {
            sb.append("{\"run_id\": \"").append(c.runId())
                    .append("\", \"query\": ").append(quote(c.query()))
                    .append(", \"understanding\": ").append(compact(c.understandingJson())).append("}\n");
        }
        return sb.append("</CHAIN_CONTEXT>").toString();
    }

    /** JSON 压缩：去掉格式化换行/缩进，保证每链一行（JSON 字符串值内的换行本就是转义形式，不受影响） */
    private static String compact(String json) {
        return json == null ? "null" : json.replaceAll("\\s*\n\\s*", " ").trim();
    }

    private static String quote(String s) {
        if (s == null) {
            return "null";
        }
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
