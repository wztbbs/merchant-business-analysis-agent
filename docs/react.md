# ReAct 分支：协议、预算、完成度与恢复

## 进入条件和模型上下文

只有领域内且没有被其他高优先级分支处理的问题进入 ReAct。没有命中文字场景也执行；命中时仅额外注入 scene_id 和 guidance，不生成固定 DAG、不强制调用 recommended_tools。

决策 prompt 包含：结构化 task、冻结 time、可选文字场景、全量轻量 catalog、当次已加载工具的完整定义、结果预览、调用记录、历史别名绑定、观察反馈和剩余预算。工具结果由真实执行器提供，不让模型自己填入“工具结果”。

当前通过 `LlmClient.chat` 的文本 JSON 动作协议实现；没有接入供应商原生 tool_calls/function-calling，也没有使用 LangGraph。每次决策只能一个动作；load_tools 可一次加载多个定义，call_tool 每次只调用一个工具。

## 四种动作

### 加载工具

```json
{"action":"load_tools","names":["sales_summary_query","sku_sales_history","ratio"]}
```

名称必须在白名单。单批最多 5 个不重复工具，一次运行最多加载 12 个；重复加载已加载定义无新增进展。先加载再调用，跨运行的 Schema 缓存不绕过当前加载权限。

### 调用工具

```json
{"action":"call_tool","tool":"sales_summary_query","arguments":{}}
```

工具参数按已加载定义校验。省略时间时使用本次冻结窗口；新调用的编号由服务端生成 `T1/T2…`，错误调用也占一个编号和工具尝试次数。

```json
{"action":"call_tool","tool":"sku_sales_history","arguments":{"sku":"无糖可乐","metric_delta":{"$ref":"T1.metric_delta"}}}
```

计算、金额、商品身份优先引用上游真实数据；显式商品名可来自用户原问题。引用和时间在工作线程启动前绑定为不可变快照。执行器读取独立上下文快照；只有主线程收到有效返回、验证输出后，才提交结果。

### 检查已有结果

```json
{"action":"inspect_result","reference":"T2.category_breakdown","offset":0,"limit":20}
```

初始预览的数组最多 10 项、对象最多 30 个字段、深度最多 6、长字符串最多 1000 字符。结果预览中的 items/count/truncated 是展示包装，不改变真实引用路径，不能把它误当成工具实际输出结构。

inspect 不再查询数据库。数组 offset>=0 且不超过真实长度，limit 为 1～20；返回 reference/offset/count/items/truncated。非数组只允许 offset=0，返回 reference/value。单次窗口最多保留 20 项，每项内部仍受预览限制。

第一次查看某个 `reference:offset:limit` 被视为进展；重复窗口不算。长列表全量断言需要足够检查范围，或者直接把完整集合引用交给计算工具得到汇总标量；不能只看前 10 行就宣称完成全量排名。

### 提交完成度

```json
{
  "action":"finish",
  "status":"COMPLETED",
  "reason":"已取得两期全店销售额",
  "requirements":[{
    "index":0,
    "status":"completed",
    "evidence":["T1.current_total","T1.baseline_total"],
    "reason":"两期金额来自销售汇总工具"
  }]
}
```

模型只能提议 COMPLETED、PARTIAL 或 CAPABILITY_MISSING；每个原始 requirement 必须恰好有一项，index 从 0 开始，不可遗漏、重复、越界。子项状态为 completed/partial/missing，必须给出 reason 和 evidence 数组。完成示例仅适用于相应需求，不是适用于任意问题的通用完成模板。

协议拒绝未知动作、额外字段和非法字段类型；动作文本最多 32000 字符，字符串数组最多 100 项，其他短字符串也有限长。协议/参数错误作为 observation 反馈给模型，有界修复；模型连接失败和超时不走无限重试。

## 默认预算

配置来自 [application.yml](../src/main/resources/application.yml)，由 ReactSettings 在首次运行时校验并快照保存。

