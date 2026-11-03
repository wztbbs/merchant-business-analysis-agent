# 已落地的 ReAct 文字场景

本文件原为候选方案，现已按实际实现更新。以下 4 个场景已接入识别与 ReAct；不是尚未落地的设计清单。

## 与 Playbook 的区别

Playbook 是骨架、因子、依赖、参数和覆盖契约组成的固定流程，运行前可生成并校验完整 DAG。文字场景只有说明、示例、建议工具和 guidance：告诉模型通常先做 A、再做 B、何时停止，并提醒口径边界；没有因子插槽、固定节点和专属执行器。

路由先判断是否有可覆盖的 Playbook，未覆盖才走 ReAct。识别阶段只注入文字场景摘要（id/name/description/examples），执行阶段再注入命中场景的 guidance。recommended_tools 是作者建议，不是必须调用的程序清单，也不会使模型自动加载全部定义。没有命中场景仍可 ReAct。

## 当前四个场景

| id | 入口问题 | 典型路线 | 完成边界 |
|---|---|---|---|
| top_sku_drilldown | 主要变化商品逐项核查 | 销售变化绝对值 Top10 → 选中商品 → 按需查销售、状态、价格、缺货 | 默认优先最多 3 个；不能从 Top10 推断全店完整下降集合 |
| stockout_sales_crosscheck | 缺货商品中哪些销售下降 | 缺货流水 → 按 sku_id 去重 → 逐 SKU 两期销售 → 按 sales_delta 判断 | 全量结论要覆盖全部选中商品；只能说明同期现象，不能估缺货损失 |
| category_structure_review | 品类销售结构、差额与占比 | 两期品类销售 → 按需 sum/max/difference/ratio 等 | 只有品类销售额；无品类订单数、AOV 或完整 SKU 明细 |
| multi_sku_review | 指定多个商品的经营对照 | 每 SKU 两期销售 → 按需价格/状态 → 计算比较 | null 不当零，不无故扩大为全店诊断；同品类整体变化不证明指定商品间转移 |

资源在 [react/scenarios](../src/main/resources/react/scenarios)。场景抽取基于现有工具的可组合能力，不应为了丰富场景而写不存在的取数或因果能力。

## YAML 格式

一个场景一个 `.yaml` 文件，文件名等于 id，例如：

```yaml
id: stockout_sales_crosscheck
name: 缺货商品与销售下降交叉核查
description: 以缺货事件为入口，对发生过缺货的商品逐项检查两期销售变化，找出同期销售下降的商品。
examples:
  - 上个月发生过缺货的商品里，哪些销售也下降了？
recommended_tools: [stockout_query, sku_sales_history, sales_summary_query]
guidance: |
  先查询 stockout_query，按 sku_id 去重形成商品集合；没有记录时结束。
  使用同一观察期与基线期，逐项查询 sku_sales_history，按 sales_delta 判断下降。
  商品身份优先引用上一步结果；用户问全店变化贡献时再查销售汇总并引用 metric_delta。
  缺货与销售下降同时发生不等于缺货造成损失；不从原始事件自行估算时长或损失。
  预算不足时说明只核查了部分商品，不能输出全量结论。
```

此示例展示当前资源的结构和主要约束；以仓库中的完整 YAML 为运行依据。

## 加载和校验

`ReactSceneCatalog` 使用 `classpath*:react/scenarios/*.yaml` 自动发现，适用于打包 JAR。校验文件名与 id 一致、ID 唯一、必要描述不空、推荐工具已注册。它不验证 guidance 中每句话一定可执行，也不会生成依赖图。

与之不同，当前 PlaybookLoader 的骨架/因子仍使用固定文件名列表；不要把文字场景自动发现误套到 Playbook 新增方法。

## 编写与验收

1. 先确定当前 Playbook 的 coverage 确实覆盖不了，且现有工具能取得关键事实。
2. description 描述适用问题，examples 给代表 query，避免与另一个场景完全重叠。
3. guidance 写入口、顺序、结果分支、可选步骤、引用对象、停止条件和能力限制。
4. 不把 recommended_tools 写成“每次全调用”，不要忽略用户实际需求或运行预算。
5. 验证场景选择和工具链；需要真模型验收时观察是否少走弯路、是否误报全量完成、是否越界推因果。

新增文字配置比新增固定 DAG 轻，但仍应验证名称、适用范围和效果。“不用编排代码”不等于“不用测试”。只有新增工具、能力或独立证据规则时，才需要进一步代码改造。
