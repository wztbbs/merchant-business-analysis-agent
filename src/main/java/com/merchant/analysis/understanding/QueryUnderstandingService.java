package com.merchant.analysis.understanding;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.merchant.analysis.exception.LlmException;
import com.merchant.analysis.execution.CapabilityCatalog;
import com.merchant.analysis.llm.LlmClient;
import com.merchant.analysis.llm.LlmJson;
import com.merchant.analysis.playbook.PlaybookLoader;
import com.merchant.analysis.scene.ReactSceneCatalog;
import com.merchant.analysis.time.TimeContext;
import com.merchant.analysis.time.TimeResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 一次模型调用完成改写、领域判断、子需求提取与匹配提议，代码确定最终路由。 */
@Service
public class QueryUnderstandingService {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final Set<String> FOLLOW_UP_TYPES = Set.of("metric_switch", "time_compare", "factor_detail", "advice", "explain", "result_reference");
    private final LlmClient llm;
    private final String promptTemplate;
    private final String promptVersion;
    private final TimeResolver timeResolver;
    private final ReactSceneCatalog scenes;
    private final PlaybookMatcher playbooks;

    public QueryUnderstandingService(LlmClient llm, Resource resource) { this(llm, resource, new TimeResolver()); }
    public QueryUnderstandingService(LlmClient llm, Resource resource, TimeResolver timeResolver) {
        this(llm, resource, timeResolver, new ReactSceneCatalog(new CapabilityCatalog()),
                new PlaybookMatcher(new PlaybookLoader(new CapabilityCatalog())));
    }
    @Autowired
    public QueryUnderstandingService(LlmClient llm, @Value("classpath:prompts/query_understanding.txt") Resource resource,
                                     TimeResolver timeResolver, ReactSceneCatalog scenes, PlaybookMatcher playbooks) {
        this.llm = llm;
        this.timeResolver = timeResolver;
        this.scenes = scenes;
        this.playbooks = playbooks;
        try {
            promptTemplate = resource.getContentAsString(StandardCharsets.UTF_8);
            promptVersion = DigestUtils.md5DigestAsHex((promptTemplate + json(scenes.summaries()) + json(playbooks.summaries()))
                    .getBytes(StandardCharsets.UTF_8)).substring(0, 8);
        } catch (Exception e) { throw new IllegalStateException("意图理解配置加载失败", e); }
    }
    public String promptVersion() { return promptVersion; }
    public UnderstandingResult understand(String query) { return understand(query, List.of()); }

