# 工具定义、按需加载与聚合算子

## 分层与调用链

当前 18 个工具统一注册：10 个 `business` 查询工具、8 个 `calculation` 计算工具。Playbook 与 ReAct 共用同一个运行时，工具不是 ReAct 专属实现。

```text
capabilities.yaml：轻量元数据和供给白名单
  → ToolCatalog.summaries：name / description / kind
  → 按工具名读取 tools/<name>.yaml：input_schema / output_schema
  → 展开 tools/common.yaml 的 common: 引用
  → ToolRuntime.prepare：绑定数据引用 → 检查未知参数 → 时间规范化 → 输入校验
  → 不可变 ToolInvocation
  → ToolRuntime.invoke：再校验输入 → 执行业务/计算 → 校验输出
  → 不可变 ToolExecution：tool、actual arguments、references、output
  → 主线程提交 AnalysisContext / step_run / 运行状态
```

`capabilities.yaml` 包含 name、description、kind、params、produces、executor，以及业务工具 metrics/scopes；模型摘要仅取前三项。完整工具定义包含 name、description、kind、input_schema、output_schema；kind 以注册信息为基础，实际资源文件未必重复书写。

完整 Schema 按需解析并进程内缓存，不是每轮把所有工具接口发给模型。每个 ToolSession 仍有独立的已加载集合，不能因为别的运行加载过某个 Schema 就越权调用。未知工具名先经白名单检查，不能拼接任意资源路径。

ReAct 的完整流程是“看摘要 → load_tools → 看选中定义 → call_tool”。加载动作可一批选多个工具；调用动作每轮只执行一个工具。当前初始摘要仍是全量列表，没有向量检索、检索召回排序或分页工具目录。18 个工具验证的是按需接口加载机制，不能据此宣称已经测过 50 个真实生产工具。

## 结果引用与参数

显式结果引用是一个只包含 `$ref` 的对象：

```json
{"metric_delta":{"$ref":"T1.metric_delta"}}
```

支持对象字段和零起始数组索引，例如 `T2.top_contributors.0.sku_id`。ReAct 当次调用为 Tn，Playbook 为 Sn，导入历史快照为 Hn。调用者不能自行指定新的 T 编号。

- 引用先解析为真实值，再校验输入类型；不是字符串替换，也不执行表达式。
- 缺失 root、缺失字段和越界索引报错；存在但值为 null 会作为 null 继续校验，不能当作 0。
- 引用绑定有最大 32 层嵌套保护。
- Playbook 旧 `inputs` 的 `S1.metric_delta` 在接入运行时后转换成引用对象；PlanValidator 同时验证引用字段供给和依赖链。
- 工具输出和调用快照做防御性复制，避免异步任务修改已提交结果。

业务时间参数统一为 `time_range/baseline`。它们可传三类 TimeSpec 或已解析 DateRange；缺省时沿用本次冻结上下文。独立、无 AnalysisContext 的调用必须提供当前时间。不同当前窗口的默认 baseline 规则见[时间](time.md)。

动态调用拒绝未知顶层参数。Playbook 一个步骤可同时调用多个工具，因此先用 `selectArguments` 把共享参数投影为各工具声明的输入；不能将这一兼容逻辑理解成动态调用也会静默丢弃错参数。

## 业务工具和口径边界

| 工具 | 主要结果 | 关键限制 |
|---|---|---|
| sales_summary_query | 两期销售额、去重订单数、AOV 和各自差值 | 全店口径；无 SKU/品类过滤；订单数为 0 时 AOV 为 0 |
| sku_sales_breakdown | 两期 SKU 并集内，按销售额差值绝对值排序的 Top10 | 含涨跌；不是完整商品明细、纯下降榜或订单量贡献榜 |
| category_sales_breakdown | 各品类两期销售额和差值，按差值绝对值排序 | 没有品类订单数、AOV 或品类完整 SKU 明细 |
| sku_status_query | 观察期末上下架状态、两期覆盖窗内最近下架记录 | 非实时状态；商品未知返回空/unknown，不应推断已下架 |
| sku_sales_history | 指定商品两期销售、基线日均、店内销售占比、差值、变化贡献率 | 未知商品的数值为 null，不是零；没有 SKU 订单数或 AOV |
| substitute_sku_analysis | 排除目标商品后的同品类整体销售变化 | 不提供某个指定替代商品销量，不证明需求转移 |
| campaign_query | 活动记录与带 activity_id 的订单金额 | 不是 ROI、净增量或因果效果 |
| traffic_conversion_query | 曝光、进店 UV 汇总；订单数/UV 转化率及变化 | UV 是日级汇总之和，不能自动视作跨日去重访客；比率和百分数需区分 |
| price_change_query | 两期成交均价变化绝对比例至少 1% 的商品 | SQL AVG(price) 按订单明细行平均，非按数量加权；样本内成交价不等于挂牌价 |
| stockout_query | 观察期全店缺货事件流水 | 没有 SKU 过滤、可靠区间配对、时长或损失估计 |

`price_change_query` 作用域优先级为显式 `sku` > `sku_ids` > `top_contributors` > 全店。显式空集合表示空集合，不回退全店；全店分支按两期销售额之和取 Top10，不是价格变化最大的 Top10。模板价格因子现在显式引用贡献榜，不再隐式扫描历史上下文。

`sku_status_query` 下架记录覆盖 `min(current.start, baseline.start)` 到两期最大终点，所以“查到下架记录”不一定表示下架发生于观察期内；必须结合返回日期解释。

SKU 销售变化贡献率为：

```text
sales_delta = sku_current_sales - sku_baseline_sales
contribution_ratio = sales_delta / shop_metric_delta
```

