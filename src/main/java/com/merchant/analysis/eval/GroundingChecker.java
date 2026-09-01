package com.merchant.analysis.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 结论 grounding 机械校验（不调 LLM）。覆盖三类失效模式中可规则化的部分：
 * - 编造（extrinsic）：结论数字必须能在 step_results 数值集合中找到（含 ×100 百分比换算与比值派生）；
 * - 漏说（omission）：店铺级问题的主因必须点名、假设证伪必须声明（单品问题不强制点名店铺级主因）；
 * - 说错（intrinsic）的浅层：符号一致性（方向表述与用户所问指标的 delta 符号一致，而非固定看店铺级
 *   metric_delta——销售额下滑但客单价上升是合法剧情）。
 * 深层的因果断言正确性仍需 LLM-as-judge，不在本类范围。
 */
@Component
public class GroundingChecker {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 日期时间先剔除，避免 2026/07/15 这类片段被当成业务数字 */
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{1,2}-\\d{1,2}( \\d{1,2}:\\d{1,2}(:\\d{1,2})?)?");
    /** 千分位逗号（10,997 → 10997），避免被拆成两个无出处数字 */
    private static final Pattern THOUSANDS = Pattern.compile("(?<=\\d),(?=\\d{3})");
    /** 行首编号（"1. " / "2、"）是列表排版不是业务数字，数字抽取前剔除 */
    private static final Pattern LIST_MARKER = Pattern.compile("(?m)^\\s*\\d+[.、](?=\\s)");
    /** 步骤编号引用（如 S3）不是业务数字，数字抽取前剔除 */
    private static final Pattern STEP_REF = Pattern.compile("\\bS\\d+\\b");
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?%?");
    /** 否定形态的方向词（"未出现增长"/"没有下滑"），符号一致性判断前先剔除，防止误判 */
    private static final Pattern NEGATED_DIRECTION = Pattern.compile(
            "(未|没有|并无|未见)(出现|发生)?(增长|上升|上涨|提升|下滑|下降|下跌)");

    public record CheckResult(String name, boolean passed, String detail) {
    }

    public List<CheckResult> check(String conclusion, Map<String, Map<String, Object>> stepResults,
                                   boolean hypothesisBroken, String query, String metric, String sku) {
        List<CheckResult> results = new ArrayList<>();
        results.add(checkNumbers(conclusion, stepResults, query));
        results.add(checkMainCause(conclusion, stepResults, sku));
        results.add(checkHypothesis(conclusion, hypothesisBroken));
        results.add(checkEntities(conclusion, stepResults, query));
        results.add(checkSignConsistency(conclusion, stepResults, metric));
        return results;
    }

    /**
     * 编造检查：结论中每个数字都能在 step_results 中找到来源（原值 / ×100 百分比 / 两值比值），
     * 或来自用户 query 本身（用户自己提到的数字，如"满50减10"的活动规则，与实体检查的 query 豁免对齐）。
     */
    private CheckResult checkNumbers(String conclusion, Map<String, Map<String, Object>> stepResults,
                                     String query) {
        Set<Double> values = new HashSet<>();
        List<String> texts = new ArrayList<>();
        collect(stepResults, values, texts);
        Set<String> queryNumbers = extractNumbers(query);

        String stripped = THOUSANDS.matcher(DATE.matcher(conclusion).replaceAll(" ")).replaceAll("");
        stripped = STEP_REF.matcher(LIST_MARKER.matcher(stripped).replaceAll("")).replaceAll(" ");
        List<String> unmatched = new ArrayList<>();
        Matcher m = NUMBER.matcher(stripped);
        while (m.find()) {
            String token = m.group();
            boolean percent = token.endsWith("%");
            double x = Double.parseDouble(percent ? token.substring(0, token.length() - 1) : token);
            if (!matchesAny(x, percent, values) && !appearsInTexts(token, texts)
                    && !queryNumbers.contains(normalizeNumber(token))) {
                unmatched.add(token);
            }
        }
        return unmatched.isEmpty()
                ? new CheckResult("number_grounding", true, "结论数字全部有出处")
                : new CheckResult("number_grounding", false, "无出处的数字: " + unmatched);
    }

    /** 提取 query 中的数字（与结论侧同一 tokenizer），用于"用户自己提到的数字"豁免 */
    private static Set<String> extractNumbers(String query) {
        Set<String> numbers = new HashSet<>();
        if (query != null) {
            Matcher m = NUMBER.matcher(query);
            while (m.find()) {
                numbers.add(normalizeNumber(m.group()));
            }
        }
        return numbers;
    }

    /** 数字归一化：去掉 % 后缀和多余的 .0，保证 50 / 50.0 / 50% 视为同一个数 */
    private static String normalizeNumber(String token) {
        String plain = token.endsWith("%") ? token.substring(0, token.length() - 1) : token;
        return plain.endsWith(".0") ? plain.substring(0, plain.length() - 2) : plain;
    }

    /**
     * 漏说检查 1：变化贡献最大的单品必须在结论中点名。
     * 单品问题（query 带了具体 sku）不强制点名店铺级主因——结论围绕单品展开是合理的。
     */
    private CheckResult checkMainCause(String conclusion, Map<String, Map<String, Object>> stepResults,
                                       String sku) {
        if (sku != null && !sku.isBlank()) {
            return new CheckResult("coverage_main_cause", true, "单品问题，不强制点名店铺级主因，跳过");
        }
        for (Map<String, Object> outputs : stepResults.values()) {
            Object tc = outputs.get("top_contributors");
            if (tc instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> top) {
                Object name = top.get("name");
                if (name == null) {
                    return new CheckResult("coverage_main_cause", true, "top_contributors 无名称字段，跳过");
                }
                boolean found = conclusion.contains(name.toString());
                return new CheckResult("coverage_main_cause", found,
                        found ? "主因「" + name + "」已点名" : "主因「" + name + "」未在结论中出现");
            }
        }
        return new CheckResult("coverage_main_cause", true, "无 top_contributors 产出，跳过");
    }

