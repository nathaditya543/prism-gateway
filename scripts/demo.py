#!/usr/bin/env python3
"""Walks the Recommended Demo Flow from PRISM_PROBLEM_STATEMENT.md against a running gateway and
the two mock providers. Zero dependencies (Python 3.9+ stdlib).

    python3 scripts/demo.py                # run every step
    python3 scripts/demo.py --pause        # wait for Enter between steps (for recording)
    python3 scripts/demo.py --only 4 8     # run selected steps

Needs: gateway on :8080, mock providers alpha on :9001 and beta on :9002.
Note: the budget-demo key's budget is monthly - after the first run of step 7, both requests are
rejected until the month rolls over (or you delete var/ for a fresh database).
"""

import argparse
import http.client
import json
import sys
import time
import urllib.error
import urllib.request
from urllib.parse import urlparse

KEYS = {
    "search": "prism-sk-search-1a2b3c",
    "research": "prism-sk-research-4d5e6f",
    "free": "prism-sk-free-7g8h9i",
    "budget": "prism-sk-budget-demo-0j1k2l",
}
PRISM_HEADERS = ("x-prism-provider", "x-prism-cache", "x-prism-fallback", "x-prism-cost-usd",
                 "x-prism-route", "x-prism-cache-similarity", "x-prism-cache-bypass")


