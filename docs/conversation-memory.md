# 会话、追问与历史事实

## 三种标识

| 标识 | 含义 |
|---|---|
| session_id | 前端可选择的一次会话容器 |
| run_id | 一次真正执行 Playbook/ReAct 的分析 |
| root_run_id | 同一分析话题链的根；重跑型追问挂到原链 |

一轮不一定有 run。读历史、澄清和领域外回答写 conversation_turn，不新建 analysis_run；执行型问题则把回答保存在 analysis_run，turn 记录关联。原始 query 和 resolved_query 同时保留，避免丢失改写前语义。

## 候选上下文与会话选择

请求可带 merchantId、userId、sessionId；前两个缺省 default。显式 sessionId 时读取该会话候选链，可以选择超过隐式窗口的旧会话。未指定时查用户近 7 天活跃会话和近期链尾，最多取两条候选链给理解模型，不把全部会话原文塞进去。

理解后若为有效追问，落到被引用 run 的所属会话；否则新建会话。显式选择 sessionId 是候选上下文选择，不保证任何新问题都强制留在该会话。

候选按 root_run_id 取链尾，时间排序依赖 created_at；同秒并列目前没有完整的业务因果序列证明。商家/用户标识服务于会话组织，当前 API 和 SQL 没有完整权限控制；显式 sessionId、runId 也不应被当成生产安全边界。

## 哪些结果能作为历史事实

成功运行可读。PARTIAL、CAPABILITY_MISSING、BUDGET_EXCEEDED、LOOP_DETECTED 只有存在至少一个 SUCCESS step 时才进入可承接候选；FAILED/RUNNING 不进入。Hn 导入的历史快照也是成功步骤，因此一个没有新查询、但已有可读快照的部分运行也可能成为候选。

HistoryFacts 向理解阶段提供每条链最多 40 个字段预览；集合最多 3 行、对象最多 12 字段、深度 3、字符串 200 字符，并标明截断。服务端校验和后续复用针对完整成功步骤结果，不受这个摘要大小限制。不要把该预览策略与 ReAct 的 10 行结果预览混为一谈。

## 一次 LLM 调用中的追问判定

意图识别同时返回 followUp、followUpType、refRunId、needsRerun，完成 Query 改写与历史指代绑定。允许的追问包括 metric_switch、time_compare、factor_detail、advice、explain、result_reference。

- 解释/建议且不需要新事实：HISTORY，直接用来源成功步骤和旧结论回答。
- 换指标/时间/实体/因子，或需要新查询：新建 run，再按 coverage 选 Playbook/ReAct，沿原 root_run_id 挂链。
- 选错来源、字段缺失、集合指代无法落到具体事实：CLARIFY，不盲目用全店数据替代。

来源不是 SUCCESS 时，历史回答 prompt 会明确说明“上次未全部完成，只能引用已取得事实”。响应保留 source_status；读历史状态为 PARTIAL，不把部分来源洗成全量成功。来源丢失/不可读返回 HISTORY_UNAVAILABLE。

## 历史集合引用和 ReAct 快照

```json
{"run_id":"实际历史run_id","path":"S2.top_contributors"}
```

模型提出 task.fact_references，服务端检查它属于本轮候选且完整路径存在。群体指代需要这样的可靠来源或明确实体列表，不能让模型凭记忆重新生成商品集合。

ReAct 首次执行把被引用 step 的完整根结果复制为本次 Hn 步骤，并记录源 run/path 到 Hn.path 的别名绑定。即使只选择了某个字段，根快照也可能包含同一步其他字段；这方便稳定引用，但不是字段级最小授权实现。

后续工具/计算可以引用 Hn 路径，保留来源日期、工具和参数；派生计算的 lineage 继续追溯原业务来源。恢复时沿用本次快照，不用重新取一次榜单来改变原集合。来源快照缺失时明确失败，不伪造新集合。

这解决“上次这些商品”的事实承接，不代表完整的长期记忆检索、任意多轮语义推理或所有口径兼容性都已实现。

## 时间继承和澄清边界

全局时间意图未变的指标切换型追问可沿用原 resolved_time，避免跨月后“上月”变义；修改时间则按本轮上下文解析。旧 run 没有冻结时间时无法补回原参考日，详见[时间](time.md)。

澄清问答会写入独立轮次，但当前没有“待澄清任务”持久化状态机。不能保证回答一个短语“最近7天”就自动恢复上一次未执行的完整任务；必要时用户需要把条件补充成完整问题。

## 查询接口

- `GET /api/conversations?merchantId=demo&userId=u1&limit=50`：列表按最近活跃返回，含 session_id/title/topic_summary/status/last_active_at/created_at。
- `GET /api/conversations/{sessionId}/turns`：返回轮次；run 型回答从 analysis_run.conclusion 读取，独立轮次从 turn.answer 读取。
- `POST /api/analyze`：传 sessionId 表示选会话继续理解；传 runId 表示恢复原运行，两者用途不同。

当前没有跨服务缓存、向量记忆、话题摘要自动压缩和生产鉴权。本地已存结构化事实是主要可追溯依据。
