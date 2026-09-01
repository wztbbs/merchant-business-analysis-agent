# 闪购商家经营分析助手（merchant-business-analysis-agent）

这是一个用固定分析模板约束 LLM 的商家经营分析 Agent：LLM 不直接决定查什么，只负责理解问题和
生成表达。

用户用自然语言提问，比如"为什么上个月销售额下滑了，跟下架的无糖可乐有关吗？"，系统会先判断
用户想问什么，再按固定的分析流程生成查询计划，查完数据后输出一段能解释清楚原因的结论。

核心原则：**分析步骤由预先配置好的模板决定，不让 LLM 临时决定怎么查；LLM 只负责理解用户问题，
以及把查询结果整理成可读结论。**

## 这个系统解决什么问题

商家在经营中经常问三大类问题：

- 指标为什么变了（"上个月销售额为什么下滑"）→ 指标变化诊断；
- 某个动作影响大不大（"我把无糖可乐下架了影响大吗"）→ 因子影响评估；
- 某个指标是多少（"上个月销售额是多少"）→ 指标查询报表。

系统的回答必须数字准确、链路可查——商家可能会根据结论去补货、调价或投放广告，所以宁可明确说
暂时无法回答，也不能给出一个看起来合理但数字错误的结论。因此所有计算、数据传递和分析步骤都由
代码控制，LLM 只参与"理解问题"和"生成最终表达"两个环节。

## 核心设计：固定分析流程 + 可按需增加的原因排查模块

- 每类问题对应一个**场景模板**（skeleton）：固定的分析路线，如"确认事实 → 贡献拆解 →
  逐个排查原因 → 综合结论"。
- 每一种可用数据验证的原因，都做成一个独立的**原因模块**（factor），例如下架、活动、流量、
  价格、缺货。这些模块可以插入不同分析流程中复用。
- 每次分析都会先选定一个场景模板，再加入用户明确提到的原因和系统默认排查的原因，最终生成
  一份完整的**执行计划**（plan）。
- 执行计划在查询数据库前会先做规则校验，校验失败就直接停止，不会访问数据库。整个流程固定
  只调用 2 次 LLM。

## 一次请求是怎么跑完的

```text
第一层：理解问题
用户问题（可带 merchantId/userId/sessionId）
  │
  ▼
获取最近相关的历史分析记录：如果用户是在追问，就找出最近可承接的分析；
如果是首次访问或没有近期会话，则不带历史上下文。
  │
  ▼
意图识别会用一次 LLM 调用，判断用户要问的问题类型、指标、时间范围和怀疑原因；
如果是追问，也会在这一步判断它接的是哪一次历史分析（详见「会话与追问」一节）

第二层：生成并执行计划（纯代码，不调 LLM）
场景模板 + 原因模块
  │
  ▼
生成执行计划
  │
  ▼
校验执行计划
  │
  ▼
调用数据查询能力（SQL）

第三层：输出结果
节点结果
  │
  ▼
用 LLM 把查询结果整理成用户能看懂的分析结论
  │
  ▼
写会话轮次，返回给用户
```

对于只追问原因细节、解释或建议的问题，例如用户先问"上个月销售额为什么下滑"，再追问"那无糖可乐
下架具体影响了多少？"，系统会优先复用被承接的历史分析结果，不重新查询数据；如果用户更换了
指标、时间范围或对比口径，则会生成新的分析任务并重新执行（见「会话与追问」一节）。

## 核心概念

**场景（scene）**
含义：系统先判断用户的问题属于哪种分析类型。
例子："销售额为什么下降" → 指标变化诊断（metric_change_diagnosis）。
代码/配置：只支持这几类场景：`metric_change_diagnosis`、`factor_impact_evaluation`、
`metric_query_report`；不属于这些类型的问题会归为 `out_of_scope`（暂不支持的问题，进需求收集，
见下文），由意图识别判定。

**场景模板（skeleton）**
含义：每一类问题都有一套固定的分析步骤。
例子：指标变化诊断 = S1 确认变化事实 → S2 贡献度拆解 → 排查原因 → S99 综合结论（S 编号为运行时编号）。
代码/配置：`playbooks/skeletons/*.yaml`，声明固定节点、原因排查插入点、收尾节点。

