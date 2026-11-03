# 闪购商家经营分析助手

一个基于 Java 17、Spring Boot 3.3.5、SQLite 的经营分析 Agent。当前采用 **Playbook 优先、领域内 ReAct 补充** 的执行方式：适合固定流程的问题走经过校验的模板；超出模板覆盖范围的经营问题，由模型结合可选的文字场景说明和按需加载的工具继续分析。

本轮改造已接入三类日级时间意图、Query 改写、覆盖范围校验、4 个文字场景、统一工具运行时、独立计算算子和有预算的 ReAct。当前共 **18 个工具：10 个业务查询工具 + 8 个计算工具**。本工程使用演示数据，尚未实现生产级权限、租户数据隔离和完整因果推断。

## 文档导航

| 文档 | 内容 |
|---|---|
| [系统架构](docs/architecture.md) | 完整链路、模块边界、持久化表和实现入口 |
| [路由与 Query 改写](docs/routing-and-query-rewrite.md) | 五种路由、覆盖契约、需求拆分、追问引用与澄清 |
| [时间定义](docs/time.md) | 三种时间类型、昨日截止、默认基线、冻结与恢复、边界案例 |
| [工具与聚合算子](docs/tools-and-calculations.md) | 工具目录、按需加载、引用、Schema、业务口径和计算协议 |
| [ReAct 执行](docs/react.md) | 动作协议、预算、循环检测、证据校验、降级和恢复 |
| [文字场景](docs/react-scene-candidates.md) | 已落地的 4 个场景及新增 YAML 方法 |
| [Playbook 编写](docs/playbook-authoring.md) | 骨架、因子、coverage、依赖与静态校验 |
| [执行与恢复](docs/execution.md) | DAG 并发、证伪、步骤缓存、两条分支的恢复差异 |
| [会话与记忆](docs/conversation-memory.md) | 会话、run chain、历史快照、部分结果与短追问边界 |
| [评测](docs/eval.md) | 接口、当前验证范围、旧版本实验记录及适用限制 |
| [ReAct 与模板的选择](docs/react-vs-plan.md) | 两条路径的职责与取舍 |
| [动态 DAG 设计边界](docs/dynamic-dag-design.md) | 当前实现与将来动态计划能力的区别 |
| [演示数据](docs/data-story.md) | 造数故事、日期与数据截止的关系 |
| [与生产环境的差异](docs/production-differences.md) | 指标、权限、数据、质量和运行限制 |

## 请求分支

`POST /api/analyze` 先判断 `runId`：传入时直接恢复已有任务，忽略 `query`，不重新做意图识别。

新问题用一次 LLM 调用完成 Query 改写、领域判断、需求提取、追问判定，以及 Playbook/文字场景匹配建议。最终路由由代码按以下顺序决定：

1. **OUT_OF_DOMAIN**：不属于店铺经营分析，返回能力范围说明。
2. **CLARIFY**：必要条件、历史指代或时间不可确定，返回澄清问题。
3. **HISTORY**：有效的历史追问，且只需解释、建议或已有事实，不重新执行查询。
4. **PLAYBOOK**：模型建议的 Playbook 通过完整的 coverage 校验，按模板生成并执行 DAG。
5. **REACT**：领域内但没有可覆盖需求的 Playbook。命中文字场景时注入指导文字；没有命中也继续执行。

“在领域内”“命中 Playbook”“命中文字场景”是三个不同判断。没有相应工具的问题也可能属于领域内，最终应返回能力不足或部分完成。不能把原问题改窄后强行塞进模板。

## 时间规则

业务以日为单位，最新可分析日默认是业务时区的昨日。支持：

```json
{"type":"calendar_period","unit":"month","offset":-1}
```

```json
{"type":"recent_days","days":7}
```

```json
{"type":"date_range","start":"2026-10-01","end":"2026-10-15"}
```

自然周期单位为 `day/week/month/quarter/year`，`offset` 为非正整数；最近 N 日以数据截止日结束；显式日期的 `end` 包含当天。内部统一为 `[start, endExclusive)`，SQL 用 `>= start AND < end`。当前自然周期截到可用数据日，历史完整周期不静默截断。

未指定时间默认上月；未指定对比期时由代码推导。已解析时间和参考日保存在运行记录中，新任务恢复时沿用冻结结果。完整规则和闰年、月初、数据延迟等边界见[时间文档](docs/time.md)。

## 工具、因子与聚合

Playbook 中的因子是多步骤业务排查模块，例如“确认下架 → 读取 SKU 两期销售 → 检查同品类变化”。它不等于一个通用聚合算子。

业务工具和计算工具共用 ToolRuntime：先解析结果引用和时间，再校验输入、调用实现、校验输出。ReAct 最初只看全量工具的 `name/description/kind`，通过 `load_tools` 加载所需工具的完整输入、输出定义，加载后才能调用。一次运行最多加载 12 个工具，单批最多 5 个。

计算工具为 `sum/avg/min/max/compare/difference/ratio/relative_change`，使用 BigDecimal，返回可继续引用的十进制字符串。SQL 内的既有业务汇总仍保留；不是把所有 SQL 聚合搬到模型侧。“SKU 影响量化”可组合 `sku_sales_history` 和 `substitute_sku_analysis` 等工具完成，但销售变化贡献率不等于下架、价格或缺货造成的因果损失。

## 启动

需要 JDK 17 和 Maven。

```bash
mvn spring-boot:run
```