| 配置 | 默认值 | 含义 |
|---|---:|---|
| max-model-calls | 12 | ReAct 内模型调用总数，包含一次收尾回答；最多 11 次决策 |
| max-tool-calls | 20 | 工具尝试总数，包括失败、重复缓存复用；超限拒绝的第 21 次不再计入 |
| max-no-progress | 3 | 连续无进展轮数 |
| max-same-errors | 2 | 同一个错误指纹出现次数 |
| max-loaded-tools | 12 | 当前运行最多已加载定义数 |
| max-load-batch | 5 | 单次加载的不同工具数 |
| total-timeout-ms | 90000 | 从 ReAct 状态创建起的时间预算 |
| model-timeout-ms | 20000 | 单次模型调用等待上限 |
| tool-timeout-ms | 5000 | 单次工具调用等待上限 |
| answer-reserve-ms | 10000 | 决策/工具执行阶段预留的收尾时间 |
| max-prompt-chars | 120000 | 单次 prompt 字符数上限，不是 token 数 |

load、inspect、错误修复同样需要模型决策，均消耗 max-model-calls。收尾最多再调用一次，不会因为回答校验失败再开启新一轮修复。

这些预算**不包含前面的意图识别调用**，也不是整个 HTTP 请求严格 90 秒 SLA：历史准备、JDBC 状态持久化、prompt 序列化、完成度检查等并非全部可抢占。当前没有独立 token/金额预算。调大预算只能增加尝试空间，不能自动补齐数据能力。

## 防止重复和打圈

工具调用指纹为 SHA-256（工具名 + 已绑定、已规范化日期的真实参数，JSON key 排序）。相同参数在本次 run 已成功执行过，则复用结果、新建 T 别名并记录 CACHED，不再物理查询；仍消耗工具尝试和模型调用次数，并算无进展。来源 ID 本身不单独入指纹，真实值相同就可能复用。没有跨 run 结果缓存。

进展的定义是：新增工具定义、首次成功的物理工具调用、首次 inspect 窗口。重复加载、缓存调用、错误增加无进展计数；例如 A→B→A→B 可被缓存及无进展规则截断。错误指纹由名称、原始输入、错误信息组成，重复两次触发 LOOP_DETECTED。

限制也要明确：不同参数但返回相同内容仍可能算进展；初次 inspect 已预览内容仍算进展；修改错误参数可能改变错误指纹。它不是完备的语义循环检测器，模型/时间/工具硬预算是最后边界。

模型和工具各自最多 4 个工作线程，采用无队列的有界执行池；超时通过 Future 取消。中断不保证外部供应商或 SQL 物理停止、也不保证停止计费，但迟到结果不能绕过主线程提交路径。工具超时终止为 BUDGET_EXCEEDED；普通工具错误可在剩余预算内修复。模型调用异常通常立即 FAILED，模型超时为 BUDGET_EXCEEDED。

## 完成度核验

模型说“完成”不直接等于完成。ReactCompletion 按每个需求检查：

- evidence 路径真实存在且非 null；空列表可以表示确实没有记录，需结合需求解释。
- 业务字段的 x-metrics/x-scopes 与需求指标、粒度相符。
- 观察期/基线与需求时间一致；指定商品与调用参数或可信事实中的实体一致。
- 指定 SKU/多 SKU 的问题不能只拿全店汇总证明完成。
- comparison 类型需要相应计算工具根结果作为证据，不能只让模型心算后宣称已对比。
- forecast 需要 x-actions 中的预测证据；当前没有预测工具。
- 引用超过预览长度的整份列表时检查查看覆盖，防止拿局部当全量。
- stockout_sales_crosscheck 对去重后的缺货商品集合检查逐项非空销售查询，未完成全部核查不能说全量完成；无事件可作为真实空集合处理。

计算结果沿调用引用追溯业务来源，历史导入也保留来源参数和 lineage。这些是有限的结构化证据检查，不是完整因果/量纲/单位证明，不保证理解所有自然语言子条件。