    /** 漏说检查 2：假设被证伪时，结论必须明确声明 */
    private CheckResult checkHypothesis(String conclusion, boolean hypothesisBroken) {
        if (!hypothesisBroken) {
            return new CheckResult("coverage_hypothesis", true, "假设未被证伪，跳过");
        }
        boolean stated = conclusion.contains("不成立") || conclusion.contains("无下架") || conclusion.contains("未下架");
        return new CheckResult("coverage_hypothesis", stated,
                stated ? "假设证伪已声明" : "hypothesis_broken=true 但结论未声明假设不成立");
    }

    /**
     * 实体检查：结论中「」引用的名称必须有出处——出现在 step_results（证据）或用户原始 query
     * （用户自己提到的实体）中。前者防编造，后者排除话术中的非实体引用（如「为什么」类问法提示）。
     * 注意：只能抓纯编造；"证据里存在但张冠李戴"的深层实体错误仍需 LLM-as-judge。
     */
    private CheckResult checkEntities(String conclusion, Map<String, Map<String, Object>> stepResults,
                                      String query) {
        String evidenceJson;
        try {
            evidenceJson = MAPPER.writeValueAsString(stepResults);
        } catch (Exception e) {
            return new CheckResult("entity_grounding", false, "step_results 序列化失败: " + e.getMessage());
        }
        String q = query == null ? "" : query;
        List<String> unknown = new ArrayList<>();
        Matcher m = Pattern.compile("「([^」]+)」").matcher(conclusion);
        while (m.find()) {
            String name = m.group(1);
            if (!evidenceJson.contains(name) && !q.contains(name)) {
                unknown.add(name);
            }
        }
        return unknown.isEmpty()
                ? new CheckResult("entity_grounding", true, "引用实体全部有出处")
                : new CheckResult("entity_grounding", false, "无出处的实体: " + unknown);
    }

    /**
     * 符号一致性（说错的浅层规则化）：按用户所问指标取对应 delta——
     * aov → aov_delta，order_count → order_delta，其余 → metric_delta；
     * 结论说"下滑/下降"时该 delta 必须为负，说"上升/上涨"时必须为正。
     */
    private CheckResult checkSignConsistency(String conclusion, Map<String, Map<String, Object>> stepResults,
                                             String metric) {
        String deltaField = switch (metric == null ? "" : metric) {
            case "aov" -> "aov_delta";
            case "order_count" -> "order_delta";
            default -> "metric_delta";
        };
        Double delta = null;
        for (Map<String, Object> outputs : stepResults.values()) {
            if (outputs.get(deltaField) instanceof Number n) {
                delta = n.doubleValue();
                break;
            }
        }
        if (delta == null) {
            return new CheckResult("sign_consistency", true, "无 " + deltaField + " 产出，跳过");
        }
        String normalized = NEGATED_DIRECTION.matcher(conclusion).replaceAll(" ");
        boolean saysDown = normalized.contains("下滑") || normalized.contains("下降") || normalized.contains("下跌");
        boolean saysUp = normalized.contains("上升") || normalized.contains("上涨") || normalized.contains("增长");
        if (delta < 0 && saysUp && !saysDown) {
            return new CheckResult("sign_consistency", false, deltaField + "=" + delta + " 为负，但结论只表述上升");
        }
        if (delta > 0 && saysDown && !saysUp) {
            return new CheckResult("sign_consistency", false, deltaField + "=" + delta + " 为正，但结论只表述下滑");
        }
        return new CheckResult("sign_consistency", true, "方向表述与 " + deltaField + " 符号一致");
    }

    private static boolean matchesAny(double x, boolean percent, Set<Double> values) {
        double ax = Math.abs(x);   // 结论常以绝对值表述（"损失 16793 元"），存储值带符号；符号正确性由 sign_consistency 负责
        for (double v : values) {
            double av = Math.abs(v);
            if (close(ax, av) || close(ax, av * 100) || close(ax, av / 100)) {
                return true;
            }
            // 比值派生：如「环比 -23.3%」= delta/base，并非任何单一存储值
            for (double u : values) {
                if (u != 0 && (close(ax, Math.abs(v / u)) || close(ax, Math.abs(v / u) * 100))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean close(double a, double b) {
        return Math.abs(a - b) <= Math.max(0.06, Math.abs(b) * 0.005);
    }

    /** 兜底：数字以文本形态出现在 step_results 的字符串值里（如活动规则「满50减10」） */
    private static boolean appearsInTexts(String token, List<String> texts) {
        String plain = token.endsWith("%") ? token.substring(0, token.length() - 1) : token;
        if (plain.endsWith(".0")) {
            plain = plain.substring(0, plain.length() - 2);
        }
        for (String t : texts) {
            if (t.contains(plain)) {
                return true;
            }
        }
        return false;
    }

    /** 递归收集 step_results 中的全部数值与字符串 */
    private static void collect(Object node, Set<Double> values, List<String> texts) {
        if (node instanceof Number n) {
            values.add(n.doubleValue());
        } else if (node instanceof String s) {
            texts.add(s);
        } else if (node instanceof Map<?, ?> map) {
            map.values().forEach(v -> collect(v, values, texts));
        } else if (node instanceof List<?> list) {
            list.forEach(v -> collect(v, values, texts));
        }
    }
}
