#!/usr/bin/env python3
"""合并 label1/label2 → PUT /api/eval/case 契约格式（labels_merged.jsonl）。

规则：
- label2 为主；其中 4 条「7月」case 改标 out_of_scope（与自身「上周/最近30天」口径一致，
  且 7月=last_month 的标注下月就过期）；
- label1 只挑实体落在种子库的 case（无糖可乐/巧克力/sku15），query 文本与 label2 重复的跳过；
- 字段映射到接口契约：scene/primary_scene→expectedScene, entity(_sku)→expectedEntitySku,
  env→split, source 标记来源文件。
"""
import json

DB_ENTITIES = {"无糖可乐", "巧克力", "sku15"}

# label2 中需要改标 out_of_scope 的「7月」query → suggest 切面
JULY_FIX = {
    "为什么7月份订单量环比下滑了？": ("diagnosis", "order_count"),
    "7月订单量为什么比6月少？": ("diagnosis", "order_count"),
    "查一下7月份的订单量。": ("query", "order_count"),
    "给我一份7月销售额的报表。": ("query", "sales_amount"),
}

def to_api(query, scene, metric, time_range, entity, factor, env, source):
    return {
        "query": query,
        "expectedScene": scene,
        "expectedMetric": metric,
        "expectedTimeRange": time_range,
        "expectedEntitySku": entity,
        "expectedCandidateFactor": factor,
        "split": env,
        "source": source,
    }

merged, seen = [], set()

# --- label2 全量（修 7月） ---
for line in open("label2.txt", encoding="utf-8"):
    line = line.strip()
    if not line:
        continue
    d = json.loads(line)
    q = d["query"]
    if q in JULY_FIX:
        action, obj = JULY_FIX[q]
        d.update({"metric": None, "time_range": None, "comparison_baseline": None,
                  "entity": None, "candidate_factor": None,
                  "suggest_action": action, "suggest_object": obj,
                  "scene": "out_of_scope", "factor_modules": []})
    merged.append(to_api(q, d["scene"], d["metric"], d["time_range"],
                         d["entity"], d["candidate_factor"], d["env"], "label2"))
    seen.add(q)

# --- label1 只挑种子库实体 case ---
skipped_dup, picked = 0, 0
for line in open("label1.txt", encoding="utf-8"):
    line = line.strip()
    if not line:
        continue
    d = json.loads(line)
    if d["entity_sku"] not in DB_ENTITIES:
        continue
    if d["query"] in seen:
        skipped_dup += 1
        continue
    seen.add(d["query"])
    picked += 1
    merged.append(to_api(d["query"], d["primary_scene"], d["metric"], d["time_range"],
                         d["entity_sku"], d["candidate_factor"], d["env"], "label1"))

with open("labels_merged.jsonl", "w", encoding="utf-8") as f:
    for m in merged:
        f.write(json.dumps(m, ensure_ascii=False) + "\n")

from collections import Counter
print(f"total={len(merged)} (label2=100, label1 picked={picked}, dup skipped={skipped_dup})")
print("by split:", dict(Counter(m["split"] for m in merged)))
print("by scene:", dict(Counter(m["expectedScene"] for m in merged)))
