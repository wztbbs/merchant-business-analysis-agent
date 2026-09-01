# 评测体系与实验记录

两次 LLM 调用各有评测手段，trace 直接复用 `analysis_run` / `step_run`。

## 评测接口

- `POST /api/eval/run {"evalType": "intent", "split": "dev"}` —— 触发评测（同步执行），
  响应直接返回完整结果（eval_run 汇总 + case_results 逐条明细），附 `eval_run_id` 供事后查询。
  `evalType=grounding` 时对最近的 SUCCESS 分析结论跑机械校验（不调 LLM）；
- `GET /api/eval/query/{eval_run_id}` —— 按 eval_run_id 查询单批次结果（含逐 case 明细）；
- `GET /api/eval/stats?evalType=intent` —— 跨批次聚合统计（按 eval_type + model + prompt_version
  分组）；
- `PUT /api/eval/case [{query, expectedScene, ...}]` —— 标注批量提交：按 query 逐条 upsert
  （存在则更新，否则插入），单条失败不影响整批，失败明细在响应 failed 列表中返回。

## 规则说明

- `prompt_version` 为 prompt 文件内容哈希前 8 位（启动时计算），覆盖未 commit 的调优中间态；
- grounding（结论校验）五项机械校验：数字出处（含百分比换算/比值派生/绝对值表述；行首编号、
  步骤编号 S1/S2 不算业务数字；用户 query 中提到的数字视为有出处）、主因点名（单品问题不强制
  点名店铺级主因）、假设证伪声明、实体出处（「」内只允许实体名，需在 step_results 或用户 query
  中出现）、符号一致性（按用户所问指标取对应 delta 比对，aov/order_count 不再套用店铺级
  metric_delta）；深层因果断言（数对但话说错）仍需 LLM-as-judge，未实现；
- mock 模式下 intent 评测实际考的是关键词规则覆盖率，真实准确率需接真实 LLM 后跑；
- intent 评测中单条 LLM 调用失败**不参与考核**（不进 case_result、不计入分母），失败数与明细
  记录在 `eval_run.metrics_json` 的 `llm_call_failed` / `llm_call_errors` 字段；执行过程逐条打
  进度日志（`[i/总数] 意图识别/LLM 响应`），服务端日志可直接跟踪；
- split 约定：dev=调 prompt 用，test=封存回归用。

## 意图识别评测记录

| 轮次 | 日期 | model | prompt_version | split | total | passed | case 通过率 | scene | metric | time_range | entity | factor |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| case1（基线） | 2026-08-27 | qwen-plus | 29d305a8 | dev | 87 | 70 | 80.5% | 93.1% | 94.4% | 87.3% | 100% | 100% |
| case2（补判定规则） | 2026-08-27 | qwen-plus | 716cef6a | dev | 87 | 85 | 97.7% | 98.9% | 98.6% | 98.6% | 100% | 100% |
| case2（test 回归，封盘） | 2026-08-27 | qwen-plus | 716cef6a | test | 41 | 41 | 100% | 100% | 100% | 100% | 100% | 100% |

case2 改动：prompt 增加 4 条判定规则（时间越界→out_of_scope / 诊断与评估边界 / evaluation 缺省值
last_month+sales_amount / 销量→order_count），修掉 case1 全部 4 类失败。

case2 剩余 2 条失败均为**标注自相矛盾**（已修标注，见下）：

1. **"7月"的口径不一致**："为什么7月客单价涨了"原标注为 diagnosis+`last_month`，但同样是绝对
   月份的"查一下7月份的订单量"/"给我一份7月销售额的报表"标注为 out_of_scope——已统一为
   **绝对月份一律 out_of_scope**（该条改标 out_of_scope）；
2. **"销量"的指标映射不一致**："冰淇淋销量腰斩"标注 `order_count`，"无糖可乐销量掉得厉害"原标注
   `sales_amount`——已统一为**销量→order_count**（该条改标 order_count）。