这个业务比率允许带符号分母，分母缺失或为零返回 null，保留原业务口径。它与要求正分母的通用 ratio 不完全等价。基线日均按 baseline 实际日数计算；占比、金额等继续遵循业务工具原有舍入规则。

部分 SKU 参数为兼容模板允许缺省或 null，可能返回空结果，不能把“Schema 合法”理解成“业务对象已经识别成功”。工具的 metrics/scopes 是能力声明，也不是全维度权限和口径证明。

## 计算算子协议

统一参数外层只能是 `{ "input": {...} }`。算子只处理传入数据，不查数据库、不执行 SQL 或自由表达式，不自行推断指标/单位/时间是否可加。

### 集合运算

`sum/avg/min/max` 支持两种输入。

```json
{"input":{"kind":"values","values":["10.10","20.20",{"$ref":"T1.current_total"}]}}
```

```json
{"input":{"kind":"field","collection":{"$ref":"T2.category_breakdown"},"field":"current_total"}}
```

第二种示例要求上游集合确实具有 current_total 字段；以实际工具输出 Schema 为准，不能凭名字假设字段存在。

集合必须有 1～10000 项。field 只能是直接字段名，不能是点分嵌套路径；缺字段、null 或非数字会整体失败，不跳过坏行，也不悄悄改变参与集合。

| 算子 | 计算与输出 |
|---|---|
| sum | 精确求和，value、count、method=arithmetic_sum |
| avg | 等权算术平均，value、count、method=equal_weight_arithmetic_mean |
| min/max | value、count、零起始 index；field 模式附原始 item；并列取首项 |

平均是等权平均，不适合直接平均 AOV 或转化率来得到总体比率；应先确认分子、分母与权重。min/max 比较字段本身的带符号数值，不能冒充“绝对变化最大”，除非上游明确提供了绝对值字段。

### 双值与变化运算

```json
{"input":{"left":{"$ref":"T1.current_total"},"right":{"$ref":"T1.baseline_total"}}}
```

上述结构适用于 compare、difference、ratio。

```json
{"input":{"current":{"$ref":"T1.current_total"},"baseline":{"$ref":"T1.baseline_total"}}}
```

| 算子 | 规则 | 输出 |
|---|---|---|
| compare | left 与 right 比较 | value 为字符串 -1/0/1，relation 为 less/equal/greater |
| difference | left-right，保留符号 | value |
| ratio | left/right，right 必须 >0 | value、percent=value×100 |
| relative_change | (current-baseline)/baseline，baseline 必须 >0 | value、percent=value×100 |

例如 value=`0.15` 对应 percent=`15`，展示为 15%，不能再次乘 100。没有 annualize、任意公式、自动分组/过滤/排序或独立去重算子；场景中的集合筛选由模型在真实结构化结果上选择并以引用传递，预算不足时应部分完成。

### 数值精度

输入允许有限数字和严格十进制字符串（可科学计数法），拒绝百分号、单位、千位逗号、表达式、布尔值、null、NaN/Infinity。原始文本最长 2048 字符、精度最多 100 位、绝对 scale 最多 1000；这些是资源保护，不是无限精度承诺。

求和/减法使用 BigDecimal；平均和除法用 DECIMAL128（34 位有效数字、HALF_EVEN）。结果去尾零并输出普通十进制字符串，避免 JSON double 二次损失。累加结果若超出下一次输入限制，继续串联也可能被拒绝。

SQL REAL、业务执行器现有 double 和舍入仍存在，后置 BigDecimal 不会恢复已经损失的上游精度。`DecimalMath` 的部分基础运算也供业务执行器复用，但没有强行统一业务零分母和舍入口径。

## Schema 支持范围

`SchemaValidator` 是明确受限的实现，不是完整 JSON Schema 引擎。支持 type（object/array/string/number/integer/boolean/null）、properties、required、additionalProperties、items、anyOf、oneOf、enum、const、minimum/maximum、min/maxItems、min/maxLength、pattern；支持 description 和业务注解 x-metrics/x-scopes/x-actions。定义含未知校验关键字会失败，不能随意加入标准中的其他关键字并假设有效。数字须为有限值。

资源 `$ref: common:<name>` 在读取时展开；调用参数 `$ref: T1.xxx` 则是数据引用，两个概念不同。Schema 校验负责形状和取值范围；证据注解用于 ReAct 完成度检查，不能替代单位、币种、去重、维度可加性、因果和权限规则。

## 新增工具

1. 在 `capabilities.yaml` 注册唯一名称、描述、kind、params、produces、executor；业务工具补 metrics/scopes。
2. 添加 `tools/<name>.yaml`，声明真实输入、输出 Schema，按需复用 common 定义。
3. 业务输出可用于完成需求的字段，补 x-metrics/x-scopes；预测类还需合适 x-actions。只增加名字不会自动获得完成度证明能力。
4. 业务工具实现并注册 CapabilityExecutor Bean；新增计算算子还需扩展 CalculationEngine 的白名单与实现，不是仅写 YAML。
5. 检查调用参数、空值、时间、集合大小、输出校验和证据标签；必要时新增对应场景 guidance。
6. 如需用于 Playbook，再声明步骤供给、依赖、inputs 和 coverage，不自动扩大旧模板范围。

实现入口：[ToolCatalog](../src/main/java/com/merchant/analysis/tool/ToolCatalog.java)、[ToolRuntime](../src/main/java/com/merchant/analysis/tool/ToolRuntime.java)、[ToolSession](../src/main/java/com/merchant/analysis/tool/ToolSession.java)、[CalculationEngine](../src/main/java/com/merchant/analysis/calculation/CalculationEngine.java)、[完整定义目录](../src/main/resources/tools)。
