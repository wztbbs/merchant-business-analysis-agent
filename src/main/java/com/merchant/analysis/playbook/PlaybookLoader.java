package com.merchant.analysis.playbook;

import com.merchant.analysis.exception.PlanValidationException;
import com.merchant.analysis.execution.CapabilityCatalog;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 启动时加载 playbook（场景骨架 + 因子模块 YAML），并做静态校验：
 * 步骤 id 形态与唯一性、depends_on/inputs 引用、插槽位置、产出字段唯一性、
 * 能力白名单与产出供给、因子语义输入在骨架中的生产者存在性。
 * 校验失败直接抛异常使启动失败，避免带病运行。
 *
 * 步骤 id 是语义化的定义 id（如 confirm_change_fact），编织时才分配运行时 id（S1、S2…）。
 *
 * 注：骨架/因子文件名固定枚举（demo 期不依赖 classpath 目录遍历，打包成 jar 也能工作）。
 */
@Component
public class PlaybookLoader {

    private static final List<String> SKELETON_FILES =
            List.of("metric_change_diagnosis", "factor_impact_evaluation", "metric_query_report");
    private static final List<String> FACTOR_FILES =
            List.of("delisting", "traffic", "campaign", "price", "stockout");

    /** 语义化步骤 id：小写字母/数字/下划线，不允许点号（与 inputs 的 "步骤id.产出key" 引用形态区分） */
    private static final Pattern STEP_DEFINITION_ID = Pattern.compile("[a-z][a-z0-9_]*");
    /** inputs 引用形态：步骤id.产出key，或语义引用 @产出key（由插槽前产出该 key 的步骤提供） */
    private static final Pattern INPUT_REF = Pattern.compile("[a-z][a-z0-9_]*\\.\\w+|@\\w+");

    private final Map<String, Skeleton> skeletons = new HashMap<>();
    private final Map<String, FactorModule> factors = new HashMap<>();

    public PlaybookLoader(CapabilityCatalog catalog) {
        for (String scene : SKELETON_FILES) {
            skeletons.put(scene, loadSkeleton(scene, catalog));
        }
        // 因子 depends_on 允许引用骨架固定步骤的定义 id（纯排序依赖），启动时先收集全量骨架步骤 id
        Set<String> skeletonStepIds = new HashSet<>();
        for (Skeleton skeleton : skeletons.values()) {
            for (StepTemplate step : skeleton.fixedSteps()) {
                skeletonStepIds.add(step.stepId());
            }
        }
        for (String factor : FACTOR_FILES) {
            factors.put(factor, loadFactor(factor, catalog, skeletonStepIds));
        }
        validateSemanticInputs();
    }

    public Skeleton skeleton(String scene) {
        Skeleton s = skeletons.get(scene);
        if (s == null) {
            throw new IllegalArgumentException("未注册的场景骨架: " + scene);
        }
        return s;
    }

    public FactorModule factor(String name) {
        FactorModule f = factors.get(name);
        if (f == null) {
            throw new PlanValidationException("未知因子模块: " + name);
        }
        return f;
    }

    private Skeleton loadSkeleton(String scene, CapabilityCatalog catalog) {
        Map<String, Object> root = loadYaml("playbooks/skeletons/" + scene + ".yaml");
        List<StepTemplate> fixed = parseSteps(list(root, "fixed_steps"));
        StepTemplate finalStep = parseStep(map(root, "final_step"));

        Skeleton.FactorSlot factorSlot = null;
        Map<String, Object> slot = map(root, "factor_slot");
        if (slot != null) {
            factorSlot = new Skeleton.FactorSlot(
                    CapabilityCatalog.strList(slot, "default_factors"),
                    bool(slot, "include_user_candidate", true),
                    strOrDefault(slot, "default_policy", "append_after_user_candidate"));
        }

        Skeleton skeleton = new Skeleton(scene, fixed, factorSlot, finalStep);
        validateSkeleton(skeleton, catalog);
        return skeleton;
    }

    private FactorModule loadFactor(String factor, CapabilityCatalog catalog, Set<String> skeletonStepIds) {
        Map<String, Object> root = loadYaml("playbooks/factors/" + factor + ".yaml");
        List<StepTemplate> steps = parseSteps(list(root, "steps"));
        String doc = loadText("playbooks/factors/" + factor + ".md");
        FactorModule.Falsification falsification = parseFalsification(root);

        FactorModule module = new FactorModule(factor, steps, doc, falsification);
        validateFactor(module, catalog, skeletonStepIds);
        return module;
    }

