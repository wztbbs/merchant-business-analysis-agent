#!/usr/bin/env python3
"""逐行导入 label 到 eval_case：读取 labels_merged.jsonl，每行单独调用一次
PUT /api/eval/case（单元素数组）。插入和更新是同一个接口——服务端按 query 做 upsert，
已存在则更新，否则插入；每条独立提交，单条失败不影响后续。

用法：
    python3 import_labels.py                          # 默认 labels_merged.jsonl → localhost:8082
    python3 import_labels.py labels_merged.jsonl      # 指定文件
    BASE_URL=http://host:8082 python3 import_labels.py
"""
import json
import os
import sys
import urllib.request
import urllib.error

BASE_URL = os.environ.get("BASE_URL", "http://localhost:8082")
PATH = "/api/eval/case"


def upsert_one(case: dict) -> dict:
    body = json.dumps([case], ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(BASE_URL + PATH, data=body,
                                 headers={"Content-Type": "application/json"}, method="PUT")
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8"))


def main() -> int:
    path = sys.argv[1] if len(sys.argv) > 1 else "labels_merged.jsonl"
    inserted = updated = failed = 0
    with open(path, encoding="utf-8") as f:
        for lineno, line in enumerate(f, 1):
            line = line.strip()
            if not line:
                continue
            try:
                case = json.loads(line)
                r = upsert_one(case)
                if r.get("failed"):
                    failed += 1
                    print(f"[{lineno}] FAILED  {case.get('query')}  -> {r['failed']}")
                elif r.get("updated"):
                    updated += 1
                    print(f"[{lineno}] updated {case.get('query')}")
                else:
                    inserted += 1
                    print(f"[{lineno}] inserted {case.get('query')}")
            except (json.JSONDecodeError, urllib.error.URLError, TimeoutError) as e:
                failed += 1
                print(f"[{lineno}] FAILED  行解析/请求异常: {e}")
    print(f"\n完成：inserted={inserted} updated={updated} failed={failed}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
