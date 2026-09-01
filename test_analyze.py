#!/usr/bin/env python3
"""批量测试 /api/analyze：从 labels_merged.jsonl 中按环境（split）取前 N 条 query，
逐条调用 POST /api/analyze，请求和响应写入 logs/test_analyze.log。

用法：
    python3 test_analyze.py               # 默认 dev 环境，全部 dev 行
    python3 test_analyze.py dev 10        # dev 环境，只跑前 10 行
    python3 test_analyze.py test 5        # test 环境，只跑前 5 行
    BASE_URL=http://host:8082 python3 test_analyze.py dev 10
"""
import json
import logging
import os
import sys
import urllib.request
import urllib.error

BASE_URL = os.environ.get("BASE_URL", "http://localhost:8082")
PATH = "/api/analyze"
LOG_FILE = os.path.join("logs", "test_analyze.log")

logging.basicConfig(
    filename=LOG_FILE,
    level=logging.INFO,
    format="%(asctime)s %(message)s",
    encoding="utf-8",
)
log = logging.getLogger("test_analyze")


def analyze(query: str) -> dict:
    body = json.dumps({"query": query}, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(BASE_URL + PATH, data=body,
                                 headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=300) as resp:
        return json.loads(resp.read().decode("utf-8"))


def main() -> int:
    env = sys.argv[1] if len(sys.argv) > 1 else "dev"
    limit = int(sys.argv[2]) if len(sys.argv) > 2 else None

    cases = []
    with open("labels_merged.jsonl", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            case = json.loads(line)
            if case.get("split") == env:
                cases.append(case)
                if limit is not None and len(cases) >= limit:
                    break

    log.info("=" * 80)
    log.info("开始测试 env=%s limit=%s 共 %d 条", env, limit, len(cases))

    ok = failed = 0
    for i, case in enumerate(cases, 1):
        query = case.get("query")
        try:
            r = analyze(query)
            ok += 1
            scene = ((r.get("understanding") or {}).get("scene") or {}).get("primary_scene")
            msg = f"[{i}/{len(cases)}] OK {query} | scene={scene} (期望 {case.get('expectedScene')}) run_id={r.get('run_id')}"
            print(msg)
            log.info(msg)
            log.info("响应: %s", json.dumps(r, ensure_ascii=False))
        except (urllib.error.URLError, TimeoutError) as e:
            failed += 1
            msg = f"[{i}/{len(cases)}] FAILED {query} 请求异常: {e}"
            print(msg)
            log.error(msg)

    summary = f"完成：ok={ok} failed={failed} 日志见 {LOG_FILE}"
    print(summary)
    log.info(summary)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