    public UnderstandingResult understand(String query, List<ChainContext> chains) {
        TimeContext requestTime = timeResolver.capture();
        String prompt = promptTemplate
                .replace("{{time_context}}", "业务日期=" + requestTime.reference_date() + "，日数据截至="
                        + requestTime.data_through() + "，时区=" + requestTime.timezone())
                .replace("{{playbook_catalog}}", json(playbooks.summaries()))
                .replace("{{scene_catalog}}", json(scenes.summaries()))
                .replace("{{follow_up_section}}", renderChainSection(chains))
                .replace("{{query}}", query);
        JsonNode node = LlmJson.parse(llm.chat(prompt));
        try {
            if (!(node instanceof ObjectNode object) || !node.path("task").path("in_domain").isBoolean())
                throw new LlmException("意图理解缺少 task.in_domain 或合法任务对象");
            // 模型不能提供实际区间或最终执行路由。
            object.remove(List.of("resolved_time", "routing"));
            QueryUnderstanding u = MAPPER.treeToValue(object, QueryUnderstanding.class);
            QueryTask task = u.task().withOriginal(query);
            boolean followUp = node.path("is_follow_up").asBoolean(false);
            String refRun = text(node, "ref_run_id");
            String followType = text(node, "follow_up_type");
            boolean rerun = !node.has("needs_rerun") || node.path("needs_rerun").asBoolean(true);
            ChainContext anchor = null;
            if (followUp) {
                for (var chain : chains) if (chain.runId().equals(refRun)) { anchor = chain; break; }
                if (anchor == null) {
                    task = task.clarify("没有找到可承接的历史分析，请明确要查询的商品、指标和时间。");
                    followUp = false;
                    refRun = null;
                    followType = null;
                    rerun = true;
                } else if (followType != null && !FOLLOW_UP_TYPES.contains(followType)) {
                    throw new LlmException("未知追问类型: " + followType);
                }
            } else { refRun = null; followType = null; rerun = true; }
            for (var reference : task.fact_references()) {
                try { HistoryFacts.validate(reference, chains); }
                catch (IllegalArgumentException e) { task = task.clarify("无法定位所指的历史结果，请明确要分析的商品或结果集合。"); }
            }
            boolean groupPronoun = query.matches("(?s).*(这几个|那几个|这些商品|那些商品|它们|刚才那些).*");
            boolean explicitEntities = task.requirements().stream().anyMatch(r -> !r.entities().isEmpty()
                    && r.entities().stream().allMatch(query::contains));
            if (groupPronoun && task.fact_references().isEmpty() && !explicitEntities)
                task = task.clarify("你指的是哪些商品？请指定商品，或明确要引用的上次结果。");
            if (!task.fact_references().isEmpty() && !followUp)
                task = task.clarify("请明确要承接哪一次历史分析。");

            QueryUnderstanding previous = anchor == null ? null : MAPPER.readValue(anchor.understandingJson(), QueryUnderstanding.class);
            if (task.in_domain() && !task.needs_clarification()) {
                try {
                    if (previous != null && previous.resolved_time() != null && !"time_compare".equals(followType)
                            && Objects.equals(previous.time_range(), u.time_range())
                            && Objects.equals(previous.comparison_baseline(), u.comparison_baseline())) {
                        u = u.withResolvedTime(previous.resolved_time());
                    } else u = u.withResolvedTime(timeResolver.resolvePeriods(u.time_range(), u.comparison_baseline(), requestTime));
                    for (var requirement : task.requirements()) {
                        if (requirement.time_range() != null || requirement.comparison_baseline() != null)
                            timeResolver.resolvePeriods(requirement.time_range() == null ? u.time_range() : requirement.time_range(),
                                    requirement.comparison_baseline() == null ? u.comparison_baseline() : requirement.comparison_baseline(),
                                    u.resolved_time().context());
                    }
                } catch (RuntimeException e) {
                    task = task.clarify("时间范围没有可用日数据或超出支持范围，请调整日期。" + e.getMessage());
                }
            }
            if (followUp && ("metric_switch".equals(followType) || "time_compare".equals(followType)
                    || previous == null || !Objects.equals(previous.metric(), u.metric())
                    || !Objects.equals(previous.time_range(), u.time_range())
                    || !Objects.equals(previous.comparison_baseline(), u.comparison_baseline())
                    || !Objects.equals(previous.entity(), u.entity())
                    || !Objects.equals(previous.candidate_factor(), u.candidate_factor()))) rerun = true;
            if (followUp && !Set.of("factor_detail", "advice", "explain").contains(followType == null ? "" : followType)) rerun = true;

            if (followUp && task.requirements().stream().anyMatch(r -> !Set.of("advice", "explain").contains(r.action()))) rerun = true;

            RoutingDecision route;
            if (!task.in_domain()) {
                followUp = false; refRun = null; followType = null; rerun = true;
                route = new RoutingDecision(RoutingDecision.Mode.OUT_OF_DOMAIN, null, null, "问题不属于经营分析领域");
            } else if (task.needs_clarification()) {
                route = new RoutingDecision(RoutingDecision.Mode.CLARIFY, null, null, task.clarification_question());
            } else if (followUp && !rerun) {
                route = new RoutingDecision(RoutingDecision.Mode.HISTORY, null, null, "引用已存在的分析结果回答");
            } else {
                var match = playbooks.match(text(node, "matched_playbook"), u, task);
                if (match.playbook() != null) {
                    u = u.withScene(match.playbook());
                    route = new RoutingDecision(RoutingDecision.Mode.PLAYBOOK, match.playbook(), null, match.reason());
                } else {
                    String candidateScene = text(node, "matched_react_scene");
                    route = new RoutingDecision(RoutingDecision.Mode.REACT, null,
                            scenes.contains(candidateScene) ? candidateScene : null, match.reason());
                }
            }
            if (u.primaryScene() == null) u = u.withScene(task.in_domain() ? "other" : "out_of_scope");
            u = u.withInterpretation(task, route);
            return new UnderstandingResult(u, followUp, followType, refRun, rerun);
        } catch (LlmException e) { throw e; }
        catch (Exception e) { throw new LlmException("意图理解响应不符合契约: " + e.getMessage(), e); }
    }
    private static String text(JsonNode node, String field) {
        return node.path(field).isTextual() && !node.path(field).asText().isBlank() ? node.path(field).asText() : null;
    }
    private static String renderChainSection(List<ChainContext> chains) {
        if (chains.isEmpty()) return "会话上下文：无";
        StringBuilder result = new StringBuilder("会话上下文（候选分析与事实摘要，集合预览不代表完整集合）：\n<CHAIN_CONTEXT>\n");
        for (var chain : chains) {
            try {
                ObjectNode node = MAPPER.createObjectNode();
                node.put("run_id", chain.runId()); node.put("query", chain.query());
                node.put("source_status", chain.runStatus());
                node.set("understanding", MAPPER.readTree(chain.understandingJson()));
                node.set("facts", MAPPER.valueToTree(HistoryFacts.summaries(chain)));
                result.append(json(node)).append('\n');
            } catch (Exception e) { throw new IllegalArgumentException("历史分析上下文不可解析", e); }
        }
        return result.append("</CHAIN_CONTEXT>").toString();
    }
    private static String json(Object value) {
        try { return MAPPER.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("意图上下文序列化失败", e); }
    }
}