    /** falsification 节点（可空）：本因子步骤产出中某字段为 null 即判定用户假设不成立 */
    private static FactorModule.Falsification parseFalsification(Map<String, Object> root) {
        Map<String, Object> node = map(root, "falsification");
        if (node == null) {
            return null;
        }
        return new FactorModule.Falsification(
                CapabilityCatalog.str(node, "when_output_null"),
                CapabilityCatalog.str(node, "note"));
    }

    // ---------- 静态校验 ----------

    private void validateSkeleton(Skeleton skeleton, CapabilityCatalog catalog) {
        Set<String> ids = new HashSet<>();
        Set<String> outputNames = new HashSet<>();
        for (StepTemplate step : skeleton.fixedSteps()) {
            check(step.stepId() != null && STEP_DEFINITION_ID.matcher(step.stepId()).matches(),
                    skeleton.scene() + " 的步骤 id 必须是语义化定义 id（小写/数字/下划线，不含点号）: " + step.stepId());
            check(step.name() != null && !step.name().isBlank(),
                    skeleton.scene() + "/" + step.stepId() + " 缺少 name");
            check(ids.add(step.stepId()), skeleton.scene() + " 存在重复步骤 id: " + step.stepId());
            validateStepSupply(step, catalog);
            for (String dep : step.dependsOn()) {
                check(ids.contains(dep), skeleton.scene() + "/" + step.stepId()
                        + " depends_on 引用了未定义或非前序的步骤: " + dep);
            }
            for (String output : step.outputs()) {
                check(outputNames.add(output), skeleton.scene() + " 存在重复产出字段: " + output
                        + "（同一骨架内产出字段需唯一，语义引用 @产出key 按它反查生产者）");
            }
        }
        if (skeleton.factorSlot() != null) {
            check("append_after_user_candidate".equals(skeleton.factorSlot().defaultPolicy()),
                    skeleton.scene() + " factor_slot.default_policy 不支持: "
                            + skeleton.factorSlot().defaultPolicy() + "（当前仅支持 append_after_user_candidate）");
        }
        check(skeleton.finalStep().dependsOn().equals(List.of("previous_steps")),
                skeleton.scene() + " final_step.depends_on 必须为 {scope: previous_steps}");
    }

    private void validateFactor(FactorModule module, CapabilityCatalog catalog, Set<String> skeletonStepIds) {
        Set<String> localIds = new HashSet<>();
        for (StepTemplate step : module.steps()) {
            check(step.stepId() != null && STEP_DEFINITION_ID.matcher(step.stepId()).matches(),
                    module.factor() + " 的步骤 id 必须是语义化定义 id（小写/数字/下划线，不含点号）: " + step.stepId());
            check(step.name() != null && !step.name().isBlank(),
                    module.factor() + "/" + step.stepId() + " 缺少 name");
            check(localIds.add(step.stepId()), module.factor() + " 存在重复步骤 id: " + step.stepId());
            validateStepSupply(step, catalog);
            for (String dep : step.dependsOn()) {
                check(localIds.contains(dep) || skeletonStepIds.contains(dep),
                        module.factor() + "/" + step.stepId() + " depends_on 引用非法: " + dep
                                + "（只能引用本模块前序步骤或骨架固定步骤的定义 id）");
            }
            for (String ref : step.inputs().values()) {
                check(ref != null && INPUT_REF.matcher(ref).matches(),
                        module.factor() + "/" + step.stepId() + " inputs 引用形态非法: " + ref);
            }
        }
        if (module.falsification() != null) {
            String output = module.falsification().output();
            check(output != null && module.steps().stream().anyMatch(s -> s.outputs().contains(output)),
                    module.factor() + " 的 falsification.when_output_null 引用了本模块未声明的产出: " + output);
            check(module.falsification().note() != null && !module.falsification().note().isBlank(),
                    module.factor() + " 的 falsification 缺少 note");
        }
    }

    /**
     * 交叉校验：因子步骤的语义输入 @key 必须能挂进每个带插槽的骨架——
     * 即该骨架存在产出 key 的固定步；同时校验各骨架 default_factors 引用的因子模块已注册。
     * 校验不过直接启动失败，不留到 plan 编织期。
     */
    private void validateSemanticInputs() {
        for (Skeleton skeleton : skeletons.values()) {
            if (skeleton.factorSlot() == null) {
                continue;
            }
            for (String factorName : skeleton.factorSlot().defaultFactors()) {
                check(factors.containsKey(factorName),
                        skeleton.scene() + " 的 default_factors 引用了未注册的因子模块: " + factorName);
            }
            Set<String> provided = new HashSet<>();
            for (StepTemplate step : skeleton.fixedSteps()) {
                provided.addAll(step.outputs());
            }
            for (FactorModule module : factors.values()) {
                for (StepTemplate step : module.steps()) {
                    for (String ref : step.inputs().values()) {
                        if (ref != null && ref.startsWith("@")) {
                            check(provided.contains(ref.substring(1)),
                                    module.factor() + "/" + step.stepId() + " 的语义输入 " + ref
                                            + " 在骨架 " + skeleton.scene() + " 的固定步中找不到产出者");
                        }
                    }
                }
            }
        }
    }

