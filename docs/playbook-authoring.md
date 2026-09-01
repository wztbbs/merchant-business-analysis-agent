# 如何编写场景模板与原因模块（playbook authoring）

playbook 是 plan 编织的全部输入：场景模板（skeleton）+ 原因模块（factor），都是 YAML，
启动时由 `PlaybookLoader` 加载并静态校验，写错直接启动失败。

**id 规则**：模板里的步骤 id 一律是语义化的定义 id（小写/数字/下划线，不含点号，如
`confirm_change_fact`）；运行时编号（S1、S2…）由 PlanWeaver 编织时按顺序统一分配，
模板作者不需要也不应该手写运行时编号。

## 场景模板（playbooks/skeletons/*.yaml）

以 `metric_change_diagnosis.yaml` 为例：

```yaml
# 指标变化诊断骨架：确认事实 → 贡献拆解 → [因子插槽] → 综合结论
scene: metric_change_diagnosis
fixed_steps:
  - step_id: confirm_change_fact
    name: 确认变化事实
    capabilities: [sales_summary_query]
    params: { time_range: "{{time_range}}", baseline: "{{comparison_baseline}}" }
    outputs: [metric_delta, order_delta, aov_delta]
  - step_id: contribution_breakdown
    name: 贡献度拆解
    depends_on: [confirm_change_fact]
    capabilities: [sku_sales_breakdown, category_sales_breakdown]
    params: { time_range: "{{time_range}}", baseline: "{{comparison_baseline}}" }
    outputs: [top_contributors, category_breakdown]
factor_slot:
  default_factors: [campaign, traffic, price, stockout]
  include_user_candidate: true                   # 用户假设因子必选且排最前
  default_policy: append_after_user_candidate    # 默认因子按声明顺序去重追加在假设因子之后
final_step:
  step_id: final_conclusion
  name: 综合结论与建议
  depends_on: { scope: previous_steps }          # 依赖全部前序步骤
```

字段说明：

- `fixed_steps`：固定节点，定义 id 语义化，节点间 depends_on 也引用定义 id；
- `factor_slot.default_factors`：默认挂载的原因模块；
- `factor_slot.include_user_candidate` / `default_policy`：因子选择策略（当前仅支持
  `append_after_user_candidate`：用户怀疑原因必选且排最前，默认因子去重追加），写在配置里，
  改策略不改代码；
- `final_step.depends_on`：必须为 `{scope: previous_steps}`，编织时展开为全部前序节点；
- 同一骨架内各节点的 `outputs` 必须唯一（语义引用按产出字段反查生产者，重复会导致定位歧义）。

## 原因模块（playbooks/factors/*.yaml）

以 `delisting.yaml` 为例：

```yaml
# 下架因子：确认下架事件 → 量化下架影响（含替代效应）
factor: delisting
# 证伪规则（可选）：仅当本因子是用户假设（candidate_factor）时生效——
# 本因子节点产出中该字段为 null 即判定用户假设不成立，执行期跳过本因子后续节点
falsification:
  when_output_null: delisting_time
  note: 用户假设不成立：该 SKU 查询期内无下架记录
steps:
  - step_id: confirm_delisting_event
    name: 确认下架事件
    capabilities: [sku_status_query]
    params: { sku: "{{entity.sku}}" }
    outputs: [delisting_time, listing_status]
  - step_id: quantify_delisting_impact
    name: 量化下架影响
    depends_on: [confirm_delisting_event]
    # 语义引用 @产出key：key 是注入 params 的参数名，value 声明"需要插槽之前产出该字段的那一步"；
    # 编织时反查生产者步骤重写为具体引用（如 S1.metric_delta）并自动补 depends_on，
    # 因子模块无需知道宿主骨架的步骤编号
    inputs: { metric_delta: "@metric_delta" }
    capabilities: [sku_sales_history, substitute_sku_analysis]
    params: { sku: "{{entity.sku}}", time_range: "{{time_range}}" }
    outputs: [sales_delta, contribution_ratio, substitution_effect]
```

