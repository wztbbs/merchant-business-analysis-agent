# Playbook：骨架、因子、coverage 与校验

## 什么时候写 Playbook

当问题有稳定的取数顺序、明确的输入/输出和可验证依赖时，使用 Playbook。不能覆盖的领域内问题交给 ReAct；只有一段分析经验时优先写[文字场景](react-scene-candidates.md)，不用勉强构造固定 DAG。

Playbook 分为三部分：骨架负责固定步骤和因子插槽；因子负责可插拔的业务排查；coverage 约束模板真正可回答的需求。模型负责建议匹配，不自由生成 DAG。

## 当前注册范围

骨架位于 `playbooks/skeletons/`：

| scene | 范围 |
|---|---|
| metric_query_report | 全店 sales_amount/order_count/aov 查询，最多 3 个同时间需求 |
| metric_change_diagnosis | 单一全店 sales_amount 变化诊断 |
| factor_impact_evaluation | 一个明确 SKU 的下架销售变化评估 |

因子位于 `playbooks/factors/`，当前为 delisting、traffic、campaign、price、stockout，每个有 YAML 和同名 MD。MD 在生成模板结论时作为业务说明注入，它不是计算代码，也不提供因果识别能力。应以工具真实口径解释“影响/贡献/替代”，不能据说明文字把相关性提升为因果。

PlaybookLoader 当前使用 `SKELETON_FILES/FACTOR_FILES` 固定枚举，新增文件需要同步注册；与自动发现文字场景不同。

## 骨架结构

下面摘录诊断骨架的核心结构：

```yaml
scene: metric_change_diagnosis
fixed_steps:
  - step_id: confirm_change_fact
    name: 确认变化事实
    capabilities: [sales_summary_query]
    params: {time_range: "{{time_range}}", baseline: "{{comparison_baseline}}"}
    outputs: [metric_delta, order_delta, aov_delta]
  - step_id: contribution_breakdown
    name: 贡献度拆解
    depends_on: [confirm_change_fact]
    capabilities: [sku_sales_breakdown, category_sales_breakdown]
    params: {time_range: "{{time_range}}", baseline: "{{comparison_baseline}}"}
    outputs: [top_contributors, category_breakdown]
factor_slot:
  default_factors: [campaign, traffic, price, stockout]
  include_user_candidate: true
  default_policy: append_after_user_candidate
final_step:
  step_id: final_conclusion
  name: 综合结论与建议
  depends_on: {scope: previous_steps}
description: 全店销售额变化诊断，以销售贡献榜和活动、流量、价格、缺货等线索排查。
coverage:
  actions: [diagnosis]
  metrics: [sales_amount]
  scopes: [shop]
  candidate_factors: [delisting, traffic, campaign, price, stockout]
  requires_sku: false
  max_requirements: 1
```

定义 ID 为小写字母开头的小写字母/数字/下划线，不能含点。运行时由 PlanWeaver 统一改成 S1/S2 等，因子不应硬编码运行时编号。

`outputs` 声明步骤的逻辑供给，必须能由 capabilities 的 produces 提供；工具实际返回字段可能多于逻辑 outputs。`final_step` 依赖 previous_steps，作为全部前序步骤结束的屏障；本身通常不调用业务能力，实际结论由 Controller 后续调用 ConclusionService 生成。

coverage 的完整匹配条件见[路由](routing-and-query-rewrite.md)。添加更宽的描述而不增加实际供给，不能扩大 coverage；例如订单量查询工具存在，不等于销售额贡献榜能做订单量归因。

## 因子结构和两类引用

```yaml
factor: delisting
falsification:
  when_output_null: delisting_time
  note: 用户假设不成立：该 SKU 查询期内无下架记录
steps:
  - step_id: confirm_delisting_event
    name: 确认下架事件
    capabilities: [sku_status_query]
    params: {sku: "{{entity.sku}}"}
    outputs: [delisting_time, listing_status]
  - step_id: quantify_delisting_impact
    name: 量化下架影响
    depends_on: [confirm_delisting_event]
    inputs: {metric_delta: "@metric_delta"}
    capabilities: [sku_sales_history, substitute_sku_analysis]
    params: {sku: "{{entity.sku}}", time_range: "{{time_range}}"}
    outputs: [sales_delta, contribution_ratio, substitution_effect]
```

- `depends_on` 表达执行顺序。
- `inputs: {参数名: "定义步骤.字段"}` 明确取上游结果，例如价格因子取 `contribution_breakdown.top_contributors`；编织时改写为 S 编号。
- `inputs: {参数名: "@字段"}` 按插槽前固定步骤的唯一产出查找生产者，重写为具体引用并自动补依赖。
- 参数模板的完整占位符保留对象类型，例如 TimeSpec 不会被转成 JSON 字符串；实体由 `{{entity.sku}}` 等填充。

语义引用 @字段不是任意上下文检索。当前加载器会验证该语义字段在所有带因子插槽的骨架中存在；某个字段仅有诊断骨架提供时，不适合用全局 @引用。

价格因子因此使用显式 `contribution_breakdown` 依赖和 inputs。它适用于有贡献榜的诊断骨架，不能直接移植到当前下架评估骨架。路由 coverage 也没有宣称价格影响评估已经支持。

## 选择因子和编织

`FactorSelector` 根据 candidate_factor 和 skeleton.factor_slot 选择模块：用户候选优先，再按默认列表去重追加。`scene.factor_modules` 是兼容的模型分类字段，不是直接执行的任意模块列表。

当前销售额诊断默认查 campaign、traffic、price、stockout；如用户提出下架假设，先加入 delisting。PlanWeaver 实例化全部步骤、替换参数和引用、补依赖，最终生成完整静态 Plan；不会根据执行到一半的数据再请模型重写此 DAG。

通用计算工具已在统一目录注册，固定步骤可以使用它们并声明相应 inputs/outputs；现有业务 SQL 汇总并没有全部改写成独立计算节点。因子仍负责业务路线，计算职责已可独立复用。

## 两层校验

启动期 PlaybookLoader 检查 ID、前序依赖、因子引用、能力白名单、声明产出来源、语义供给、final_step 和 coverage 必要字段。错误配置会导致启动失败。

运行期 PlanValidator 检查实例化后的 ID 唯一、依赖存在、无环、工具已注册、输出可供应、输入和嵌套结果引用来自声明的上游输出且依赖可达。它不证明业务结论、单位、指标可加性或每个因子的因果有效性。

## 证伪与测试

falsification 只在该因子等于用户 candidate_factor 时生效。规则字段为 null 时标记 hypothesis_broken，跳过本因子后续步骤；不停止其他默认因子。当前规则只有指定产出为 null，没有任意表达式规则引擎。恢复时会用已成功结果重放这一判断。

新增或修改 Playbook 至少应验证覆盖边界、编织后依赖、真实输出供给、空对象、证伪和恢复；涉及不同骨架的因子要逐个验证适配。已有测试可参考 PlanWeaverTest、ToolPlanTest、ExecutionServiceFalsificationTest。执行细节见[执行与恢复](execution.md)。
