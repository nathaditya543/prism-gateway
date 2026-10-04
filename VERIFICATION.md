# Prism verification report

All results come from one clean run on a fresh database (`var/` deleted), against the pack's two
mock providers on `:9001` (alpha) and `:9002` (beta). The raw outputs are in
[`verification/`](verification/).

**Environment:** Windows 11, JDK 20 (bytecode target 17), Spring Boot 4.0.8, H2 2.4 in file mode,
Python 3.9 for the pack scripts.

**Contents:**

1. [Smoke test](#1-smoke-test)
2. [Load test: over-admission and accounting](#2-load-test-over-admission-and-accounting)
3. [Accounting and reliability under concurrency](#3-accounting-and-reliability-under-concurrency)
4. [Routing eval](#4-routing-eval)
5. [Semantic cache: threshold, embedder, sample pairs](#5-semantic-cache-threshold-embedder-sample-pairs)
6. [Failure drills](#6-failure-drills)
7. [Latency](#7-latency)
8. [Automated tests](#8-automated-tests)
9. [Hardening log: what was found and fixed](#9-hardening-log-what-was-found-and-fixed)

| Check | Result |
|---|---|
| `scripts/smoke_test.py` | **17 passed, 1 warning, 0 failed** |
| `scripts/load_test.py`: over-admission (free tier, 10 rpm) | **10 accepted / limit 10, 0 over-admitted** |
| Accounting: client totals vs usage API | **exact match**: requests, prompt tokens, completion tokens, cost |
| 200 requests at concurrency 20 | **200/200 served, exact accounting match** |
| 120 requests at concurrency 30, and again at 50 | **120/120 and 120/120 served** |
| Routing eval, `data/routing_eval.jsonl` (graded) | **20/20 (100%)**; length-only baseline 12/20 |
| Routing eval, fresh held-out set (never tuned on) | **23/24 (96%)**; length-only baseline 13/24 |
| Live failover (alpha down, slow, restored) | **beta serves with `x-prism-fallback: true`; slow alpha times out at 2.5 s** |
| Circuit breaker (alpha slow) | **opens after 5 failures, so requests drop from 2.5 s to ~8 ms; back on alpha 5 s after recovery** |
| Budget under a concurrent burst | **1 of 10 admitted** against a budget that covers one request |
| Automated tests (`./mvnw test`) | **62 tests, 0 failures** |

---

## 1. Smoke test

```
$ python scripts/smoke_test.py --url http://localhost:8080 --key prism-sk-search-1a2b3c --model fast
[1] Non-streaming completion + header contract   6 PASS
[2] Streaming                                    4 PASS (SSE chunks, ends with [DONE])
[3] Auth                                         PASS (401)
[4] Unknown model                                2 PASS (404 + error body)
[5] Semantic cache: exact repeat                 PASS (hit)
[6] Semantic cache: paraphrases                  pair A PASS (hit), pair B WARN (miss), semantic check PASS
[7] Semantic cache: unrelated prompt             PASS (miss)
  17 passed, 1 warnings, 0 failed
```

Full output: [`verification/smoke_test.txt`](verification/smoke_test.txt).

**Why pair B warns.** "What is the refund policy for annual plans?" and "If I bought an annual plan,
can I get my money back?" share little vocabulary. They score 0.75 against the search key's 0.92
threshold. I measured a real embedding model on the same pair and it scores lower (0.70); see §5.
I didn't hand-tune synonyms to force the pair, because that would be overfitting to the test.

The smoke test also passes when re-run against a populated cache. Each run salts its prompts with
a random id, and identifiers count as content, so the same question from an earlier run scores
0.67 and is not served.

## 2. Load test: over-admission and accounting

```
$ python scripts/load_test.py --url http://localhost:8080 --key prism-sk-free-7g8h9i --model fast --requests 30 --concurrency 10 --rpm-limit 10
  accepted (200):      10
  rate limited (429):  20
  latency avg/p95 ms:  369 / 391
Client-side accounting totals - compare with your usage API for this key:
  accepted requests:   10
  prompt tokens:       120
  completion tokens:   269
  sum of cost headers: 0.000179 USD (10 of 10 had a numeric header)
Over-admission check OK: 10 accepted <= limit 10.
```

Compared with `GET /admin/usage?key=prism-sk-free-7g8h9i`:

| | Load test (client) | Usage API |
|---|---|---|
| Accepted / served | 10 | `requests: 10` (+ `rejected: 20`) |
| Prompt tokens | 120 | 120 |
| Completion tokens | 269 | 269 |
| Cost (USD) | 0.000179 (6 decimals) | `cost_usd_exact: "0.0001794"` |

They match exactly. The client just prints fewer decimals. The ~370 ms latency is JVM warm-up: this
burst is the first traffic after a fresh start.

The limiter is an exact sliding-window log, with check and record under one per-key lock. The unit
test `SlidingWindowRateLimiterTest.neverOverAdmitsUnderConcurrency` fires 500 simultaneous attempts
from 64 threads at a limit of 10, and exactly 10 are admitted.

## 3. Accounting and reliability under concurrency

**Accounting.** A burst that is never rate-limited stresses the usage counters themselves:

```
$ python scripts/load_test.py ... --key prism-sk-research-4d5e6f --requests 200 --concurrency 20 --rpm-limit 300
  accepted (200): 200    prompt tokens: 2400    completion tokens: 5275    sum of cost headers: 0.003525 USD
```

The usage API reports `requests: 200`, `failed: 0`, `prompt_tokens: 2400`, `completion_tokens: 5275`,
`cost_usd_exact: "0.003525"`. That is an exact match. Each increment is an atomic
`UPDATE ... SET x = x + ?`, written in the same transaction as the request log row.

**Reliability at 3–5× the graded concurrency**
([`load_test_high_concurrency.txt`](verification/load_test_high_concurrency.txt)):

| Burst | Served | Client latency avg / p95 |
|---|---|---|
| 120 requests, concurrency 30 | **120/120** | 101 / 328 ms |
| 120 requests, concurrency 50 | **120/120** | 139 / 344 ms |

Before the hardening in §9, the same kind of burst produced up to 7 × 502 per 200 requests. During
development, six further rounds at concurrency 30 and 50 also served 270/270.

## 4. Routing eval

Run with `python scripts/routing_eval.py [--set pack|holdout|holdout2]`. It uses classification
only, with no upstream calls.

**Method:** engineered signals scored on the prompt's *task sentences* (questions and imperatives),
after removing quoted material and code. Pasted data is ignored. A vague follow-up question
("What could be causing this?") inherits its problem-statement sentence. Threshold 2.0. Details are
in the README.

### Summary, and how generalization was measured

| Set | First router | Current router | Length-only baseline |
|---|---|---|---|
| `routing_eval.jsonl`, 20 graded cases | 20/20 | **20/20** | 12/20 (60%) |
| `routing_holdout.jsonl`, 20 cases | 14/20 | 20/20 * | 9/20 (45%) |
| `routing_holdout2.jsonl`, 24 cases, fresh | 18/24 (75%) | **23/24 (96%)** | 13/24 (54%) |

\* The first held-out set was used to diagnose the first router's misses, so its current score is
no longer an independent measurement.

The process:

1. I wrote the first held-out set before running the router. It showed 70%, and six misses with
   clear causes.
2. I wrote the second held-out set **before changing anything**, and measured the unchanged router
   on it: 75%.
3. I generalized each diagnosed miss into a rule:
   - "show that" proofs
   - symptom diagnosis
   - quantitative reasoning
   - security analysis
   - failure and edge-case analysis
   - word-form matching for domain terms
   - context inheritance for vague follow-ups
4. Only then did I re-measure the fresh set: **96%**. Its single miss was deliberately left alone.

### Pack set: 20/20

Every trap routes correctly:

- **Long but trivial** (route_006 roster, route_007 log, route_010 email): only the task sentence
  is scored, and quoted material is removed. All score −2.0 and go to `fast`.
- **Short but hard:**
  - route_011, route_020 (proofs): +3.5
  - route_012 (estimation): +4.0
  - route_013 (systems): +6.0
  - route_019 (debugging): +5.0

  All go to `smart`.

The length-only baseline gets all eight traps wrong. Per-case reasons are in
[`routing_eval_pack.json`](verification/routing_eval_pack.json).

### Fresh held-out set: 23/24

| Case | Expected → actual | Score | Matched signals |
|---|---|---|---|
| h2_001–h2_012 (12 fast prompts, including a long ID list, a lunch-order roster, a pasted stack trace and a quoted customer message) | fast → **fast** (all 12) | −2.0 … 0.0 | lookups, transformations, definition, or no signal |
| h2_013 Kubernetes CrashLoopBackOff | smart → smart | 2.0 | causal "why" |
| h2_014 heap-building complexity | smart → smart | 5.0 | derivation, algorithmic complexity |
| h2_015 duplicate charges: root causes | smart → smart | 3.5 | causal analysis, open-ended method, multi-part |
| h2_016 multi-region rate limiter | smart → smart | 2.5 | design, domain [consistency] |
| h2_017 UUID vs auto-increment, sharded | smart → smart | 2.5 | justify, domain [sharded] |
| h2_018 "ETL 3x slower… What could explain this?" | smart → smart | 2.0 | symptom diagnosis (problem statement inherited) |
| h2_019 halting problem "show that" | smart → smart | 3.0 | formal proof |
| **h2_020 lock-free queue in C++** | smart → **fast** ✗ | 1.0 | multi-part only: no signal for concurrent-data-structure difficulty |
| h2_021 migrate 50M rows without downtime | smart → smart | 2.0 | open-ended method, domain [migrate, schema] |
| h2_022 expected rolls until a six | smart → smart | 3.0 | quantitative reasoning, multi-part |
| h2_023 architecture critique | smart → smart | 2.0 | design |
| h2_024 "logs users into someone else's account… what could be going on?" | smart → smart | 2.0 | symptom diagnosis |

No fast prompt is routed to `smart` in any set, so the router never overspends. Its remaining
failure mode is hard prompts with no recognizable task signal. Full reports:
[`routing_eval_holdout.json`](verification/routing_eval_holdout.json) and
[`routing_eval_holdout2.json`](verification/routing_eval_holdout2.json).

## 5. Semantic cache: threshold, embedder, sample pairs

**Embedder:** `lexical-bow-v1`, the documented local substitute. It's a normalized bag of content
words with synonym canonicalization, negation kept, stopwords and question-framing words removed,
and light stemming; similarity is the cosine. **Thresholds** are per key from the seed data: search
0.92, free-tier 0.85; research and budget-demo have caching disabled.

**Compared with a real embedding model** (`scripts/compare_embeddings.py`, `all-MiniLM-L6-v2`,
normalized cosine; [`cache_embedding_comparison.txt`](verification/cache_embedding_comparison.txt)):

| Pair | Should | Lexical (used) | MiniLM |
|---|---|---|---|
| `req_cache_a1` vs `a2` (paraphrase) | hit | **1.00** ✓ | 0.95 ✓ |
| `a1` vs `a3` (password reset vs 2FA reset) | miss | **0.67** ✓ | 0.76 ✓ |
| `b1` vs `b2` (paraphrase, little shared vocabulary) | hit | 0.75 ✗ | 0.70 ✗ |
| Same question, different smoke-test salt | miss | **0.67** ✓ | 0.89 (✗ at 0.85) |
| Unrelated prompt | miss | 0.24 ✓ | 0.23 ✓ |
| "Convert 72°F…" vs "Convert 73°F…" | miss | **0.80** ✓ | **0.96 ✗ false hit** |

The dense model doesn't rescue pair B, and at these thresholds it would serve wrong answers when
only a number or ID differs. The lexical matcher has the wider margin between the intended hit
(1.00) and the closest must-miss (0.67 at 0.92, and 0.80 for the numeric pair at 0.85).

**Live confirmation** ([`demo_flow.txt`](verification/demo_flow.txt), step 4):

```
req_cache_a1 (first ask)       x-prism-cache=miss  x-prism-cost-usd=0.00001455
req_cache_a1 (exact repeat)    x-prism-cache=hit   x-prism-cost-usd=0  x-prism-cache-similarity=1.0
req_cache_a2 (paraphrase)      x-prism-cache=hit   x-prism-cost-usd=0  x-prism-cache-similarity=1.0
req_cache_a3 (near-miss)       x-prism-cache=miss  x-prism-cost-usd=0.0000177
req_no_cache (time-sensitive)  x-prism-cache=miss  x-prism-cache-bypass=time-sensitive prompt
```

**Tenant isolation.** The scope is virtual key + tier + context hash.
`GatewayIntegrationTest.semanticCacheHitsParaphrasesButNeverAcrossTenants` sends the same prompt
with a second key and gets a `miss`, with the upstream called again.

## 6. Failure drills

From [`demo_flow.txt`](verification/demo_flow.txt) (steps 6–8) and
[`circuit_breaker_drill.txt`](verification/circuit_breaker_drill.txt):

| Drill | Result |
|---|---|
| Burst of 15 on the free-tier key (10 rpm) | `{200: 10, 429: 5}`, each rejection `rate_limit_exceeded` with `Retry-After` |
| Budget-demo key, two requests | 1st: 200, $0.00001395 (above the $0.00001 budget: the documented one-request overshoot). 2nd: **402 `budget_exceeded`** |
| Alpha `mode: down` | 200 from `beta/beta-small`, `x-prism-fallback: true`, attempts `alpha/alpha-small:503 -> beta/beta-small:ok` |
| Alpha `latency_ms: 3000`, one request | 200 from beta in 2526 ms: the 2500 ms timeout fired, never a hang |
| Alpha restored | Next request on `alpha/alpha-small`, `x-prism-fallback: false` |

**Circuit breaker under a sustained slowdown:**

```
alpha latency_ms=3000 (gateway request timeout 2500 ms)
  requests 1-5: HTTP 200  ~2.51 s  beta/beta-small     <- each pays the timeout
  requests 6-8: HTTP 200  ~0.008 s beta/beta-small     <- circuit open: alpha skipped
  circuit: {'alpha': 'open', 'beta': 'closed'}
alpha restored; one request per second
  t+1s .. t+4s: beta/beta-small                        <- still in the 5 s cooldown
  t+5s .. t+7s: alpha/alpha-small                      <- probe succeeded, circuit closed
  circuit: {'alpha': 'closed', 'beta': 'closed'}
```

These are covered by tests:

| Scenario | Result |
|---|---|
| Alpha `fail_rate: 0.3` | Retries absorb it, e.g. `alpha/alpha-small:500 -> alpha/alpha-small:ok` |
| Both providers down | 502 `upstream_error` with the full attempt trail |
| Concurrent budget burst | 10 simultaneous requests against a budget that one request exceeds: exactly 1 admitted, 9 × 402 (`concurrentBurstCannotOvershootABudgetThatCoversOneRequest`) |
| Upstream breaks mid-stream | Three content chunks, then `data: {"error": {..., "code": "stream_interrupted"}}`. No `[DONE]`, no splice from beta. Logged as `stream_interrupted`, not billed. |

## 7. Latency

From `GET /admin/stats` after the run ([`stats.json`](verification/stats.json)). There were 490
non-streaming requests served by an upstream, including the concurrency-50 bursts.

| | avg | p50 | p95 | max |
|---|---|---|---|---|
| Total request latency (ms) | 92 | 21 | 283 | 2521 |
| **Gateway-added latency (ms)** | 41 | **1** | 260 | 699 |

**How it's measured:** per request, total time minus time inside upstream calls. So added latency
includes auth, limits, cache lookup, database writes, **bulkhead queueing and retry backoff**.

**What it shows:**

- **A typical request adds about 1 ms.**
- **The tail is the overload bursts.** At concurrency 30–50 the mock refuses connections. The
  gateway then waits a jittered 100–300 ms and retries, rather than returning 502. That's the right
  trade-off, but it shows up as "added" latency.
- **The 2521 ms maximum** is the slow-provider drill: a 2500 ms timeout, then beta.

## 8. Automated tests

```
$ ./mvnw test
Tests run: 10  CacheDecisionTest            paraphrase vs near-miss, numbers, negation, salts, tenant/tier/context scope, bypass rules
Tests run: 13  GatewayIntegrationTest       real HTTP + in-process fakes: header contract & cost, failover, all-down 502, streaming,
                                            mid-stream failure, cache isolation, cached SSE replay, concurrent rate limit + usage
                                            reconciliation, budget, concurrent budget burst, every rejection type, auto routing, admin auth
Tests run: 4   BudgetServiceTest            admission, in-flight reservations, release, 200-thread burst admits exactly one
Tests run: 4   SlidingWindowRateLimiterTest exact limit, sliding (not refilling) window, per-key isolation, 500 concurrent attempts
Tests run: 16  UpstreamInvokerTest          retry vs failover vs fatal, bounded retries, timeouts, broken connections, refusal from a
                                            provider that just answered, chain re-walk, circuit open / probe / re-open / all-open, bulkhead
Tests run: 10  DifficultyRouterTest         traps, quote stripping, follow-up context inheritance, "this Thursday" not inherited,
                                            word forms, >= 90% on the fresh held-out set, beats the length baseline on the pack set
Tests run: 5   CostCalculatorTest           per-1M pricing, exact header sums, plain-decimal format
Total: 62 tests, 0 failures
```

The integration test class was also run 5 times in a row with no failures, to rule out the flaky
test described in §9.

## 9. Hardening log: what was found and fixed

Each weakness from the first version was fixed and then re-measured:

| Weakness (v1) | Fix | Before → after |
|---|---|---|
| Router generalized poorly (70% on held-out prompts) | Signals generalized from diagnosed misses; vague follow-ups inherit their problem statement; domain terms match word forms | Fresh held-out set: **75% → 96%**; pack set still 20/20 |
| Concurrent requests could overshoot a budget by one request *each* | In-flight cost reservations under a per-key lock | 10-request burst on a one-request budget: **up to 10 → exactly 1** admitted |
| 502s under heavy concurrency (both mocks refusing connections) | Broken connections retried; chain re-walked when every provider refused; refusals from a provider that just answered retried rather than failed over; jittered backoff; per-provider bulkhead | **7 × 502 per 200** at concurrency 20 → **0 in 200 + 240** at concurrency 20 / 30 / 50 |
| Provider health measured but ignored (each request paid a 2.5 s timeout while alpha was slow) | Per-provider circuit breaker: open after 5 failures, one probe after 5 s | Slow alpha: **2.5 s → ~8 ms** per request after 5 failures; back on alpha **5 s** after recovery |
| Cache pair B misses | Investigated, not changed: a real embedding model scores it *lower* and adds false hits on numbers and IDs | Kept the lexical matcher; evidence in §5 |

**Root cause of the concurrency 502s.** The pack's mock uses Python's `socketserver` default listen
backlog of 5. On Windows, connection attempts beyond it are refused immediately, so for a few
milliseconds a healthy provider looks dead. Treating every refusal as an outage was wrong: a
provider that answered moments ago is up, and only its accept queue is full.

**A flaky test, found and fixed.** In one verification run, the concurrent-budget integration test
admitted 2 requests instead of 1. The cause was the test's assumption, not the gateway: with the
fake provider, one request cost less than the budget. So a request arriving after the first
completed was legitimately admitted under the sequential rule. The test now uses a request that
costs more than the whole budget, so the expected result no longer depends on timing.