    /** 供给校验：capability 在目录中；声明的 outputs ⊆ 各能力 produces 的并集 */
    private void validateStepSupply(StepTemplate step, CapabilityCatalog catalog) {
        if (step.capabilities().isEmpty()) {
            return; // 收尾步无能力，仅作为依赖汇聚点
        }
        Set<String> produced = new HashSet<>();
        for (String cap : step.capabilities()) {
            check(catalog.exists(cap), "playbook 引用了能力目录中不存在的 capability: " + cap);
            produced.addAll(catalog.get(cap).produces());
        }
        for (String out : step.outputs()) {
            check(produced.contains(out),
                    step.stepId() + " 声明的 output " + out + " 不在其能力 " + step.capabilities() + " 的 produces 中");
        }
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new IllegalStateException("playbook 校验失败: " + message);
        }
    }

    // ---------- YAML 解析 ----------

    private List<StepTemplate> parseSteps(List<Map<String, Object>> rawList) {
        List<StepTemplate> steps = new ArrayList<>();
        for (Map<String, Object> raw : rawList) {
            steps.add(parseStep(raw));
        }
        return steps;
    }

    @SuppressWarnings("unchecked")
    private StepTemplate parseStep(Map<String, Object> raw) {
        return new StepTemplate(
                CapabilityCatalog.str(raw, "step_id"),
                CapabilityCatalog.str(raw, "name"),
                CapabilityCatalog.strList(raw, "capabilities"),
                rawMap(raw.get("params")),
                parseInputs(raw.get("inputs")),
                dependsOn(raw.get("depends_on")),
                CapabilityCatalog.strList(raw, "outputs"));
    }

    /**
     * inputs 两种形态：
     * map（输入名 → 引用，输入名即注入 params 的参数名）或 list（引用列表，输入名取引用中的输出 key——
     * "@metric_delta" / "confirm_change_fact.metric_delta" 的输入名都是 metric_delta）。
     */
    private static Map<String, String> parseInputs(Object v) {
        Map<String, String> result = new LinkedHashMap<>();
        if (v instanceof Map<?, ?>) {
            return strMap(v);
        }
        if (v instanceof List<?> list) {
            for (Object ref : list) {
                String s = ref.toString();
                String key = s.startsWith("@") ? s.substring(1) : s.substring(s.indexOf('.') + 1);
                result.put(key, s);
            }
        }
        return result;
    }

    /** depends_on：列表（步骤定义 id）或 map 形式 {scope: previous_steps}（仅收尾步骤） */
    @SuppressWarnings("unchecked")
    private static List<String> dependsOn(Object v) {
        if (v == null) {
            return List.of();
        }
        if (v instanceof List<?> list) {
            return (List<String>) list;
        }
        if (v instanceof Map<?, ?> scopeMap) {
            return List.of(String.valueOf(scopeMap.get("scope")));
        }
        return List.of(v.toString());
    }

    private static boolean bool(Map<String, Object> map, String key, boolean defaultValue) {
        Object v = map.get(key);
        return v == null ? defaultValue : Boolean.parseBoolean(v.toString());
    }

    private static String strOrDefault(Map<String, Object> map, String key, String defaultValue) {
        Object v = map.get(key);
        return v == null ? defaultValue : v.toString();
    }

    /** params：保留原始类型（字符串含 {{xxx}} 占位符，数字/布尔等原样透传） */
    private static Map<String, Object> rawMap(Object v) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (v instanceof Map<?, ?> map) {
            map.forEach((k, val) -> result.put(k.toString(), val));
        }
        return result;
    }

    private static Map<String, String> strMap(Object v) {
        Map<String, String> result = new LinkedHashMap<>();
        if (v instanceof Map<?, ?> map) {
            map.forEach((k, val) -> result.put(k.toString(), val == null ? null : val.toString()));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> root, String key) {
        return (Map<String, Object>) root.get(key);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> root, String key) {
        Object v = root.get(key);
        return v == null ? List.of() : (List<Map<String, Object>>) v;
    }

    private static Map<String, Object> loadYaml(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new Yaml().load(in);
        } catch (Exception e) {
            throw new IllegalStateException("加载 " + path + " 失败", e);
        }
    }

    private static String loadText(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("加载 " + path + " 失败", e);
        }
    }
}