仅当原始需求全部通过核验且模型请求 COMPLETED，才完成；显式请求 PARTIAL 不自动升级。CAPABILITY_MISSING 只有在无已覆盖子项且轻量能力声明也不满足时成立，其他缺证据情形通常 PARTIAL。未经过 finish 就预算终止时，未验证的子项标 missing，即使 step_results 已有部分可读事实。

## 回答和降级

收尾 prompt 带事实、日期、最终状态和覆盖情况。生成回答后仅采用现有 GroundingChecker 的 number_grounding、entity_grounding 两项作为 ReAct 在线校验，并额外检查非完成状态下部分常见“全部完成”措辞。不是五项 checker 全部作为在线硬门槛，也不是完整语义审查。

回答生成异常、超时、无预算或校验未通过时，使用确定性的事实摘要降级，不无限调用模型。原本 COMPLETED 的运行若完整回答无法生成，会降为 PARTIAL；其他终态保留。非完成回答会明确标记“本次分析未全部完成”，不能把预算耗尽包装成成功。

## 状态与 API

| API status | 含义 | analysis_run.status |
|---|---|---|
| RUNNING | 同一进程已有执行中的同 run；提示稍后查询 | RUNNING |
| COMPLETED | 全部需求验证通过且回答可交付 | SUCCESS |
| PARTIAL | 部分可回答或完整回答交付失败 | PARTIAL |
| CAPABILITY_MISSING | 当前工具缺少所需能力 | CAPABILITY_MISSING |
| BUDGET_EXCEEDED | 次数/时间/上下文等预算耗尽 | BUDGET_EXCEEDED |
| LOOP_DETECTED | 连续无进展或重复错误 | LOOP_DETECTED |
| FAILED | 模型或运行异常 | FAILED |

响应附 coverage、react_stats（model_calls/tool_calls/loaded_tools/reason/started_at/deadline）和 trace_url。started_at/deadline 为 epoch 毫秒；loaded_tools 是名称列表，不是数量。

`GET /api/react/{runId}` 返回 run、state、events；events 的 input_json/output_json 为 JSON 字符串。MODEL/RECEIVED 只表示收到响应，不表示动作合法或业务结论正确。事件类型包括 MODEL、LOAD_TOOLS、TOOL、INSPECT、FINISH、ACTION_ERROR、HISTORY、RUNTIME、ANSWER，TOOL 可为 SUCCESS/CACHED/FAILED。

## 持久化与恢复

- `analysis_run` 保存理解、冻结时间和空 react Plan 标记；`step_run` 保存成功/失败工具尝试及历史快照；`react_run` 保存预算、状态、定义加载集合、调用指纹/来源、检查窗口、覆盖和回答标志；`react_event` 保存轨迹。
- 预算快照、startedAt、固定 deadline 不因 resume 重置，也不拾取修改后的新配置。过期恢复不会获得新的 90 秒和 12 次调用。
- Hn 是选中历史 step 根结果的本次快照，保留绑定路径、来源时间和来源工具；来源 ReAct 的派生计算可递归追溯原始业务事实。不能在恢复时用今天重新查询替代历史商品集合。
- 已完成运行直接返回缓存结果；崩溃在 react_run 已写终态但 analysis_run 未收尾时，恢复可校正外层状态。
- `answerAttempted` 在付费收尾调用之前持久化，避免崩溃后再次花费同一次收尾预算；可能因此恢复时只能给事实降级回答。
- 模型/工具次数在尝试前落库。成功 step 已写但调用元数据未保存的崩溃窗口，恢复可能重新执行只读查询；不是 exactly-once 执行，也不是恢复到每条程序指令。
- active-run 集合只保护同一进程并发恢复，不是多实例分布式锁。

涉及完整历史链和部分结果的资格规则，见[会话文档](conversation-memory.md)；固定模板恢复见[执行文档](execution.md)。