**原因模块（factor）**
含义：一个可以用数据单独验证的原因假设。
例子：下架（delisting）模块 = 确认下架事件 → 量化下架影响（含替代效应）。
代码/配置：`playbooks/factors/*.yaml` + 同名 .md 分析思路文档（供结论生成拼 prompt）。
如果用户在问题里明确提到了某个原因，例如"是不是因为无糖可乐下架"，这个原因会被记为:
**用户怀疑原因**（`candidate_factor`），并且一定会被纳入分析；如果系统查不到支持该原因的
数据证据，就会标记为 `hypothesis_broken`，表示用户提出的这个假设目前不成立。

**原因排查插入点（factor_slot）**
含义：场景模板中预留的位置，用来插入需要排查的原因模块。
例子：诊断模板声明"位于 S2 之后，默认挂活动 / 流量 / 价格 / 缺货"。
代码/配置：骨架 YAML 的 `factor_slot` 字段。

**指标（metric）**
含义：分析对象。
例子：销售额 / 订单量 / 客单价。
代码/配置：枚举 `sales_amount` / `order_count` / `aov`。

**数据查询能力（capability）**
含义：执行计划中的每个节点只能调用预先登记好的查询能力，不能随意拼接或新增查询。
例子：`sales_summary_query` 汇总销售额与环比变化。
代码/配置：`capabilities.yaml` 声明入参、产出字段（produces）、执行器 bean；
实现在 `execution/executors/`（JdbcTemplate SQL 聚合）。

**执行计划（plan）/ 计划节点（step）**
含义：系统把场景模板和原因模块组合起来后，生成的一份完整执行步骤表。每个节点都会声明它依赖
哪些前置节点（`depends_on`），以及需要使用哪些前置节点的输出数据（`inputs`，用
"上游节点.产出字段" 的方式引用）。
代码/配置：`Plan` / `PlanStep`；这些节点会形成一个 DAG，也就是有先后依赖关系、但不会出现
循环依赖的执行流程。模板里步骤用语义化的定义 id（如 `confirm_change_fact`），生成执行计划时，
`PlanWeaver` 会按照执行顺序给节点分配编号，例如 S1、S2。

**运行记录（run）**
含义：一次分析任务的执行记录。用户可以用 `runId` 重新执行，系统会跳过已经成功的步骤，只补跑
失败或未完成的步骤。
代码/配置：`analysis_run` / `step_run` 两张表。如果追问需要重新分析，新生成的分析记录会通过
`root_run_id` 关联到最初那次分析，表示它们属于同一个话题，详见
[docs/conversation-memory.md](docs/conversation-memory.md)。

模板配置之间的关系：

```text
场景
  │
  ▼
场景模板
  │
  ├── 固定节点：确认事实 / 贡献拆解 / ...
  ├── 原因排查插入点 ◄── 原因模块（被插入到场景模板里）
  └── 收尾节点：综合结论
```

用户请求的实际处理流程：

```text
用户问题
  │
  ▼
意图识别
  │
  ▼
生成执行计划
  │
  ▼
校验执行计划
  │
  ▼
执行查询节点
  │
  ▼
生成结论
```

## 执行计划是怎么生成的

以"为什么上个月销售额下滑了，跟下架的无糖可乐有关吗？"为例，`PlanWeaver.weave()`
（纯确定性代码，不调 LLM）分五个阶段：

**阶段一：意图识别**

LLM 会把用户问题解析成生成执行计划所需的结构化参数。这也是整个流程中唯一会影响执行计划内容
的 LLM 环节：

```json
{
  "scene": {"primary_scene": "metric_change_diagnosis"},
  "metric": "sales_amount",
  "time_range": "last_month",
  "comparison_baseline": "previous_month",
  "entity": {"sku": "无糖可乐"},
  "candidate_factor": "delisting"
}
```

从这里开始就是纯确定性代码，同样的输入永远生成同样的执行计划。

**阶段二：选择场景模板**

按场景取骨架 `metric_change_diagnosis`：确认变化事实、贡献度拆解，插入点在贡献度拆解之后，
最后是收尾节点。系统会把模板里的固定步骤转换成实际计划节点，并把模板参数替换成这次请求的
具体值，例如把 `{{time_range}}` 替换成 `last_month`。

