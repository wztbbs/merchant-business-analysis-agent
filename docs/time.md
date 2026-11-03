# 时间类型、数据截止与对比基线

## 业务前提和对象层次

当前只有日级经营分析，没有实时、小时或分钟查询；最新可分析日期默认是昨日。日期解析由代码确定，不让模型直接生成 SQL 时间戳。

| 对象 | 职责 | 关键字段 |
|---|---|---|
| `TimeSpec` | 用户想查哪个时间范围 | 三种类型之一 |
| `TimeContext` | 本次解析的业务日历上下文 | reference_date、data_through、timezone |
| `DateRange` | 确定的日期区间 | start、endExclusive |
| `ResolvedTime` | 一次任务冻结的完整时间 | context、current、baseline |

默认时区 `Asia/Shanghai`，可通过 `BUSINESS_TIME_ZONE` 配置。`reference_date` 在意图识别调用之前，按业务时区捕获一次，避免跨午夜时不同步骤各算一次“今天”。`data_through` 默认 `reference_date - 1 day`，可通过 `BUSINESS_DATA_THROUGH=yyyy-MM-dd` 覆盖，必须早于参考日。

配置的截止日表示业务约定，系统不验证数仓实际是否齐备、某个历史日期是否存在数据，也没有按工具/数据源分别水位的机制。截止日前没有记录与“数据缺失”的区别仍需数据治理。

## 三种 TimeSpec

### calendar_period：自然周期

```json
{"type":"calendar_period","unit":"month","offset":-1}
```

| 字段 | 规则 |
|---|---|
| unit | day、week、month、quarter、year |
| offset | 非正整数；0 为当前自然周期，-1 为前一个自然周期，以此类推 |

周期锚定 `reference_date`，不是锚定 data_through。周从周一开始，月从 1 日开始，季度为自然季度，年为自然年。

- `offset=0`：先取当前完整自然周期，再把末端截至 `data_through+1 day`；若截完为空，则没有可用日数据。
- `offset<0`：要求整个历史自然周期都在数据截止范围内，超出则报错，不能静默把“上月”改成半个月。
- `day/offset=0` 表示今天，在默认昨日截止前提下没有可用数据；昨天用 day/-1。

### recent_days：最近 N 个完整日

```json
{"type":"recent_days","days":7}
```

`days` 必须为正整数。区间为 `[data_through-N+1, data_through+1)`，包含截止日，排除今天。它不是从当前时刻往回滚动 N×24 小时；当数据延迟时，它会随实际配置的截止日向前移动。

### date_range：绝对日期区间

```json
{"type":"date_range","start":"2026-10-01","end":"2026-10-15"}
```

输入 `end` **包含当天**，允许 start=end 的单日查询。必须 end>=start，且不能超过 data_through。日期严格为四位年 `yyyy-MM-dd`，支持年份 0001～9999 内的合法日期，不接受时间戳、非法闰日或其他日期格式。极端日期在加一天或减基线时越过可表示范围仍会失败，不做无限日历支持。

### 严格输入和默认值

三类字段不能混用，未知字段拒绝。整数不接受字符串、浮点数或溢出的数值；正 offset、非正 days 均非法。新理解协议使用对象；旧记录的字符串只兼容：

| 旧字符串 | 转为 |
|---|---|
| last_month | calendar_period/month/-1 |
| previous_month | calendar_period/month/-2；保留旧“前月”含义 |
| this_month | calendar_period/month/0 |

全局 `time_range=null` 时默认上月；`comparison_baseline=null` 时按下述规则推导。子需求可携带自己的 TimeSpec；这不代表当前固定模板能覆盖不同时间的混合需求。

## 内部半开区间与 SQL

```json
{"start":"2026-10-01","endExclusive":"2026-10-16"}
```

上述对象等价于外部 `2026-10-01`～`2026-10-15`。内部 DateRange 必须 start<endExclusive，`dayCount` 是自然日数。SQL 使用：

```sql
WHERE ts >= '2026-10-01 00:00:00'
  AND ts <  '2026-10-16 00:00:00'
```

`endExclusive` 是内部字段名，不能和 TimeSpec 的包含式 `end` 混淆。工具既可接受 TimeSpec，也可接受已解析的 DateRange，由 ToolRuntime 规范化后再传给执行器。底层演示库用业务本地日期/时间字符串比较，没有通用时区转换、夏令时采样或多市场交易日模型。

## 默认比较期

