package com.merchant.analysis.understanding;

import java.util.List;
import java.util.Set;
import com.merchant.analysis.time.TimeSpec;

/** 保留用户原文、补全后的任务和各子需求；不把问题改写成模板方便执行的形式。 */
public record QueryTask(String original_query, String resolved_query, Boolean in_domain,
                        List<Requirement> requirements, List<FactReference> fact_references,
                        boolean needs_clarification, String clarification_question) {
    public QueryTask {
        if (resolved_query == null || resolved_query.isBlank() || in_domain == null)
            throw new IllegalArgumentException("任务需要 resolved_query 和 in_domain");
        requirements = requirements == null ? List.of() : List.copyOf(requirements);
        fact_references = fact_references == null ? List.of() : List.copyOf(fact_references);
        if (in_domain && requirements.isEmpty() && !needs_clarification)
            throw new IllegalArgumentException("领域内任务需要至少一个结构化子需求");
        if (needs_clarification && (clarification_question == null || clarification_question.isBlank()))
            throw new IllegalArgumentException("需要澄清时必须提供问题");
    }
    public QueryTask withOriginal(String original) {
        return new QueryTask(original, resolved_query, in_domain, requirements, fact_references,
                needs_clarification, clarification_question);
    }
    public QueryTask clarify(String question) {
        return new QueryTask(original_query, resolved_query, in_domain, requirements, fact_references, true, question);
    }
    public record Requirement(String action, String metric, String scope, List<String> entities,
                              TimeSpec time_range, TimeSpec comparison_baseline) {
        public Requirement(String action, String metric, String scope, List<String> entities) {
            this(action, metric, scope, entities, null, null);
        }
        public Requirement {
            if (!Set.of("query", "diagnosis", "evaluation", "comparison", "forecast", "advice", "explain").contains(action == null ? "" : action)
                    || !Set.of("shop", "sku", "category", "multi_sku", "dynamic_skus", "other").contains(scope == null ? "" : scope))
                throw new IllegalArgumentException("需求 action 或 scope 非法");
            if (metric == null && !Set.of("advice", "explain").contains(action))
                throw new IllegalArgumentException("查询、诊断等需求需要 metric");
            entities = entities == null ? List.of() : List.copyOf(entities);
            if (entities.stream().anyMatch(String::isBlank)) throw new IllegalArgumentException("实体名称不能为空");
        }
    }
    public record FactReference(String run_id, String path) {
        public FactReference {
            if (run_id == null || run_id.isBlank() || path == null
                    || !path.matches("[A-Za-z][A-Za-z0-9_]*(\\.(?:[A-Za-z_][A-Za-z0-9_]*|[0-9]+))+"))
                throw new IllegalArgumentException("历史事实引用需要 run_id 和完整字段路径");
        }
    }
}
