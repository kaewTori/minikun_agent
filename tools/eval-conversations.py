#!/usr/bin/env python3
"""Run the opt-in conversation suite against a test Minikun server; save answers for rubric review."""
import argparse
import json
import math
import os
from pathlib import Path
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


def summarize(results):
    times = sorted(row["elapsedMs"] for row in results if not row.get("error"))
    return {"total": len(results), "passed": sum(row["passed"] for row in results),
            "p50Ms": times[math.ceil(len(times) * .50) - 1] if times else None,
            "p95Ms": times[math.ceil(len(times) * .95) - 1] if times else None}


def regressions(previous, current):
    old = {row["id"]: row for row in previous}
    return [row["id"] for row in current if old.get(row["id"], {}).get("passed") and not row["passed"]]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", help="Test server URL, for example http://127.0.0.1:8080")
    parser.add_argument("--output", default="/tmp/minikun-conversation-eval.json")
    parser.add_argument("--baseline", type=Path)
    parser.add_argument("--case", action="append", dest="cases")
    parser.add_argument("--timeout", type=float, default=300)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        rows = [{"id": "a", "passed": True, "elapsedMs": 10},
                {"id": "b", "passed": False, "elapsedMs": 30}]
        assert summarize(rows) == {"total": 2, "passed": 1, "p50Ms": 10, "p95Ms": 30}
        assert regressions([{"id": "b", "passed": True}], rows) == ["b"]
        assert summarize([])["p95Ms"] is None
        print("self-test passed")
        return 0
    token = os.environ.get("MINIKUN_EVAL_MANAGEMENT_TOKEN", "")
    if not args.url or not token:
        parser.error("--url and MINIKUN_EVAL_MANAGEMENT_TOKEN are required")
    if args.timeout <= 0:
        parser.error("--timeout must be positive")

    def call(path, method="GET"):
        request = Request(args.url.rstrip("/") + "/v1/evals/conversations" + path,
                          headers={"X-Minikun-Personal-Token": token}, method=method)
        with urlopen(request, timeout=args.timeout) as response:
            return json.load(response)

    scenarios = call("")
    if args.cases:
        unknown = set(args.cases) - {row["id"] for row in scenarios}
        if unknown:
            parser.error("unknown cases: " + ", ".join(sorted(unknown)))
        scenarios = [row for row in scenarios if row["id"] in args.cases]
    results = []
    for scenario in scenarios:
        started = time.monotonic()
        try:
            result = call("/" + scenario["id"], "POST")
        except (HTTPError, URLError, TimeoutError) as error:
            # Do not retry automatically: the first evaluation may still be running.
            result = {"id": scenario["id"], "passed": False, "error": type(error).__name__,
                      "elapsedMs": round((time.monotonic() - started) * 1000)}
        results.append(result)
        report = {"summary": summarize(results), "results": results,
                  "note": "Contract checks only; review each answer using its rubric. Times include all scenario turns."}
        if args.baseline:
            report["regressions"] = regressions(json.loads(args.baseline.read_text())["results"], results)
        Path(args.output).write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        print(f'{result["id"]}: {"PASS" if result["passed"] else "FAIL"} ({result["elapsedMs"]} ms)', flush=True)
    print(json.dumps(summarize(results)))
    return 0 if all(row["passed"] for row in results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
