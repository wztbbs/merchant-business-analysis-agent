-- 闪购商家经营分析助手：十一张表（全部幂等，可重复执行）
-- 种子数据由 SeedDataGenerator（CommandLineRunner）在表为空时生成，便于控制数据分布。

-- 1. 订单明细事实表
CREATE TABLE IF NOT EXISTS order_item (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    order_id    TEXT    NOT NULL,
    sku_id      TEXT    NOT NULL,
    ts          TEXT    NOT NULL,   -- 下单时间 'yyyy-MM-dd HH:mm:ss'
    qty         INTEGER NOT NULL,
    amount      REAL    NOT NULL,   -- 行金额 = qty * price
    price       REAL    NOT NULL,
    activity_id TEXT,               -- 参与活动的订单标记，可空
    channel     TEXT,
    user_id     TEXT
);

-- 2. 商品维表
CREATE TABLE IF NOT EXISTS sku (
    sku_id   TEXT PRIMARY KEY,
    name     TEXT NOT NULL,
    category TEXT NOT NULL
);

-- 3. 上下架/缺货事件流水
CREATE TABLE IF NOT EXISTS sku_status_log (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    sku_id     TEXT NOT NULL,
    event_type TEXT NOT NULL,       -- listing / delisting / stockout_start / stockout_end
    event_time TEXT NOT NULL
);

-- 4. 活动表
CREATE TABLE IF NOT EXISTS activity (
    activity_id TEXT PRIMARY KEY,
    type        TEXT NOT NULL,      -- 满减 / 折扣 等
    start_time  TEXT NOT NULL,
    end_time    TEXT NOT NULL,
    rule        TEXT                -- 规则描述，如 '满50减10'
);