| 当前时间意图 | 没有显式 baseline 时 |
|---|---|
| 历史完整自然周期 | 前一个完整自然周期；允许月、季度、年天数不同 |
| 当前自然周期 | 前一个自然周期，从起点取相同已覆盖天数，最多截到前一周期末端 |
| 最近 N 天 | 紧邻当前窗口之前的 N 天 |
| 绝对日期区间 | 紧邻当前窗口之前、相同天数的区间 |

`comparison_baseline` 可显式指定上述三种类型之一，此时独立解析，不自动强制同天数。

当前自然周期按**日数序号**对齐，而不是强制按同月同日或星期几对齐。闰年、不同季度长度下尤其需要注意。例如当前年已过的相同天数，在上年可能落到另一个月日；当前月覆盖 30 日但前月仅 28 日时，比较期最多只有前月的 28 日。这是当前实现的明确规则，不是统一“同比/同周几/节假日可比”策略。

### 固定参考日示例

以下均以 `reference_date=2026-11-03`、`data_through=2026-11-02`、`timezone=Asia/Shanghai` 为例，区间为半开表示：

| 意图 | current | 默认 baseline |
|---|---|---|
| 昨天 day/-1 | [2026-11-02, 2026-11-03) | [2026-11-01, 2026-11-02) |
| 最近 7 天 | [2026-10-27, 2026-11-03) | [2026-10-20, 2026-10-27) |
| 上月 month/-1 | [2026-10-01, 2026-11-01) | [2026-09-01, 2026-10-01) |
| 本月 month/0 | [2026-11-01, 2026-11-03) | [2026-10-01, 2026-10-03) |
| 本周 week/0 | [2026-11-02, 2026-11-03) | [2026-10-26, 2026-10-27) |
| 10 月 1～15 日 | [2026-10-01, 2026-10-16) | [2026-09-16, 2026-10-01) |

### 边界情况

- 月初 1 日查本月：昨日仍在上月，本月为空，返回时间澄清，不把今天算作已有数据。
- 数据延迟到 10 月 30 日，但参考日为 11 月 3 日：上月不是完整可用月，拒绝静默截短；本月也没有可用数据；recent_days 则以 10 月 30 日结束。
- 3 月 31 日、截止 3 月 30 日查本月：当前覆盖 30 日，默认基线为前月，最多覆盖其实际 28/29 日。
- 绝对日期超过截止：澄清；不伪装成实时查询，也不判为领域外。
- 时间意图根本无法合法解析：可能在协议校验时抛异常，不保证每种模型坏输出都能转换成澄清。

## 冻结、工具传参与追问

解析结果保存在 `QueryUnderstanding.resolved_time`，随 `analysis_run.understanding_json` 持久化：

```json
{
  "context":{"reference_date":"2026-11-03","data_through":"2026-11-02","timezone":"Asia/Shanghai"},
  "current":{"start":"2026-10-01","endExclusive":"2026-11-01"},
  "baseline":{"start":"2026-09-01","endExclusive":"2026-10-01"}
}
```

- 新任务运行和恢复沿用冻结上下文，不能在恢复时把“上月”改成恢复日的上月。
- 有效历史追问只要全局 time_range/comparison_baseline 意图均未变且非 time_compare，就继承来源的冻结时间；指标切换是典型情况。改时间会重新解析。
- 旧运行记录若只有 last_month 等字符串，没有原始参考日，则无法恢复当时到底是哪一个月；兼容读取不等于可补回丢失信息。
- ToolRuntime 缺省当前窗口用上下文 current；缺省 baseline 且当前窗口与上下文相同时用上下文 baseline；传入不同当前时间且未传 baseline 时，按该请求的时间意图推导比较期。显式 baseline 优先。
- 执行器只读取规范化的显式 `time_range/baseline`，不再从全局历史结果隐式选时间。

业务口径也随之统一：`sku_daily_avg_sales=基线销售额/基线实际自然日数`；`sku_status_query` 的状态为观察期末状态，不是实时状态，其最近下架记录在两期覆盖窗口中查找。

## 当前不包括

实时/小时/分钟、工作日或交易日历、节假日可比、自动 YoY 策略、多数据源独立水位、自动补齐历史数据、完整 CEXPilot 时间与量纲体系。需要上年对比时应明确给出 comparison_baseline，不假设系统已有所有同比策略。

实现入口：[TimeSpec](../src/main/java/com/merchant/analysis/time/TimeSpec.java)、[TimeResolver](../src/main/java/com/merchant/analysis/time/TimeResolver.java)、[TimeContext](../src/main/java/com/merchant/analysis/time/TimeContext.java)、[TimeResolverTest](../src/test/java/com/merchant/analysis/time/TimeResolverTest.java)。