**阶段三：确定要排查的原因**

用户明确提到的原因（delisting）会优先排查；系统默认排查的原因（活动 / 流量 / 价格 / 缺货）
会按模板顺序追加进去，并自动去重。

**阶段四：展开原因模块**

把每个原因模块插入执行计划，然后生成计划时，`PlanWeaver` 会自动找到这些数据由哪个节点产出，进而补齐节点依赖关系
（具体写法见 [docs/playbook-authoring.md](docs/playbook-authoring.md)）。

**阶段五：增加收尾节点**

最后的收尾节点会等待前面所有节点执行完成。它本身不查询数据，只用于表示"所有分析步骤已经
完成，可以生成结论"。

示例执行计划（编织后会在服务端日志完整打印）：

```text
S1  确认变化事实      depends_on: []         outputs: [metric_delta, order_delta, aov_delta]
S2  贡献度拆解        depends_on: [S1]       outputs: [top_contributors, category_breakdown]
S3  确认下架事件      depends_on: []         factor: delisting
S4  量化下架影响      depends_on: [S3, S1]   factor: delisting, inputs: {metric_delta=S1.metric_delta}
S5  活动影响对比      depends_on: []         factor: campaign
S6  流量与转化对比    depends_on: []         factor: traffic
S7  价格变化排查      depends_on: [S2]       factor: price（要等 S2 的拆解结果收敛扫描范围）
S8  缺货事件排查      depends_on: []         factor: stockout
S9  综合结论与建议    depends_on: [S1..S8]   无能力，依赖汇聚点
```

执行计划在运行前必须通过 `PlanValidator` 校验；如果校验失败，系统会直接报错，不会查询数据库。
校验内容包括：节点 id 唯一、依赖存在且无环、依赖数据有出处、查询能力在白名单内、产出字段与
能力声明一致。另外 `PlaybookLoader` 在启动时就加载全部模板并静态校验——如果模板配置有问题，
服务启动时就会失败，避免等到用户请求时才暴露错误。

## 执行计划是怎么执行的

执行节点之间有依赖关系，`ExecutionService` 会按批次执行：没有依赖的节点先执行，依赖满足后
再执行下一批。

```text
第 1 批：S1、S3、S5、S6、S8 之间没有依赖关系，可以并发执行
  │
  ▼
第 2 批：S2、S4 并发执行
  │
  ▼
第 3 批：S7 执行
  │
  ▼
第 4 批：S9 汇总
```

执行规则：

1. 没有依赖的节点先执行；
2. 同一批节点互不依赖，提交线程池并发执行；
3. 下一批节点必须等上一批全部完成后才能开始，确保它们能拿到完整的上游数据；
4. 每个节点的执行状态都会保存到数据库。重试时，已经成功的节点不会重复执行，只会继续执行
   未完成的节点；
5. 如果系统发现用户怀疑的原因没有数据支持，例如没有查到对应的下架记录，就会标记为
   "用户假设不成立"，并跳过这个原因后续的分析步骤。

实现细节（拓扑分层算法、并发安全、落库结构、幂等）见 [docs/execution.md](docs/execution.md)。

## 会话与追问

系统支持基于历史分析继续追问，按处理方式分两类：

- **复用历史结果**：追问原因细节、解释或建议时（如"下架影响到底多大？""那我该怎么办？"），
  不重新查询数据，直接引用被承接的历史分析结果，由结论生成 LLM 组织回答；
- **重新执行**：更换指标、时间范围或对比口径时（如"那订单量呢？""那跟前两个月比呢？"），
  系统会沿用上一轮分析的指标、时间、商品等上下文，只替换用户明确修改的部分，然后生成新的
  分析任务重新执行。

追问判定不单独调用 LLM，而是在意图识别这一次调用里一起完成。意图识别时，系统会注入最近可
参考的历史分析。LLM 需要判断：是否为追问、属于哪种追问类型、引用哪次历史分析、是否需要重新
查询数据，并输出补全后的结构化意图。如果 LLM 判断是追问，但返回的历史分析 ID 不在候选范围内，
系统会拒绝该引用，按全新问题处理。

会话相关的实现细节（会话表结构、run chain、候选注入与会话找回策略）见
[docs/conversation-memory.md](docs/conversation-memory.md)。