def http_json(method, url, body=None, headers=None, timeout=30):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method,
                                 headers={"Content-Type": "application/json", **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, {k.lower(): v for k, v in resp.headers.items()}, json.loads(resp.read() or b"{}")
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            parsed = json.loads(raw)
        except ValueError:
            parsed = raw.decode(errors="replace")
        return e.code, {k.lower(): v for k, v in e.headers.items()}, parsed


class Demo:
    def __init__(self, args):
        self.gw = args.url.rstrip("/")
        self.admin = {"X-Admin-Token": args.admin_token}
        self.alpha = args.alpha.rstrip("/")
        self.beta = args.beta.rstrip("/")
        self.pause = args.pause

    def chat(self, key, model, prompt, label=None):
        status, headers, body = http_json("POST", self.gw + "/v1/chat/completions",
                                          {"model": model, "messages": [{"role": "user", "content": prompt}]},
                                          {"Authorization": f"Bearer {KEYS[key]}"})
        shown = {h: headers[h] for h in PRISM_HEADERS if h in headers}
        print(f"  {label or prompt[:60]!r}")
        print(f"    HTTP {status}  " + "  ".join(f"{k}={v}" for k, v in shown.items()))
        if status != 200:
            print(f"    error: {body.get('error', body) if isinstance(body, dict) else body}")
        return status, headers, body

    def latest_log(self, key):
        _, _, logs = http_json("GET", f"{self.gw}/admin/logs?limit=1&key={KEYS[key]}", headers=self.admin)
        return logs[0] if logs else {}

    def step(self, n, title):
        print(f"\n=== Step {n}: {title}")

    def wait(self):
        if self.pause:
            input("  (Enter for next step) ")

    # ---------------------------------------------------------------- steps

    def s1(self):
        self.step(1, "providers and gateway are healthy")
        for name, url in (("alpha", self.alpha), ("beta", self.beta), ("gateway", self.gw)):
            status, _, body = http_json("GET", url + "/health")
            print(f"  {name:8} HTTP {status}  {body}")
        # make sure the mocks start healthy
        for url in (self.alpha, self.beta):
            http_json("POST", url + "/admin/config", {"mode": "ok", "fail_rate": 0, "latency_ms": 0})
        # clear the search team's cache so step 2/4 start from a miss
        _, _, cleared = http_json("DELETE", self.gw + "/admin/cache?key=" + KEYS["search"], headers=self.admin)
        print(f"  cleared search-team cache entries: {cleared.get('removed')}")

    def s2(self):
        self.step(2, "non-streaming request, search key, 'fast' alias")
        self.chat("search", "fast", "What is a message queue and when should I use one?")

    def s3(self):
        self.step(3, "streaming request - tokens arrive one by one")
        u = urlparse(self.gw)
        conn = http.client.HTTPConnection(u.hostname, u.port or 80, timeout=30)
        body = json.dumps({"model": "fast", "stream": True,
                           "messages": [{"role": "user", "content": "Explain how server-sent events work."}]})
        start = time.time()
        conn.request("POST", "/v1/chat/completions", body,
                     {"Authorization": f"Bearer {KEYS['search']}", "Content-Type": "application/json"})
        resp = conn.getresponse()
        print(f"  HTTP {resp.status}  x-prism-provider={resp.getheader('x-prism-provider')} "
              f"x-prism-cache={resp.getheader('x-prism-cache')} (headers after {1000 * (time.time() - start):.0f} ms)")
        print("  ", end="")
        chunks = 0
        while True:
            line = resp.fp.readline()
            if not line:
                break
            if not line.startswith(b"data: "):
                continue
            data = line[6:].strip()
            if data == b"[DONE]":
                print(f"\n  [DONE] after {chunks} chunks, {1000 * (time.time() - start):.0f} ms")
                break
            delta = json.loads(data)["choices"][0]["delta"].get("content", "")
            chunks += 1
            print(delta, end="", flush=True)
        log = self.latest_log("search")
        print(f"  logged: status={log.get('status')} tokens={log.get('prompt_tokens')}+{log.get('completion_tokens')} "
              f"cost=${log.get('cost_usd_exact')}")

    def s4(self):
        self.step(4, "semantic cache: repeat, paraphrase, near-miss (search key, threshold 0.92)")
        self.chat("search", "fast", "How do I reset my password on the dashboard?", "req_cache_a1 (first ask)")
        self.chat("search", "fast", "How do I reset my password on the dashboard?", "req_cache_a1 (exact repeat)")
        self.chat("search", "fast", "What are the steps to reset my dashboard password?", "req_cache_a2 (paraphrase)")
        self.chat("search", "fast", "How do I reset my two-factor authentication on the dashboard?",
                  "req_cache_a3 (near-miss, different intent)")
        self.chat("search", "fast", "What is the current status of the payments service?",
                  "req_no_cache (time-sensitive)")

    def s5(self):
        self.step(5, "smart routing with 'auto' (research key)")
        self.chat("research", "auto", "Prove that the square root of 2 is irrational.", "route_011 short-but-hard")
        print(f"    reason: {self.latest_log('research').get('route_reason')}")
        roster = ("Here is our on-call roster for the next two weeks: Monday - Priya, Tuesday - Chen, Wednesday - "
                  "Amara, Thursday - Diego, Friday - Fatima, Saturday - Lukas, Sunday - Mei, next Monday - Tom, next "
                  "Tuesday - Sara, next Wednesday - Ravi, next Thursday - Ana, next Friday - Kofi, next Saturday - "
                  "Elif, next Sunday - Jonas. Who is on call this Thursday?")
        self.chat("research", "auto", roster, "route_006 long-but-trivial")
        print(f"    reason: {self.latest_log('research').get('route_reason')}")
        _, _, report = http_json("GET", self.gw + "/admin/routing/eval", headers=self.admin)
        print(f"  routing eval: {report['correct']}/{report['total']} = {report['accuracy']:.0%} "
              f"(length-only baseline {report['baseline']['accuracy']:.0%}) - full table: scripts/routing_eval.py")

    def s6(self):
        self.step(6, "burst past the free-tier key's 10 requests/minute")
        outcomes = {}
        for i in range(15):
            status, _, body = http_json("POST", self.gw + "/v1/chat/completions",
                                        {"model": "fast", "messages": [{"role": "user", "content": f"burst {i} {time.time()}"}]},
                                        {"Authorization": f"Bearer {KEYS['free']}"})
            outcomes[status] = outcomes.get(status, 0) + 1
            if status == 429 and outcomes[429] == 1:
                print(f"  first rejection: {body['error']}")
        print(f"  outcomes: {outcomes}")

    def s7(self):
        self.step(7, "budget-demo key: first request spends the tiny budget, second is rejected")
        self.chat("budget", "fast", "What is a message queue?", "request 1")
        self.chat("budget", "fast", "What is a message queue?", "request 2")

    def s8(self):
        self.step(8, "take provider alpha down live -> served by beta")
        http_json("POST", self.alpha + "/admin/config", {"mode": "down"})
        print("  alpha: mode=down")
        self.chat("search", "fast", f"Failover check {time.time():.0f}: what is a circuit breaker?", "request while alpha is down")
        print(f"    attempts: {self.latest_log('search').get('attempts')}")
        http_json("POST", self.alpha + "/admin/config", {"mode": "ok", "latency_ms": 3000})
        print("  alpha: slow (3000 ms) - the gateway's 2500 ms timeout fails over")
        start = time.time()
        self.chat("search", "fast", f"Timeout check {time.time():.0f}: what is backpressure?", "request while alpha is slow")
        print(f"    answered in {1000 * (time.time() - start):.0f} ms, attempts: {self.latest_log('search').get('attempts')}")
        http_json("POST", self.alpha + "/admin/config", {"mode": "ok", "latency_ms": 0, "fail_rate": 0})
        print("  alpha: restored")
        self.chat("search", "fast", f"Recovery check {time.time():.0f}: what is a bulkhead?", "request after restore")

    def s9(self):
        self.step(9, "ops console")
        print(f"  open {self.gw}/console/  (admin token: {self.admin['X-Admin-Token']})")

    def s10(self):
        self.step(10, "verification scripts")
        print(f"  python3 scripts/smoke_test.py --url {self.gw} --key {KEYS['search']} --model fast")
        print(f"  python3 scripts/load_test.py --url {self.gw} --key {KEYS['free']} --model fast "
              f"--requests 30 --concurrency 10 --rpm-limit 10")
        print("  (wait 60s after step 6 before the load test: the free-tier window is still full)")


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--url", default="http://localhost:8080")
    parser.add_argument("--admin-token", default="prism-admin-dev")
    parser.add_argument("--alpha", default="http://localhost:9001")
    parser.add_argument("--beta", default="http://localhost:9002")
    parser.add_argument("--pause", action="store_true", help="wait for Enter between steps")
    parser.add_argument("--only", type=int, nargs="*", help="step numbers to run")
    args = parser.parse_args()

    demo = Demo(args)
    steps = args.only or list(range(1, 11))
    try:
        for n in steps:
            getattr(demo, f"s{n}")()
            demo.wait()
    except urllib.error.URLError as e:
        sys.exit(f"\nCannot reach a service: {e.reason}. Is the gateway on :8080 and are both mocks running?")


if __name__ == "__main__":
    main()