规则：

- 节点 id 是模块内语义化的定义 id，模块内 depends_on 引用前序节点的定义 id，
  编织时重映射为运行时 id；
- `depends_on` 也可引用骨架固定节点的定义 id，但仅限纯排序/副作用依赖（如 price 模块依赖
  `contribution_breakdown`，因为其执行器隐式复用该节点的 top_contributors 收敛扫描范围）。
  注意这类引用会把因子绑定到含有对应节点的骨架上；
- 每个因子配一个同名 `.md` 文件，写中文分析思路/结论话术，结论生成阶段拼进 prompt；
- `falsification`（可选）：证伪规则，声明"本因子节点产出中某字段为 null 即用户假设不成立"。
  仅当本因子是用户假设（candidate_factor）时生效——默认因子命中规则不会标记证伪；
  证伪后执行期跳过本因子后续节点（记 SKIPPED），note 写入结论输入的 hypothesis_note。

## inputs：节点间的数据引用

节点间数值一律用引用传递（"上游节点.产出字段"），不经过 LLM。两种写法：

```yaml
# map 形式：key 是注入 params 的参数名，value 是引用来源（需要别名时用）
inputs: { total_delta: "@metric_delta" }   # 注入 params 的 key 为 total_delta

# list 简写：输入名直接取引用中的输出 key
inputs: ["@metric_delta"]                              # 注入 key 为 metric_delta
inputs: ["confirm_change_fact.metric_delta"]           # 直接引用骨架节点（等价于上面的解析结果）
```

执行时 `ExecutionService` 把每个 input 解析成实际值，以输入名为 key 注入该节点的 `params`。

## 语义引用 @产出key

原因模块要跨场景复用，不能硬编码宿主骨架的节点编号。对骨架产出的引用写成语义形式
`@产出key`（如 `@metric_delta`），含义是"我需要插槽之前产出这个字段的那个节点"：

1. `PlanWeaver` 编织时在已编织节点里按 outputs 反查生产者；
2. 重写为具体引用（`@metric_delta` → `S1.metric_delta`）；
3. 自动把生产者节点补进 `depends_on`（数据依赖即依赖边，depends_on 里不用再重复写）；
4. 找不到生产者抛 `PlanValidationException`。

启动期 `PlaybookLoader` 还会做交叉校验：每个因子的 `@key` 输入必须在**每个带插槽的骨架**的
固定节点里找到生产者，否则启动失败——错误暴露在启动时，而不是运行期。

## 参数模板

`params` 里可用的占位符（意图识别产出，编织时由 TemplateContext 替换；默认值也在此归一化）：

- `{{entity.sku}}` → 用户提到的商品名；
- `{{time_range}}` → 逻辑时间（如 last_month），缺省 last_month；
- `{{comparison_baseline}}` → 对比基线（如 previous_month），缺省 previous_month。

params 的值不限于字符串：数字、布尔等原始类型原样透传，只有字符串才做占位符替换。

## 新增一个场景 / 原因的步骤

新增原因模块：

1. 写 `playbooks/factors/xxx.yaml`（+ `xxx.md` 分析思路）；
2. 在 `PlaybookLoader.FACTOR_FILES` 注册文件名；
3. 需要新查询能力时，在 `capabilities.yaml` 声明 + 实现 `execution/executors/` 下的执行器；
4. 在需要它的骨架 `default_factors` 里挂上（或靠用户怀疑原因自动必选）。

新增场景：

1. 写 `playbooks/skeletons/xxx.yaml`；
2. 在 `PlaybookLoader.SKELETON_FILES` 注册文件名；
3. 在意图识别 prompt 的场景枚举里加入新场景，并补评测标注（eval_case）。

无论哪种，启动时的静态校验会把模板错误（引用不存在的节点/能力、语义输入找不到生产者、
产出字段重复）直接挡在启动阶段。