### 为什么不用 ReAct / 动态 DAG

这里要先区分两个概念：

- **ReAct**：一种让 LLM 反复执行“思考 → 选择工具 → 观察结果 → 再思考”的交互范式。LLM 在运行过程中决定下一步查什么。
- **动态 DAG**：一种“执行图运行时可调整”的编排方式。它不一定等同于 ReAct，也可以是“LLM 先生成一个 plan，代码校验后执行；执行中必要时再改图”。

本项目当前也使用 DAG：执行计划里的节点有 `depends_on`，执行器按依赖关系分批执行。区别在于：**我们的 DAG 在执行前由模板确定，执行中不改图；动态 DAG 则允许运行时加步骤、删步骤或换路线。**
固定模板和动态路线的核心区别：
```text
当前模板方案：                动态路线（如 LangGraph）：

用户问题                     用户问题
  │                            │
  ▼                            ▼
固定模板                     LLM 生成路线
  │                            │
  ▼                            ▼
固定执行路线                  执行中可能调整
  │                            │
  ▼                            ▼
可提前校验                    能回答的问题更多，但分析过程更难控制，稳定性也更低
```

没有优先采用 ReAct / 动态 DAG，主要是因为商家经营分析更看重数字准确、链路可查和结果稳定：

1. **数字传递必须确定**  
   经营分析里大量计算依赖上游结果，例如贡献率要用整体指标变化作为分母。当前方案通过 `inputs` 显式引用上游节点产出，数字由代码传递，不经过 LLM 转述。ReAct 或运行时改图更容易让 LLM 参与中间决策和参数组织，增加抄错数、用错字段、四舍五入导致偏差的风险。

2. **执行路线要能提前校验**  
   当前 plan 在查询数据库前就能做静态校验：能力是否存在、依赖是否成环、输入字段有没有来源、产出字段是否符合声明。校验失败就不执行。动态路线的错误往往只能在执行中或执行后发现，尤其是“少查了一步但结论看起来合理”这类问题，很难事后兜住。

3. **成本和延迟要可控**  
   当前系统固定只调用 2 次 LLM：一次理解问题，一次生成结论。中间查询和计算都由代码完成。ReAct 或动态 DAG 往往需要多轮 LLM 调用，token 成本和响应延迟都会显著上升。

4. **面向普通商家，宁可少答也不能乱答**  
   商家可能根据结论去补货、调价、投放广告。对这类用户来说，最危险的不是系统说“暂时无法回答”，而是给出一份数字详实但链路不可靠的分析。因此当前方案优先保证高频问题稳定、可解释、可复查。

ReAct / 动态 DAG 并不是不好，它们更适合这些情况：

1. 长尾问题很多，固定模板覆盖不了；
2. 查询能力很多，例如 50+ 个能力，手写模板组合成本过高；
3. 分析过程需要边查边改路线，例如某个假设被证伪后自动换方向继续排查；
4. 使用者是专业分析师或运营，能识别并纠正中间过程中的问题。

未来如果接入，更合适的方式是混合模式：**高频、标准化问题继续走模板 plan；低置信度或模板外的经营问题，再用动态 DAG 作为兜底**。
即使走动态 DAG，也应保持“LLM 只有提案权，代码拥有执行权”：LLM 生成或调整执行图，代码负责校验、执行、数字传递和事实校验。
详细取舍见 [docs/react-vs-plan.md](docs/react-vs-plan.md) 和 [docs/dynamic-dag-design.md](docs/dynamic-dag-design.md)。

## 目录结构

```text
src/main/java/com/merchant/analysis/
├── understanding/     理解用户问题：识别分析类型、指标、时间、商品，并判断是否为追问
├── conversation/      管理会话和追问：保存每轮问答，并关联同一话题下的多次分析
├── playbook/          加载分析流程模板和原因模块，并在服务启动时检查配置是否正确
├── plan/              生成和校验执行计划
├── execution/         按依赖顺序执行数据查询，支持并发执行和结果落库
├── conclusion/        生成最终结论（LLM）
├── persistence/       运行记录落库 / 暂不支持问题的收集
├── eval/              意图识别评测与结论校验
├── time/              把"上月""前月""当月"等自然时间转换成具体日期范围
├── llm/               LLM 客户端（真实模型 / 离线 mock 可切换）
└── seed/              演示数据生成

src/main/resources/
├── capabilities.yaml          查询能力目录（白名单）
├── playbooks/skeletons/       3 个场景模板
├── playbooks/factors/         5 个原因模块（yaml + md 分析思路）
├── prompts/                   意图识别 / 结论生成的 prompt
└── db/tables.sql              建表 DDL
```

