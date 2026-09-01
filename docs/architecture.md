# 系统架构

## 整体链路

```
POST /api/analyze {"query": "..."}
        │
        ▼
QueryUnderstandingService ── LLM（query_understanding prompt）
        │  输出 JSON：business_intent / metric / time_range / entity / candidate_factor / scene
        ▼
PlanWeaver（纯确定性代码，不调 LLM）
        │  按 scene 选骨架 → candidate_factor 必选 + default_factors 展开
        │  → 统一分配运行时步骤 id（S1、S2…）、替换 {{entity.sku}} 参数、拼接 depends_on/inputs
        ▼
PlanValidator（确定性校验，失败抛 PlanValidationException）
        │  结构校验：id 唯一 / depends_on 存在且无环 / inputs 在上游 outputs 中
        │  供给校验：capability 在能力目录白名单 / output 在其 produces 中
        ▼
ExecutionService（Kahn 拓扑分层，同层步骤提交线程池并发执行，层间屏障等待；不引编排框架）
        │  能力目录 → CapabilityExecutor（JdbcTemplate SQL 聚合）→ 结果写入 AnalysisContext
        │  动态调整：用户假设因子命中 playbook 声明的证伪规则 → hypothesis_broken，跳过该因子后续步骤
        ▼
ConclusionService ── LLM（conclusion prompt：playbook md + step_results JSON）
        ▼
{ understanding, plan, step_results, hypothesis_broken, conclusion }
```

全链路固定 2 次 LLM 调用：开头的意图识别、结尾的结论生成；中间全是确定性代码和毫秒级 SQL。

## 各模块职责

```text
src/main/java/com/merchant/analysis/
├── Application.java                 主类
├── llm/                             LlmClient 接口 / OpenAiCompatibleClient / MockLlmClient / LlmJson
├── config/LlmConfig.java            llm.* 配置（base-url / api-key / model）
├── understanding/                   意图识别（LLM → QueryUnderstanding JSON）
├── playbook/PlaybookLoader.java     启动加载骨架+因子 YAML，对照能力目录静态校验，失败即启动失败
├── plan/                            Plan / PlanStep / PlanWeaver（确定性编织）/ PlanValidator
│                                    / PlanWeavingContext / FactorSelector / ReferenceResolver / TemplateContext / PlanPrinter
├── execution/                       CapabilityCatalog / CapabilityExecutor / ExecutionService / AnalysisContext
│   └── executors/                   10 个能力执行器（JdbcTemplate SQL）
├── time/TimeResolver.java           last_month / previous_month 等逻辑时间 → 具体日期区间
├── conclusion/ConclusionService.java 结论生成（LLM）+ 读历史型追问回答（followup_answer prompt）
├── conversation/                    会话与追问：ConversationService（会话编排：取候选链、按判定落定会话、
│                                    写轮次）/ ConversationRepository（conversation_session / conversation_turn）
│                                    追问判定合并在意图识别的 LLM 调用里（链尾 understanding 注入 prompt）；
│                                    run chain 由 analysis_run.root_run_id 表达，turn.ref_run_id 记录追问引用来源
├── api/AnalyzeController.java       POST /api/analyze（支持 merchantId/userId/sessionId 追问）
├── api/ConversationController.java  GET /api/conversations（会话列表）/ {sessionId}/turns（历史轮次）
├── persistence/                     RunRepository（analysis_run/step_run 落库）
│                                    / BadQueryCaseRepository（out_of_scope 需求采集）
├── eval/                            EvalService / GroundingChecker / EvalController / EvalRepository
│                                    / EvalCaseSeeder（标注集种子，表空时插入）
└── seed/SeedDataGenerator.java      CommandLineRunner，表为空时造数（相对日期，任何时候启动都有"上月"数据）

src/main/resources/
├── db/tables.sql                    十一张表 DDL（幂等）：order_item / sku / sku_status_log / activity / shop_traffic_daily
│                                    + analysis_run / step_run（运行状态与步骤产出持久化）
│                                    + bad_query_case（out_of_scope 需求采集：query + 结构化切面）
│                                    + eval_case / eval_run / eval_case_result（评测标注集 / 批次 / 单 case 结果）
├── capabilities.yaml                能力目录（name / params / produces / executor bean 名）
├── playbooks/skeletons/*.yaml       3 个场景骨架：metric_change_diagnosis / factor_impact_evaluation / metric_query_report
├── playbooks/factors/*.yaml         5 个因子模块：delisting / traffic / campaign / price / stockout
├── playbooks/factors/*.md           各因子的中文分析思路/结论话术（结论生成阶段拼 prompt 用）
└── prompts/*.txt                    query_understanding.txt / conclusion.txt（含 TEMPLATE 标记供 mock 识别）
```

## 关键设计点

- **plan 不靠 LLM 自由生成**：由「场景骨架 + 因子模块插槽」模板确定性实例化，再经确定性校验后才
  执行；LLM 只负责两端的意图识别与结论生成。
- **启动期静态校验**：PlaybookLoader 加载全部骨架/因子 YAML 并对照能力目录校验（插槽引用存在、
  能力在白名单内、产出字段有出处、因子语义输入能在骨架固定步中找到生产者），写错直接启动失败。
- **名称由来**：之所以叫"因子"（factor）而非"因素"——因子在量化分析语境里隐含可度量、可加性
  拆解（贡献率），与 `sales_delta` / `contribution_ratio` 的产出对应。
- **插槽与槽位填充的区分**：NLP 的"槽位填充"是意图识别抽参数（metric/time_range/entity），
  发生在上游；playbook 里的"插槽"（factor_slot）是 plan 骨架上的因子插拔点，两者不要混淆。
