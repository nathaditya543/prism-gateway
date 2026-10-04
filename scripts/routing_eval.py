#!/usr/bin/env python3
"""One-command routing eval for Prism's `auto` router. Zero dependencies (Python 3.9+ stdlib).

Runs every case in data/routing_eval.jsonl (or the held-out set) through the gateway's router
via the admin API and prints per-case expected vs actual, the reason for each decision, overall
accuracy, and the length-only baseline for comparison. Classification only: no upstream calls.

Usage:
    python3 scripts/routing_eval.py [--url http://localhost:8080] [--admin-token prism-admin-dev]
                                    [--set pack|holdout|holdout2] [--json report.json]

Exit code is 1 if accuracy is below --min-accuracy (default 0.8).
"""

import argparse
import json
import sys
import urllib.error
import urllib.request


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://localhost:8080")
    parser.add_argument("--admin-token", default="prism-admin-dev")
    parser.add_argument("--set", default="pack", help="pack (data/routing_eval.jsonl) or a data/routing_<set>.jsonl name, e.g. holdout, holdout2")
    parser.add_argument("--json", help="also write the full report to this file")
    parser.add_argument("--min-accuracy", type=float, default=0.8)
    args = parser.parse_args()

    req = urllib.request.Request(f"{args.url.rstrip('/')}/admin/routing/eval?set={args.set}",
                                 headers={"X-Admin-Token": args.admin_token})
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            report = json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        sys.exit(f"eval request failed: HTTP {e.code} {e.read().decode(errors='replace')[:200]}")
    except urllib.error.URLError as e:
        sys.exit(f"cannot reach gateway at {args.url}: {e.reason}")

    print(f"Routing eval: {report['file']}")
    print(f"Method: {report['method']}\n")
    print(f"  {'case':<10} {'expected':<8} {'actual':<8} {'result':<6} {'score':>5}  reason")
    for c in report["cases"]:
        mark = "PASS" if c["result"] == "pass" else "FAIL"
        print(f"  {c['id']:<10} {c['expected']:<8} {c['actual']:<8} {mark:<6} {c['score']:>5}  {c['reason']}")
    base = report["baseline"]
    print(f"\n  accuracy: {report['correct']}/{report['total']} = {report['accuracy']:.1%}")
    print(f"  baseline ({base['method']}): {base['correct']}/{report['total']} = {base['accuracy']:.1%}")

    if args.json:
        with open(args.json, "w", encoding="utf-8") as f:
            json.dump(report, f, indent=2)
        print(f"\n  full report written to {args.json}")
    sys.exit(0 if report["accuracy"] >= args.min_accuracy else 1)


if __name__ == "__main__":
    main()