## 结论校验（grounding）评测记录

grounding 考的是结论生成 prompt（`conclusion.txt`），与意图识别是两条独立的评测线，case 编号
各自递增。评测对象：最近 50 条 SUCCESS analysis_run 的结论；mock 时代的 run 已清空，样本全部为
真实 LLM 产出。

**case1（基线）** — 2026-08-27 · qwen-plus · prompt_version `0a2797b3`

首轮评测，无改动。

| total | passed | 通过率 |
|---|---|---|
| 50 | 18 | 36.0% |

失败分布与定性（32 条失败，按检查项）：

| 检查项 | 失败数 | 定性 |
|---|---|---|
| entity_grounding | 25 | 真问题：LLM 在「建议」里编造数据中没有的商品名（如「零度可乐」「坚果巧克力礼盒」） |
| number_grounding | 13 | 混合：LLM 编造活动力度数字（"满50再减5"）+ checker 不识别千分位（`10,997` 拆成两个数，误报） |
| sign_consistency | 6 | 基本是 checker 误报：不认识否定形态（"未出现增长"含"增长"被误判为只表述上升） |
| coverage_main_cause | 1 | 边界：query 是单品维度，checker 却要求点名店铺级 top_contributor，规则偏严 |
| coverage_hypothesis | 0 | — |

**case1.5（修 checker 误报）** — 2026-08-27 · qwen-plus · 结论仍为 case1 旧 prompt 产出

相对 case1 的改动（只改 `GroundingChecker` 代码，不动 prompt、不重造数据，重评同一批旧结论）：

1. 数字抽取支持千分位（`10,997` → `10997`），不再拆成两个无出处数字；
2. sign_consistency 判断前剔除"未出现增长/没有下滑"类否定形态。

| total | passed | 通过率 |
|---|---|---|
| 50 | 20 | 40.0% |

效果：sign_consistency 6→4，number_grounding 13→12。剩余 sign_consistency 4 条为**结构性限制**——
结论在讲次指标（如 aov）的方向，checker 拿主指标 metric_delta 比对，机械规则无法区分方向词修饰
哪个指标，保留为已知限制。（eval_run 里记录的 prompt_version=0a14e639 是当前 conclusion.txt 的
哈希，不是结论的生成版本。）

**case2（prompt 约束建议部分）** — 2026-08-27 · qwen-plus · prompt_version `0a14e639`

相对 case1.5 的改动：conclusion prompt 新增第 6 条，重跑 analyze 生成新结论：

```
6. 「可执行建议」部分同样受数据约束：推荐/引用的商品名只能来自步骤数据或商家问题，不要编造新商品名；
   涉及活动建议时只做定性表述（如"上线一档满减活动"），不要编造具体活动力度数字（如"满50减5"）。
```

| total | passed | 通过率 |
|---|---|---|
| 50 | 28 | 56.0% |

效果：number_grounding 12→4（编活动力度基本绝迹）；entity_grounding 25→17 但失败性质变化——
从"编造商品名"变为「」滥用（泛化分类词/指标名/页面名/数字被「」包裹，违反 prompt 第 5 条），
伴随少量残留编造商品名。下一步：收紧第 5 条措辞，明确「」只用于数据中存在的具体商品/SKU 名。

**case3（收紧「」用法）** — 2026-08-27 · qwen-plus · prompt_version `52c77a56`

相对 case2 的改动：conclusion prompt 第 5 条收紧措辞，重跑 analyze 生成新结论：

```
5. 「」只用于引用步骤数据或商家问题中存在的具体商品/SKU 名（如「无糖可乐」）；指标名（如客单价）、
   泛化分类词（如"高单价商品"）、页面/功能名（如"购物车页"）、数字等一律不要用「」，直接平铺；
```

| total | passed | 通过率 |
|---|---|---|
| 50 | 25 | 50.0%（↓ 6 个点，回退） |

