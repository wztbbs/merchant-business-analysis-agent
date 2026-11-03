# 系统架构

本文对应当前已落地的 Playbook + ReAct 实现。两条分支共享意图识别、冻结时间、工具运行时、步骤落库和会话系统。

## 完整链路

```text
POST /api/analyze
 ├─ runId 非空 → 按原 run.scene 恢复 Playbook 或 ReAct
 └─ 新 query
     → ConversationService：取候选历史链尾和事实预览
     → 捕获 TimeContext
     → QueryUnderstandingService：一次 LLM 调用
          改写 + in_domain + requirements + 追问 + 匹配建议
     → 校验历史指代、时间、Playbook coverage → 最终 RoutingDecision
         ├─ OUT_OF_DOMAIN → 能力范围说明 + bad_query_case
         ├─ CLARIFY → 澄清问题
         ├─ HISTORY → 已有事实 + followup_answer → 回答，不建新 run
         ├─ PLAYBOOK
         │   → PlanWeaver：骨架 + 因子 → PlanValidator
         │   → create analysis_run + PENDING step_run
         │   → ExecutionService：拓扑分层执行 / 证伪
         │   → ToolRuntime → 业务执行器 / CalculationEngine
         │   → ConclusionService → SUCCESS
         └─ REACT
             → create analysis_run（空 react Plan 标记）+ react_run
             → 可选文字场景 + 全量工具摘要
             → 决策 → load_tools / call_tool / inspect_result / finish
             → ToolRuntime → 业务执行器 / CalculationEngine
             → 完成度核验 → 收尾回答或确定性降级
             → 终态 + react_event
```

路由优先级、需求覆盖和 Query 改写见[路由文档](routing-and-query-rewrite.md)。一般成功 Playbook 请求为意图识别、结论生成两次 LLM 调用；读历史通常也是识别、回答两次；澄清/领域外通常一次；ReAct 按预算进行多次调用，不能再描述成全系统固定两次。

## 模块职责

| 包/资源 | 职责 |
|---|---|
| `api/` | 分析与恢复入口、会话列表、ReAct 轨迹 |
| `understanding/` | QueryTask、RoutingDecision、一次调用的理解与改写、PlaybookMatcher、历史事实引用校验 |
| `time/` | TimeSpec、TimeContext、DateRange、ResolvedTime、TimeResolver、显式时间参数 |
| `scene/` | ReactSceneCatalog 发现和校验文字场景，只向识别阶段提供摘要 |
| `playbook/` | 骨架、因子加载；启动静态校验；覆盖范围配置 |
| `plan/` | 模板实例化、因子选择、语义引用解析、运行时 S 编号、DAG 校验 |
| `execution/` | 轻量能力目录、AnalysisContext、固定 DAG 执行、10 个业务执行器 |
| `tool/` | 按需加载完整定义、SchemaValidator、ToolRuntime、ToolSession、数据引用、不可变调用快照 |
| `calculation/` | 通用 BigDecimal 算子与基础十进制运算 |
| `react/` | 决策协议、预算和超时、循环控制、结果检查、完成度、回答降级、状态与事件恢复 |
| `conversation/` | 会话选择、候选 run chain、轮次与 resolved_query 持久化 |
| `conclusion/` | Playbook 结论与历史追问回答 |
| `persistence/` | analysis_run、step_run、bad_query_case |
| `eval/` | 意图字段评测、存量成功结论 grounding、标注和统计接口 |
| `llm/` | LlmClient、兼容 chat/completions 客户端、离线 Mock |
| `seed/` | 业务演示数据初始化 |

资源目录：

```text
src/main/resources/
├── application.yml
├── db/tables.sql
├── capabilities.yaml                 全量轻量工具元数据、供给白名单
├── tools/common.yaml                 复用 Schema（common: 引用）
├── tools/<name>.yaml                 按需读取的完整工具定义
├── playbooks/skeletons/*.yaml        3 个重流程及 coverage
├── playbooks/factors/*.{yaml,md}     5 个排查模块和结论指导
├── react/scenarios/*.yaml            4 个轻量文字场景
└── prompts/*.txt                     理解、模板结论、历史回答、ReAct 决策/回答
```

## 关键边界

- **模板和文字场景不同**：前者确定性编织 DAG；后者只是提示词，模型可以根据结果调整调用顺序。
- **因子和工具不同**：因子组织业务排查；业务工具负责取数；计算工具负责可复用运算。ReAct 直接调用后两者，不直接运行因子 YAML。
- **摘要和 Schema 分层**：启动可校验轻量注册表；模型初始只见摘要，完整定义按工具名加载、缓存；每次 ReAct 仍有独立已加载权限集合。
- **时间先冻结**：识别前捕获参考日，解析后显式传给工具；执行器不自行读取“现在”或隐式扫描上下文选时间。
- **动态调用有界**：模型决定下一动作，代码控制白名单、参数、预算、重复调用、证据、落库和终态。
- **证据并非完整语义证明**：校验指标、范围、时间和实体等条件，但不保证自动证明因果、单位可比性或所有自然语言结论。

## 持久化

[建表脚本](../src/main/resources/db/tables.sql) 当前共 15 张表：

| 分类 | 表 | 作用 |
|---|---|---|
| 业务数据 | `order_item/sku/sku_status_log/activity/shop_traffic_daily` | 销售、商品、状态、活动、流量 |
| 分析运行 | `analysis_run/step_run` | 冻结 understanding、Plan、终态、步骤结果 |
| ReAct | `react_run/react_event` | 预算快照、截止时刻、已加载工具、调用来源、覆盖度、事件轨迹 |
| 会话 | `conversation_session/conversation_turn` | 会话和轮次；原始、改写问题；引用来源 |
| 需求收集 | `bad_query_case` | 领域外和明确能力不足的问题 |
| 评测 | `eval_case/eval_run/eval_case_result` | 标注、批次、逐条结果 |

启动执行 `CREATE TABLE IF NOT EXISTS`。这是轻量建表机制，不是正式的数据库版本迁移框架；旧库的历史冻结字段缺失也不会被自动补出当时参考日。

## 并发与服务边界

固定 DAG 使用 4 个工作线程，同层并发、层间等待，主线程写入分析上下文。ReAct 每次只发一个动作；模型和工具各有最多 4 个有界工作线程，用 Future 等待与取消控制超时，主线程提交成功结果，避免迟到工作线程发布结果。

HTTP 分析接口仍同步返回。进程内 active-run 保护不等于分布式锁；SQLite 写入、模型连接和外部 SQL 的物理取消也不是端到端事务。生产差异见[说明](production-differences.md)。