## 启动方式

```bash
mvn compile
mvn test
mvn spring-boot:run        # 默认使用本地 mock LLM，不需要配置真实大模型即可启动演示；端口 8082，SQLite 文件 ./merchant_analysis.db
```

切换真实 LLM（可以接入任何兼容 OpenAI API 格式的大模型服务）：

```bash
export LLM_PROVIDER=openai
export LLM_API_KEY=sk-xxx
export LLM_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode
export LLM_MODEL=qwen-plus
mvn spring-boot:run
```

## 种子数据故事

项目启动后会自动准备一批演示数据：热销品「无糖可乐」上月 15 号下架、前月有满减活动而上月空窗、
上月流量环比下滑、某 SKU 缺货、某 SKU 涨价——因此，当你用示例问题"销售额下滑跟下架的无糖可乐
有关吗？"测试时，系统可以同时分析下架、活动、流量、价格、缺货等多个原因。演示数据会根据当前
日期动态生成，所以无论什么时候启动项目，"上月"和"前月"都有可查询的数据。
明细见 [docs/data-story.md](docs/data-story.md)。

## curl 示例

```bash
# 示例 1：分析销售额为什么变化，并重点验证用户提到的"下架"原因
curl -s http://localhost:8082/api/analyze -H 'Content-Type: application/json' \
  -d '{"query": "为什么我上个月销售额下滑了，跟下架的无糖可乐有关吗？"}'

# 示例 2：直接查询某个指标的数值
curl -s http://localhost:8082/api/analyze -H 'Content-Type: application/json' \
  -d '{"query": "上个月销售额是多少？"}'

# 示例 3：评估某个具体动作对经营指标的影响
curl -s http://localhost:8082/api/analyze -H 'Content-Type: application/json' \
  -d '{"query": "我把无糖可乐下架了影响大吗？"}'

# 示例 4：基于某次历史分析继续追问，不重新查询数据，直接引用该次分析结果回答
curl -s http://localhost:8082/api/analyze -H 'Content-Type: application/json' \
  -d '{"sessionId": "<上一次请求返回的 session_id>", "query": "那无糖可乐下架具体影响了多少？"}'

# 重试或继续执行：如果请求中传了 runId，系统会忽略 query，并从该分析任务的历史状态继续执行。
# 已经成功的节点不会重复跑；如果整个任务已经完成，则直接返回上次结果
curl -s http://localhost:8082/api/analyze -H 'Content-Type: application/json' \
  -d '{"runId": "<上一次请求返回的 run_id>"}'
```

返回结构核心字段：`{ session_id, run_id, ref_run_id, understanding, plan, step_results, hypothesis_broken, conclusion }`
（另有 `turn_id`、`is_follow_up`、`follow_up_type` 描述会话与追问状态）。其中 `run_id` 表示本轮新执行
的分析任务，`ref_run_id` 表示本轮回答引用的历史分析任务；普通首问通常只有 `run_id`，不重跑的追问
通常只有 `ref_run_id`（此时 `understanding` 和 `plan` 为空）。

## 评测与质量控制

系统会分别评测两次 LLM 调用的效果。评测所需的执行记录直接复用 `analysis_run` 和 `step_run`
表中的数据：

- **意图识别评测**：只测试问题理解环节，不生成计划、不查询数据。系统会把 LLM 输出的场景、指标、
  时间等字段与人工标注逐项对比，计算准确率；
- **结论校验**（grounding）：用规则检查最终回答是否可靠，例如数字是否来自查询结果、商品名称
  是否真实出现、上涨下降方向是否一致。这个过程不调用 LLM。