-- 5. 流量日指标表
CREATE TABLE IF NOT EXISTS shop_traffic_daily (
    stat_date TEXT PRIMARY KEY,     -- 'yyyy-MM-dd'
    exposure  INTEGER NOT NULL,
    visit_uv  INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_order_item_ts  ON order_item(ts);
CREATE INDEX IF NOT EXISTS idx_order_item_sku ON order_item(sku_id, ts);
CREATE INDEX IF NOT EXISTS idx_status_log_sku ON sku_status_log(sku_id, event_time);

-- 6. 分析运行记录：一次 /api/analyze 请求一行
CREATE TABLE IF NOT EXISTS analysis_run (
    run_id            TEXT PRIMARY KEY,
    root_run_id       TEXT,               -- run chain：NULL 表示链根（首次提问）；追问重跑的新 run 指向链根
    query             TEXT NOT NULL,
    scene             TEXT,
    plan_json         TEXT,             -- 完整 plan 结构（JSON），断点续跑时恢复用
    understanding_json TEXT,            -- 意图识别结果（JSON），断点续跑时恢复用
    status            TEXT NOT NULL,    -- RUNNING / SUCCESS / FAILED
    hypothesis_broken INTEGER NOT NULL DEFAULT 0,
    conclusion        TEXT,
    error             TEXT,
    created_at        TEXT NOT NULL,    -- 'yyyy-MM-dd HH:mm:ss'
    finished_at       TEXT
);

-- 7. 步骤执行明细：plan 实例化后先全量写入 PENDING，执行中推进状态机
CREATE TABLE IF NOT EXISTS step_run (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id       TEXT NOT NULL,
    step_id      TEXT NOT NULL,
    step_name    TEXT,
    factor       TEXT,
    status       TEXT NOT NULL,         -- PENDING / RUNNING / SUCCESS / FAILED / SKIPPED
    outputs_json TEXT,                  -- 该步产出（JSON），断点续跑时用于重建 AnalysisContext
    note         TEXT,                  -- 失败原因或跳过原因
    started_at   TEXT,
    finished_at  TEXT,
    UNIQUE(run_id, step_id)
);

CREATE INDEX IF NOT EXISTS idx_step_run_run ON step_run(run_id);

-- 8. 未支持需求采集：out_of_scope 的 query 落库，按 (suggest_action, suggest_object) 聚合统计，
--    用于驱动新场景的支持优先级（结构化切面聚合，避免自由命名发散无法统计）
CREATE TABLE IF NOT EXISTS bad_query_case (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    query          TEXT NOT NULL,       -- 用户问题原文
    business_intent TEXT,               -- LLM 概括的一句话意图（自由文本，仅作参考）
    suggest_action TEXT,                -- 分析动作切面（枚举）：diagnosis / query / evaluation / comparison / forecast
    suggest_object TEXT,                -- 分析对象切面（半开放）：sales_amount / inventory / new_customer ...
    suggest_entity TEXT,                -- 涉及实体名，可空
    created_at     TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_bad_query_case_facets ON bad_query_case(suggest_action, suggest_object);

-- 9. 评测标注集（golden set）：split 区分 dev（调 prompt 用）/ test（封存回归用）
CREATE TABLE IF NOT EXISTS eval_case (
    id                      INTEGER PRIMARY KEY AUTOINCREMENT,
    query                   TEXT NOT NULL,
    expected_scene          TEXT NOT NULL,   -- 含 out_of_scope（对抗样本也进标注集）
    expected_metric         TEXT,
    expected_time_range     TEXT,
    expected_entity_sku     TEXT,
    expected_candidate_factor TEXT,
    split                   TEXT NOT NULL DEFAULT 'dev',
    source                  TEXT,            -- manual / prod_log / bad_query_case
    created_at              TEXT NOT NULL
);

-- 10. 评测批次：一场考试一行，model + prompt_version 是横向对比的关键维度
CREATE TABLE IF NOT EXISTS eval_run (
    eval_run_id    TEXT PRIMARY KEY,
    eval_type      TEXT NOT NULL,     -- intent / grounding
    model          TEXT,
    prompt_version TEXT,              -- prompt 文件内容哈希前 8 位（非 git commit，覆盖未提交的调优中间态）
    status         TEXT NOT NULL,     -- RUNNING / SUCCESS / FAILED
    total_cases    INTEGER,
    passed_cases   INTEGER,
    metrics_json   TEXT,              -- 汇总指标，如 {"scene_acc": 0.95}
    created_at     TEXT NOT NULL,
    finished_at    TEXT
);

-- 11. 单 case 评测结果：逐字段通过位打平成列，统计时直接 AVG，不用挖 JSON
CREATE TABLE IF NOT EXISTS eval_case_result (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    eval_run_id     TEXT NOT NULL,
    case_id         INTEGER,          -- intent 评测关联 eval_case；grounding 评测为空
    analysis_run_id TEXT,             -- grounding 评测回链生产 trace（analysis_run）
    actual_json     TEXT,             -- 实际意图 JSON 或结论文本
    pass_scene      INTEGER,          -- 1/0；该字段未参与比较时为 NULL
    pass_metric     INTEGER,
    pass_time_range INTEGER,
    pass_entity     INTEGER,
    pass_factor     INTEGER,
    grounding_fail_count INTEGER,     -- grounding 机械校验失败条数（仅 grounding 评测）
    passed          INTEGER,          -- 本 case 整体是否通过：intent=所有参与字段全过；grounding=fail_count=0
    detail_json     TEXT,             -- 逐字段 expected vs actual / 各校验项明细
    created_at      TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_eval_case_result_run ON eval_case_result(eval_run_id);

-- eval_case 的 query 唯一约束：label upsert 接口按 query 判存（存在更新、否则插入）
CREATE UNIQUE INDEX IF NOT EXISTS uk_eval_case_query ON eval_case(query);

-- 12. 会话：一次商家经营分析会话。只存会话归属与入口信息；
--     追问上下文不在这里快照，由 run chain（analysis_run.root_run_id）+ 链尾 run 的 understanding 承担
CREATE TABLE IF NOT EXISTS conversation_session (
    session_id            TEXT PRIMARY KEY,
    merchant_id           TEXT NOT NULL,
    user_id               TEXT,
    title                 TEXT,
    status                TEXT NOT NULL DEFAULT 'active',  -- active / archived
    last_run_id           TEXT,
    topic_summary         TEXT,           -- 会话主题摘要（首问原文）
    last_active_at        TEXT NOT NULL,
    created_at            TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_session_user_active ON conversation_session(merchant_id, user_id, last_active_at);

-- 13. 会话轮次：一轮用户输入 + 系统回答。
--     run_id = 本轮产出的 run（真跑了 plan 才有值）；ref_run_id = 本轮引用/承接的 run（追问时记录来源）；
--     answer 仅用于无 run 的轮次（读历史型追问的回答、out_of_scope 兜底话术）
CREATE TABLE IF NOT EXISTS conversation_turn (
    turn_id        TEXT PRIMARY KEY,
    session_id     TEXT NOT NULL,
    merchant_id    TEXT NOT NULL,
    user_id        TEXT,
    user_query     TEXT NOT NULL,
    resolved_query TEXT,                -- 追问补全后的问题（非追问时为空）
    is_follow_up   INTEGER NOT NULL DEFAULT 0,
    follow_up_type TEXT,                -- metric_switch / time_compare / factor_detail / advice / explain
    run_id         TEXT,                -- 本轮新跑的分析 run；读历史型追问为空
    ref_run_id     TEXT,                -- 追问引用/承接的 run（回答依据的来源 run）
    answer         TEXT,                -- 仅 run_id 为空的轮次使用
    created_at     TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_turn_session ON conversation_turn(session_id, created_at);
