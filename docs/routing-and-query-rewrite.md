# 路由、Query 改写与意图契约

## 一次理解调用做什么

`QueryUnderstandingService` 在发起模型调用前捕获业务 `TimeContext`，并准备三类上下文：Playbook 描述与 coverage、文字场景摘要、最多两条候选历史链尾及有界事实预览。它不在此阶段注入全量工具 Schema 或文字场景完整 guidance。

同一次 LLM 调用负责：

1. 保留原问题语义，补齐可确认的历史实体、时间、指标，生成 `resolved_query`。
2. 判断是否属于店铺经营分析 `in_domain`，并拆成一个或多个 requirements。
3. 判断追问类型、引用来源及是否需要重新取数。
4. 提议 `matched_playbook` 和 `matched_react_scene`。

模型只提议匹配。代码负责历史引用检查、时间解析、coverage 和最终路由。模型提供的 `routing/resolved_time` 会被移除并由服务端重建；`task.original_query` 强制使用实际请求原文。结构化检查不能完全保证语义改写正确，仍需要真实模型验收。

## 结构化任务

`QueryUnderstanding` 保留 `business_intent/metric/time_range/comparison_baseline/entity/candidate_factor/scene` 等原字段，并增加 `task/resolved_time/routing`。

```json
{
  "original_query":"这几个商品最近7天卖得怎么样？",
  "resolved_query":"查询上次结果中指定商品最近7个可用自然日的销售额，并与前7日比较。",
  "in_domain":true,
  "requirements":[{
    "action":"query",
    "metric":"sales_amount",
    "scope":"dynamic_skus",
    "entities":[]
  }],
  "fact_references":[{"run_id":"实际历史run_id","path":"S2.top_contributors"}],
  "needs_clarification":false,
  "clarification_question":null
}
```

这是 `task` 结构示例，不是可直接作为 `/api/analyze` 请求提交的参数。示例历史 run/path 必须由候选上下文中的真实结果替换。

| 字段 | 含义 |
|---|---|
| `requirements[].action` | `query/diagnosis/evaluation/comparison/forecast/advice/explain` |
| `requirements[].metric` | 开放的 snake_case 指标标识；只有 advice/explain 可为 null，不能为了命中模板改成另一个指标 |
| `requirements[].scope` | `shop/sku/category/multi_sku/dynamic_skus/other` |
| `requirements[].entities` | 明确的实体集合；动态集合可由 fact_references 指定 |
| `requirements[].time_range/comparison_baseline` | 可选子需求时间；未设置时沿用全局时间 |
| `fact_references` | 历史 run 与结构化路径；服务端验证真实存在，不能凭空编造 |
| `resolved_time` | 服务端解析后的参考日、截止日、时区和实际日期区间 |
| `routing` | `route/matched_playbook/matched_react_scene/reason` |

不要只保留“主问题”：例如“查销售额，再看缺货商品订单量”不能因为第一项可查就按全店报表模板执行。原始方向词、对比期、商品集合及每个子需求都应保留。

## 最终路由优先级

| 顺序 | 条件 | 处理 |
|---|---|---|
| 1 | `in_domain=false` | `OUT_OF_DOMAIN`，清除追问元信息，返回经营能力范围说明 |
| 2 | 任务需要澄清 | `CLARIFY`，返回问题，不建分析 run |
| 3 | 追问锚点有效且 `needs_rerun=false` | `HISTORY`，只使用历史成功步骤回答 |
| 4 | 建议 Playbook 通过覆盖校验 | `PLAYBOOK`，确定性编织计划 |
| 5 | 其余领域内任务 | `REACT`，可有或没有文字场景 |

未知文字场景 ID 不阻断领域内 ReAct，会被忽略。模型没有建议 Playbook 时，不会靠旧的 `scene.primary_scene` 自动套模板。`scene.primary_scene` 是兼容的业务分类字段，最终控制分支的是 `routing.route`，不能用两者互相替代。

领域内但缺少工具，例如“预测下周销量”“计算缺货造成的损失”，应进入 ReAct 尝试并明确能力边界，不应因为工具缺少就被标为领域外。

## Playbook coverage

| Playbook | action | metric | scope | candidate_factor | 最多子需求 |
|---|---|---|---|---|---|
| `metric_query_report` | query | sales_amount、order_count、aov | shop | 不接因子 | 3 |
| `metric_change_diagnosis` | diagnosis | sales_amount | shop | delisting、traffic、campaign、price、stockout | 1 |
| `factor_impact_evaluation` | evaluation | sales_amount | sku | delisting；要求一个明确 SKU | 1 |

`PlaybookMatcher` 除名称白名单外还检查：

- requirements 不为空，数量、action、metric、scope 全部覆盖。
- 用户候选因子在声明范围，必须实体/因子已补齐；下架评估/假设不能缺少 SKU。
- 全局 metric 与实际子需求一致；要求单 SKU 时只能有一个匹配 `entity.sku` 的实体。
- 全店需求不能偷偷带商品过滤；动态历史事实集合不能塞进没有集合输入的固定模板。
- 子需求独立时间与全局 TimeSpec 不一致时转 ReAct；当前按意图结构比较，不做“不同写法、相同日期”的等价证明。

因此，SKU/品类报表、多个商品逐项复盘、订单量归因、价格影响评估等不会因一个宽泛 `primary_scene` 而被当成当前三个模板已覆盖。现有价格因子还依赖诊断骨架的贡献榜，不可直接插入下架评估骨架冒充已支持价格评估。

## 追问与澄清

追问类型包括 `metric_switch/time_compare/factor_detail/advice/explain/result_reference`。有以下变化会强制重跑：指标、时间、实体、候选因子变化；非纯读历史类型；或任何非 advice/explain 的新需求。重跑仍重新选择 Playbook 或 ReAct，并挂在原 run chain 下。

“这几个/那些商品/它们”等群体指代，如果没有可验证历史引用或明确实体，就澄清。错误 run_id、缺失字段、越界索引都不能悄悄退回全店重新查。

时间无效、越过可用数据截止等可表达成澄清，不把经营问题改为领域外。非法模型协议或无法反序列化的响应仍可能抛出 LlmException，不是所有失败都有自然语言澄清。

仅切换指标、时间意图未变且非 time_compare 的追问可继承原冻结时间；修改时间则按本轮参考日重新解析。详情见[时间](time.md)和[会话](conversation-memory.md)。

## 示例分支

| 问题 | 合理分支及原因 |
|---|---|
| 上个月全店销售额和订单量是多少 | PLAYBOOK，若需求均为 shop query 且无独立时间 |
| 为什么上个月全店销售额下降 | PLAYBOOK，单一 shop sales_amount diagnosis |
| 无糖可乐下架对销售变化贡献多少 | PLAYBOOK，明确 SKU 的下架销售额评估；结论仍非因果估计 |
| 上个月各品类销售结构和差值 | REACT + category_structure_review |
| 缺货的商品里哪些销售也下降 | REACT + stockout_sales_crosscheck |
| 冰淇淋和无糖可乐逐项对比 | REACT + multi_sku_review |
| 用最近 7 天的数据预测销量 | 时间有效且无需澄清时进入领域内 REACT；当前没有 forecast 工具，不能宣称完成预测 |
| 这几个商品如何（无历史） | CLARIFY |
| 帮我写一首诗 | OUT_OF_DOMAIN |

自然语言示例不是关键词硬编码；真实路由还取决于模型提取和覆盖校验结果。