**翻车分析（prompt 示例反噬）**：entity_grounding 不降反升（17→20），且失败实体几乎全是
「无糖可乐」——第 5 条里"（如「无糖可乐」）"的正面示例被 LLM 锚定成标准答案，在建议里到处拿它
做搭配推荐（query 和数据里都只有巧克力，「无糖可乐」是从 prompt 示例里抄的）。case2 的旧措辞
没有具体示例，就没这个问题。number_grounding 4→7 为"跟踪2周/3天"类建议小数字，属波动。
**教训：prompt 里的具体示例会被模型当答案抄，规则描述尽量抽象。**

**case4（去掉示例 + 建议不点名数据外商品）** — 2026-08-27 · qwen-plus · prompt_version `0c5512f5`

相对 case3 的改动：第 5 条去掉"（如「无糖可乐」）"示例，并补一句"做搭配/替代建议时只给方向，
不点名本店数据之外的具体商品"。

| total | passed | 通过率 |
|---|---|---|
| 50 | 37 | 74.0% |

效果：entity_grounding 20→5（示例反噬消除），整体 50%→74%。剩余 13 条失败构成：
entity 5 条是规则 5 明令禁止但 LLM 仍偶发违规的「高单价商品」「购物车页」（残余不听话，递减
收益）；number 4 条为建议小数字（"跟踪3天"类）；sign_consistency 3 条 + coverage_main_cause
2 条为已知结构性限制（次指标方向 / 单品 query vs 店铺级主因）。机械校验路线到此收益基本榨干，
再往上需要动 checker（指标归属感知）或引入 LLM-as-judge。

**case5（结论按场景分流 + checker 误报修复）** — 2026-09-01 · qwen-plus · prompt_version `1e4e7022`

相对 case4 的改动（prompt/结论代码 2 处 + checker 3 处）：

1. **结论输出按场景分流**（ConclusionService 按 plan.scene() 与 hypothesis_broken 注入输出结构）：
   报表场景（metric_query_report）只输出「结论 + 数据支撑」，不输出建议段——报表场景没有排查
   数据，建议只能是套话，且是 entity/number 违规的重灾区；假设证伪时不输出经营建议，只给一句
   转向引导；诊断场景保留建议段，但要求每条建议挂钩具体原因的量化结果；
2. **prompt 规则 5/6 去掉具体示例词**（"高单价商品""购物车页""满50减5"等），只留抽象规则——
   case3 已证明示例会被模型当锚点抄，反面示例同样会；
3. **checker：sign_consistency 按用户所问指标取 delta**（aov→aov_delta / order_count→order_delta），
   修掉"销售额下滑但客单价上升"被判方向矛盾的 3 条误报；
4. **checker：coverage_main_cause 对单品 query 跳过店铺级主因点名**，修掉单品 query 被强制
   点名「无糖可乐」的误报；
5. **checker：数字抽取前剔除行首编号（"1. "）与步骤编号（S3），并允许数字来自用户 query 本身**
   （如"满50减10"，与实体检查的 query 豁免对齐），修掉编号误报与 query 数字误报。

| total | passed | 通过率 |
|---|---|---|
| 50 | 43 | 86.0% |

效果：74%→86%。checker 误报清零（case4 复盘中的 sign 3 条、单品主因 1 条、编号/S3 3 条全部
消除）；报表/证伪场景的建议段连根去掉后，prompt 锚定词（「高单价商品」等）彻底消失。剩余
7 条失败全部是真错误，集中在诊断场景建议段的自由发挥：6 条为编造细节（"停留42秒/行业均值
68秒"、"满30减5"力度数字、「猜你喜欢」类页面名、5277 约成 5200、整句话塞进「」），1 条为
多值求和派生（"其余9款合计31859.0"）超出 checker 两值比值规则的验证能力。机械校验红利到此
吃完，再往上需建议段后处理重写（扫到无出处「」/数字即拒掉重生成）或引入 LLM-as-judge。
