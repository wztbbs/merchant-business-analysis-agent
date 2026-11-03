package com.merchant.analysis.understanding;

import com.merchant.analysis.playbook.PlaybookLoader;
import com.merchant.analysis.playbook.Skeleton;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Objects;

/** 对模型提出的模板候选做保守覆盖校验，不能只凭宽泛场景名进入模板。 */
@Component
public class PlaybookMatcher {
    public record Summary(String id, String description, Skeleton.Coverage coverage) {}
    public record Match(String playbook, String reason) {}
    private final PlaybookLoader loader;
    public PlaybookMatcher(PlaybookLoader loader) { this.loader = loader; }
    public List<Summary> summaries() {
        return loader.skeletons().stream().map(s -> new Summary(s.scene(), s.description(), s.coverage())).toList();
    }
    public Match match(String proposed, QueryUnderstanding u, QueryTask task) {
        if (proposed == null) return new Match(null, "没有模板候选");
        Skeleton skeleton = loader.skeletons().stream().filter(s -> s.scene().equals(proposed)).findFirst().orElse(null);
        if (skeleton == null) return new Match(null, "模板候选未注册");
        var coverage = skeleton.coverage();
        if (!task.fact_references().isEmpty()) return new Match(null, "模板未声明历史事实集合输入");
        if (task.requirements().isEmpty() || task.requirements().size() > coverage.max_requirements())
            return new Match(null, "模板不能覆盖全部子需求");
        if (u.candidate_factor() != null && !coverage.candidate_factors().contains(u.candidate_factor()))
            return new Match(null, "模板不支持该候选因子");
        if (coverage.requires_sku() && (u.skuOrNull() == null || u.skuOrNull().isBlank() || u.candidate_factor() == null))
            return new Match(null, "模板缺少商品或因子参数");
        for (var requirement : task.requirements()) {
            if (!coverage.actions().contains(requirement.action()) || !coverage.metrics().contains(requirement.metric())
                    || !coverage.scopes().contains(requirement.scope()))
                return new Match(null, "模板不支持请求的动作、指标或范围");
            if (requirement.time_range() != null && !Objects.equals(requirement.time_range(), u.time_range())
                    || requirement.comparison_baseline() != null && !Objects.equals(requirement.comparison_baseline(), u.comparison_baseline()))
                return new Match(null, "模板不能在一次执行中覆盖不同时间窗口");
            if (requirement.scope().equals("shop") && !requirement.entities().isEmpty())
                return new Match(null, "全店模板不支持实体筛选");
            if (coverage.requires_sku() && (requirement.entities().size() != 1 || !requirement.entities().get(0).equals(u.skuOrNull())))
                return new Match(null, "模板实体参数与任务不一致");
        }
        if (task.requirements().stream().noneMatch(r -> Objects.equals(r.metric(), u.metric())))
            return new Match(null, "模板指标槽位与任务不一致");
        if ("delisting".equals(u.candidate_factor()) && (u.skuOrNull() == null || u.skuOrNull().isBlank()))
            return new Match(null, "下架核查需要具体商品");
        return new Match(proposed, "模板完整覆盖结构化需求");
    }
}
