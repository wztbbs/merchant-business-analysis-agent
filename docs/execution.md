# Plan 执行：DAG 分层并发 + 状态持久化

plan 的 depends_on 构成有向无环图（DAG）。以诊断 plan（9 步）为例，按真实 depends_on
分出的层与箭头指向：

```
第1层（并发）                第2层（并发）                第3层            第4层

S1 确认变化事实 ──┬──► S2 贡献度拆解 ──────────────────► S7 价格变化排查 ──┐
S3 确认下架事件 ──┼──► S4 量化下架影响（等 S1+S3）────────────────────────┤
S5 活动影响对比 ──┤                                                       ├──► S9 综合结论与建议
S6 流量与转化对比 ─┤                                                       │    （依赖全部前序步骤，
S8 缺货事件排查 ──┴───────────────────────────────────────────────────────┘    汇聚全部前序）
```

即第1层 = S1/S3/S5/S6/S8，第2层 = S2/S4，第3层 = S7（因显式依赖 S2），第4层 = S9——共 4 波。

## 执行模型（ExecutionService）

- **拓扑分层**：Kahn 算法按层出队——每轮 indegree=0 的节点为一层，同层节点互不依赖；
- **层内并发**：同层节点提交到固定 4 线程的线程池一起执行；**层间屏障**等待本层全部完成再调度
  下一层（保证下游的 inputs 永远拿到完整上游产出）。诊断 plan 从 9 步串行变为 4 波；
- **并发安全**：工作线程只做能力调用和状态落库；`AnalysisContext` 的写入与 hypothesis_broken
  置位统一收敛到主线程层间完成，层内 ctx 只读，无需并发容器；
- **inputs 解析**：每个 input（"上游节点.产出字段"）在执行前通过 `ctx.lookup` 解析成实际值，
  以输入名为 key 注入该节点的 params，数值传递不经过 LLM；
- **动态调整**：因子模块在 playbook 声明证伪规则（`falsification`）——用户假设因子
  （candidate_factor）的节点产出命中规则（某产出为 null，如 delisting 查不到下架事件）→
  标记 hypothesis_broken，该因子后续节点记 SKIPPED 跳过（结论输入中注明"用户假设不成立"）；
  默认因子命中规则不标记——被证伪的只能是用户的假设；
- **隐式依赖必须显式化**：`price_change_query` 执行时会隐式复用 S2 的 `top_contributors` 收敛
  扫描范围，因此 `price.yaml` 显式声明 `depends_on: [contribution_breakdown]`（即运行时的 S2）——
  并发模型下不声明此依赖，该节点会与 S2 同层并发，读不到其产出。

## 持久化与断点续跑

- plan 校验通过后即写入 `analysis_run`（RUNNING，含 plan_json/understanding_json）+
  全部节点的 PENDING 预记录；
- 每节点执行中推进状态机 RUNNING→SUCCESS/FAILED/SKIPPED，产出落 `step_run.outputs_json`；
- 断点续跑：`POST /api/analyze {"runId": "..."}` 恢复执行——SUCCESS 节点从 `outputs_json` 重建
  `AnalysisContext` 不重跑，只补跑 PENDING/FAILED 节点；run 已 SUCCESS 则直接返回缓存结果（幂等）；
- 执行器全是只读 SQL 聚合，天然幂等，重跑无副作用。

数据源配套：SQLite 开 WAL（一种写入日志模式，提升并发读写稳定性）+ busy_timeout，
Hikari 连接池 8（与执行线程池 4 匹配，留余量）。