默认使用离线 `mock` 模型，端口 `8082`，数据库为当前目录 `merchant_analysis.db`。启动自动执行幂等建表脚本，并在业务数据为空时造数。Mock 用于演示和流程验证，不能代表真实模型的理解与决策质量。

使用真实的 OpenAI 兼容服务：

```bash
export LLM_PROVIDER=openai
export LLM_API_KEY='your-api-key'
export LLM_BASE_URL='https://dashscope.aliyuncs.com/compatible-mode'
export LLM_MODEL='qwen-plus'
mvn spring-boot:run
```

业务时间配置：

```bash
export BUSINESS_TIME_ZONE='Asia/Shanghai'
# 可选：实际数据延迟时设置可用截止日；必须早于业务参考日。
export BUSINESS_DATA_THROUGH='2026-11-01'
```

截止日是配置约定，系统不会自动查询数仓判断数据是否齐备。固定日期配置不会自行每天推进。ReAct 预算位于 [application.yml](src/main/resources/application.yml)，定义见 [ReAct 文档](docs/react.md)。

## API 示例

```bash
# 固定流程：全店销售额变化诊断
curl -s http://localhost:8082/api/analyze -H 'Content-Type: application/json' \
  -d '{"query":"为什么上个月销售额下滑了，跟下架的无糖可乐有关吗？","merchantId":"demo","userId":"u1"}'

# 领域内扩展任务：通常进入带文字场景的 ReAct
curl -s http://localhost:8082/api/analyze -H 'Content-Type: application/json' \
  -d '{"query":"上个月发生过缺货的商品里，哪些销售也下降了？","merchantId":"demo","userId":"u1"}'

# 承接已有会话：sessionId 用上一次响应中的实际值替换
curl -s http://localhost:8082/api/analyze -H 'Content-Type: application/json' \
  -d '{"query":"这几个商品再看一下价格变化","merchantId":"demo","userId":"u1","sessionId":"实际 session_id"}'

# 恢复任务：runId 用实际值替换，query 可省略
curl -s http://localhost:8082/api/analyze -H 'Content-Type: application/json' \
  -d '{"runId":"实际 run_id"}'
```

上述自然语言问题的实际路由取决于意图识别输出与代码校验，不是固定关键词接口。请求支持 `query/runId/merchantId/userId/sessionId`；默认商家、用户标识为 `default`。

主要响应字段：`status/routing/original_query/resolved_query/understanding/plan/step_results/hypothesis_broken/conclusion`。新轮次附 `session_id/turn_id/is_follow_up/follow_up_type/ref_run_id`；恢复响应不保证附带这些会话字段。

| 路径 | status 与运行标识 |
|---|---|
| Playbook | `SUCCESS`；新执行有 `run_id`，步骤通常为 `S1…` |
| ReAct | `RUNNING/COMPLETED/PARTIAL/CAPABILITY_MISSING/BUDGET_EXCEEDED/LOOP_DETECTED/FAILED`；有 `run_id`、`coverage/react_stats/trace_url`，步骤为 `T1…`，历史导入为 `H1…` |
| 澄清 | `NEEDS_CLARIFICATION`；`run_id=null` |
| 领域外 | `OUT_OF_DOMAIN`；`run_id=null` |
| 读历史 | 来源成功为 `SUCCESS`，部分来源为 `PARTIAL`，不可用为 `HISTORY_UNAVAILABLE`；返回 `ref_run_id/source_status`，不新建 run，响应没有新 `run_id` |

ReAct 的 `plan={"scene":"react","steps":[]}` 是分支标记，不是预生成的工具 DAG。API 的 `COMPLETED` 在 `analysis_run` 中映射为 `SUCCESS`，其余 ReAct 终态保留同名状态。

只读接口：

```bash
curl -s http://localhost:8082/api/react/实际run_id
curl -s 'http://localhost:8082/api/conversations?merchantId=demo&userId=u1&limit=50'
curl -s http://localhost:8082/api/conversations/实际session_id/turns
```

轨迹接口返回运行、状态和事件；事件的 `input_json/output_json` 为 JSON 字符串。会话列表返回 `session_id/title/topic_summary/status/last_active_at/created_at`。这些演示接口未实现生产级鉴权。

非法请求、模型协议错误或未被分支处理的执行异常可能直接返回 HTTP 错误；不能假设每种异常都会返回上述正常分析响应。部分异常会先把已有 run 标为 FAILED，但该持久化状态与 HTTP 响应格式是两回事。

## 验证与已知边界

本轮实现此前已完成 63 项定向自动化回归（Mock 模型、真实执行逻辑、部分内存 SQLite/HTTP 测试），覆盖路由、工具、时间执行、场景、ReAct 超时/恢复/证据与历史引用。独立时间解析和计算算子也有测试。尚未对本轮改造做真实模型端到端验收，不能用旧版本评测数据代替当前模型效果。

可执行 `mvn test` 运行测试；测试说明与定向命令见[评测文档](docs/eval.md)。领域外问题和 ReAct `CAPABILITY_MISSING` 会进入 `bad_query_case`，并非所有部分完成都自动收集。

当前不支持实时数据、任意模型 SQL、完整 SKU 明细遍历、可靠缺货时长/反事实损失、SKU 订单量/AOV、统一量纲证明或自动同比策略。预算、Schema 和证据检查能减少错误，不构成完整的业务语义和因果证明。详见[工具口径](docs/tools-and-calculations.md)与[生产差异](docs/production-differences.md)。