```bash
# 跑评测：同步执行，响应直接返回完整结果（汇总 + 逐条明细），附 eval_run_id 供事后查询
curl -s http://localhost:8082/api/eval/run -H 'Content-Type: application/json' \
  -d '{"evalType": "intent", "split": "dev"}'      # split 可以不传，不传时跑全量数据；dev 用于调试 prompt，test 用于固定回归测试
curl -s http://localhost:8082/api/eval/run -H 'Content-Type: application/json' -d '{"evalType": "grounding"}'

# 按 eval_run_id 查单批次明细 / 跨批次统计
curl -s http://localhost:8082/api/eval/query/{eval_run_id}
curl -s "http://localhost:8082/api/eval/stats?evalType=intent"

# 批量提交标注数据：系统会按 query 逐条写入，已存在的记录会更新，不存在的记录会新增。某一条失败不会影响其他记录
curl -s http://localhost:8082/api/eval/case -X PUT -H 'Content-Type: application/json' -d '[
  {"query": "为什么上月订单量下降了？", "expectedScene": "metric_change_diagnosis",
   "expectedMetric": "order_count", "expectedTimeRange": "last_month", "split": "dev"}
]'
```

当前结果（qwen-plus）：

| 评测项 | 通过率 | 说明 |
|---|---:|---|
| 意图识别 dev（87 条） | 97.7% | 在 prompt 中补充判定规则后得到该结果 |
| 意图识别 test（41 条，封存回归） | 100% | 使用与 dev 集合相同的 prompt 版本进行测试 |
| 结论校验（最近 50 条真实 LLM 结论） | 86.0% | 剩余失败集中在诊断场景建议段的编造细节（阈值、页面名等），机械校验红利基本吃完 |

评测规则、prompt 版本管理与逐轮实验记录见 [docs/eval.md](docs/eval.md)。

## 需求收集：把暂不支持的问题沉淀成新场景

暂不支持的问题会归为 `out_of_scope`，这些问题不会被直接丢弃，而是会被收集起来，作为后续新增
分析场景的依据：

- 对于暂不支持的问题，意图识别会额外提取动作、对象、实体等结构化信息，方便后续统计，而不是
  只保存一段无法归类的自由文本，连同 query 原文落库 `bad_query_case`；
- 系统会定期统计这些暂不支持的问题。出现频率高的"动作 + 对象"组合，会优先考虑做成新场景；
  排期时再结合原始问题理解具体需求；
- 要支持一个新场景，通常需要新增一个场景模板 YAML；如果现有模块不够，还需要新增原因模块或
  数据查询能力。之前收集的用户原始问题，也可以作为新场景的评测样本来源。

## 开源版本定位

这个工程抽象自真实商家经营分析项目，但为了便于公开、部署和理解，去掉了公司内部的数据口径、
业务规则、权限体系和工程依赖，只保留核心分析链路：意图识别 → 固定模板生成计划 → 执行数据
查询 → 生成可追溯结论。

真实环境中的指标层、权限体系、数据接入和质量评估会复杂得多，具体差异见
[docs/production-differences.md](docs/production-differences.md)。

## 已知简化点

- 演示指标只有 3 个（销售额 / 订单量 / 客单价），时间表达只支持"上月""前月""当月"；
- 目前还没有实现系统主动追问用户来补充信息的交互（假设证伪或缺参时仅标记"用户假设不成立"）；
- 接口仍是同步返回，还没有改成异步任务模式（运行状态已持久化，支持断点续跑）；
- 当前只使用一个 SQLite 数据库（WAL 模式），暂时没有实现权限控制。

## 深入阅读

- [docs/architecture.md](docs/architecture.md) —— 系统架构与各模块职责
- [docs/playbook-authoring.md](docs/playbook-authoring.md) —— 如何编写场景模板与原因模块（含语义引用写法）
- [docs/execution.md](docs/execution.md) —— DAG 执行、并发模型与断点续跑
- [docs/conversation-memory.md](docs/conversation-memory.md) —— 会话与追问的实现细节
- [docs/react-vs-plan.md](docs/react-vs-plan.md) —— 为什么不优先用 ReAct
- [docs/dynamic-dag-design.md](docs/dynamic-dag-design.md) —— 动态 DAG 的取舍与未来接入方案
- [docs/eval.md](docs/eval.md) —— 评测体系与实验记录
- [docs/data-story.md](docs/data-story.md) —— 演示数据明细
- [docs/production-differences.md](docs/production-differences.md) —— 开源版本与真实生产环境的差异
