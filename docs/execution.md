# 执行、持久化与恢复

## 固定 Plan 执行

Playbook 路由经 PlanWeaver 和 PlanValidator 得到完整 DAG，再创建 analysis_run 和预置为 PENDING 的 step_run。ExecutionService 使用 Kahn 拓扑分层，同层步骤交给固定 4 线程池并发执行，整层返回后再执行下一层。

一个步骤可调用多个 capability；每个工具先从共享参数投影自身输入，再通过 ToolRuntime 绑定引用、消解日期、校验和执行。工作线程返回步骤产出，主线程按控制流程提交上下文与证伪结果，避免多线程边查边修改共享分析状态。

“销售变化诊断 + 无糖可乐下架候选”的当前 9 步展开为：

| 步骤 | 内容 | 主要依赖 |
|---|---|---|
| S1 | 销售变化事实 | 无 |
| S2 | SKU/品类销售贡献榜 | S1 |
| S3 | 下架事件核查 | 无 |
| S4 | SKU 销售变化和同品类变化 | S3、S1 的 metric_delta |
| S5 | 活动核查 | 无 |
| S6 | 流量/转化核查 | 无 |
| S7 | 价格核查 | S2 的 top_contributors |
| S8 | 缺货流水 | 无 |
| S9 | 综合结论屏障 | 全部前序 |

因此通常拓扑层为 `{S1,S3,S5,S6,S8} → {S2,S4} → {S7} → {S9}`。编号取决于实际骨架和因子选择，不是固定 API 常量。S9 本身没有业务取数，实际 LLM 结论发生在执行服务返回后。

## 证伪不是整个运行失败

用户 candidate_factor 的确认结果满足该因子 `when_output_null` 时，设置 hypothesis_broken，并跳过同因子后续节点。其他因子继续，最后解释用户假设未获支持和已有事实。它是有限的流程跳过规则，不是对所有业务假设都完备的统计证伪，也不支持任意规则表达式。

状态和步骤产出写入数据库。底层业务 SQL 只读；恢复依赖结构化结果，不通过解析自然语言结论重新构造数据。

## Playbook 恢复

`POST /api/analyze {"runId":"实际run_id"}` 优先于 query：读取原 understanding 和 Plan，不重新识别意图或重选分支。

- analysis_run 已 SUCCESS：直接返回历史成功步骤与结论。
- 未成功：恢复成功步骤输出，跳过这些查询，重跑失败/待执行步骤；重放已有证伪结果，完成后重新生成结论。
- 保存了 resolved_time 的运行沿用当时窗口；旧记录没有原参考日时，不能保证跨月相对日期可完全复现。
- 不会仅因 query 同文就去重创建新 run；用户要恢复必须传入已有 runId。

Playbook 当前没有与 ReAct 相同的总时限、最大模型轮数和动态重复调用预算；不能把一条分支的保护泛化成全系统 SLA。

## ReAct 执行与恢复的区别

ReAct 的 Plan 为 scene=react、steps=[] 标记，工具步骤在执行时创建，编号 Tn，历史快照 Hn。它不是提前编织好的 DAG，不能用固定 Plan 的 PENDING 节点恢复机制替代 react_run 状态。

ReAct 保存并恢复原预算、固定 deadline、计数、已加载定义、成功调用、缓存指纹、历史别名、检查窗口、覆盖度、收尾标志和终态。过期不刷新额度，已完成直接返回结果；模型/工具次数在尝试前保存，避免崩溃后免费增加次数。

超时工具在独立快照运行，主线程才有提交权限，迟到结果不进入成功事实。外部服务物理取消、分布式并发和 crash exactly-once 尚未实现。完整规则见[ReAct](react.md)。

## 数据与审计位置

| 位置 | 内容 |
|---|---|
| analysis_run.understanding_json | 原始/改写问题、需求、路由、冻结时间 |
| analysis_run.plan_json | 固定 DAG 或 ReAct 空计划标记 |
| step_run | 状态、成功输出、错误；ReAct 的 H/T 步骤也在此 |
| react_run | 动态循环状态和预算快照 |
| react_event | 决策、加载、调用、检查、完成核验、回答事件 |
| conversation_turn | 原问题、改写问题、run/ref_run 和独立回答 |

ReAct 轨迹可含完整 prompt、参数和输出，数据可能比模型初始预览更完整。没有在轨迹写入 API key 请求头；业务内容仍需生产权限和保留策略。当前 SQLite 和同步 HTTP 是演示实现，不是分布式工作流引擎。
