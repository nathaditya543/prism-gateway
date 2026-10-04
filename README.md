# Prism — LLM Gateway and Semantic Cache

Prism sits between applications and model providers. Callers use one OpenAI-compatible endpoint
with a virtual key. The gateway then:

- authenticates the key, enforces its allowlist, rate limit and monthly budget
- routes `auto` traffic by prompt difficulty
- answers repeated questions from a per-tenant semantic cache
- calls providers with timeouts, retries and failover
- streams responses token by token
- meters every token and dollar from provider-reported usage
- writes a request log entry for every request, including rejections

Built with **Java 17+ / Spring Boot 4**, an embedded **H2** database in file mode, and the pack's two
mock providers as upstreams.

| Must Have | Where |
|---|---|
| Load pricing, seed keys, gateway config | `config/ConfigFiles`, `keys/SeedLoader` |
| OpenAI-compatible completions over 2 providers + header contract | `gateway/ChatGatewayService`, `provider/OpenAiCompatibleAdapter` |
| Streaming pass-through | `gateway/ChatGatewayService#relay`, `provider/UpstreamStream` |
| Virtual keys, allowlist, rate limits, budgets | `gateway/ChatGatewayService#admit`, `limits/*` |
| Aliases, retries with backoff, failover | `catalog/ModelCatalog`, `provider/UpstreamInvoker` |
| `auto` smart routing + one-command eval | `routing/DifficultyRouter`, `scripts/routing_eval.py` |
| Semantic cache, per-key scope | `cache/*` |
| Metering, request logs, usage API | `usage/*`, `admin/AdminController` |
| Ops console | `src/main/resources/static/console/index.html` → `/console/` |
| Automated tests | `src/test/java/...` (`./mvnw test`) |

Beyond the Must Have, Prism includes:

- degradation-based routing (per-provider circuit breakers) and a provider-health endpoint
- per-provider concurrency limits (bulkheads)
- budget reservations that keep concurrent requests from overshooting a budget
- cache replay as SSE, and client `Cache-Control` support
- per-request routing explanations and gateway-added latency stats
- two held-out routing eval sets

Verification results are in [VERIFICATION.md](VERIFICATION.md). To find the code behind any feature, see the
[Code map](#code-map).

---

## Quick start

**You need** JDK 17 or newer, and Python 3.9+ for the mock providers and scripts. Maven is not
needed because the wrapper downloads it.

```bash
# terminal 1 and 2: the two mock providers from the capstone pack
python3 scripts/mock_provider.py --port 9001 --name alpha
python3 scripts/mock_provider.py --port 9002 --name beta

# terminal 3: the gateway on :8080 (run from the repository root, it reads ./data)
./mvnw spring-boot:run
#   or: ./mvnw -DskipTests package && java -jar target/prism-gateway-0.0.1-SNAPSHOT.jar
```

On Windows use `mvnw.cmd` and `python` instead of `./mvnw` and `python3`.

Then:

```bash
curl -s localhost:8080/health
curl -si localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer prism-sk-search-1a2b3c" -H "Content-Type: application/json" \
  -d '{"model": "fast", "messages": [{"role": "user", "content": "What is a message queue?"}]}'
```

- **Ops console:** <http://localhost:8080/console/>. Enter the admin token `prism-admin-dev`.
- **Full demo flow** from the problem statement: `python3 scripts/demo.py`. Add `--pause` to stop
  between steps while recording.
- **Seed keys:**
  - `prism-sk-search-1a2b3c`
  - `prism-sk-research-4d5e6f`
  - `prism-sk-free-7g8h9i`
  - `prism-sk-budget-demo-0j1k2l`

  They're loaded from `data/seed_keys.json` on every start.

### Run with Docker

This needs Docker with Compose v2, and nothing else: no JDK or Python on the host.

```bash
docker compose up --build
```

That starts three containers:

| Service | Port | Image |
|---|---|---|
| `gateway` | 8080 | `prism-gateway:0.0.1`, built from the `Dockerfile` |
| `alpha` | 9001 | `python:3.12-slim` running the pack's mock provider |
| `beta` | 9002 | `python:3.12-slim` running the pack's mock provider |

Everything in this README then works unchanged against `localhost`: the curl examples, the console,
`scripts/demo.py`, `scripts/demo_helpers.sh`, and failure injection on `localhost:9001`.

- The gateway reaches the mocks by service name. Compose sets `PRISM_PROVIDER_ALPHA_BASE_URL` and
  `PRISM_PROVIDER_BETA_BASE_URL` for this.
- The H2 database lives in the `prism-data` volume, so it survives restarts.
  `docker compose down -v` gives you a fresh database.

**From the prebuilt image file** instead of building it yourself:

```bash
docker load -i prism-gateway-0.0.1.tar.gz
docker run --rm -p 8080:8080 \
  -e PRISM_PROVIDER_ALPHA_BASE_URL=http://host.docker.internal:9001/v1 \
  -e PRISM_PROVIDER_BETA_BASE_URL=http://host.docker.internal:9002/v1 \
  prism-gateway:0.0.1
```

This assumes the mock providers are running on the host. On Linux, add
`--add-host=host.docker.internal:host-gateway`.

**About the image:**

- It's a two-stage build: a JDK stage compiles the jar, then the image ships only an Eclipse
  Temurin 17 **JRE** with the jar and the `data/` files.
- It runs as a **non-root** user.
- Extra JVM flags go in `JAVA_OPTS`, e.g. `-e JAVA_OPTS=-Xmx512m`.

### Configuration

| Property / env var | Default | Meaning |
|---|---|---|
| `PORT` | `8080` | HTTP port |
| `PRISM_ADMIN_TOKEN` | `prism-admin-dev` | Admin API and console token. **Change it outside local dev.** |
| `PRISM_PROVIDER_<NAME>_API_KEY` | from config file | Provider credential override, e.g. `PRISM_PROVIDER_ALPHA_API_KEY` |
| `PRISM_PROVIDER_<NAME>_BASE_URL` | from config file | Provider URL override |
| `prism.gateway-config` | `data/gateway_config.sample.json` | Providers, aliases, fallback chains, retry policy |
| `prism.pricing-file` / `prism.seed-keys-file` | `data/model_pricing.json` / `data/seed_keys.json` | Price table and tenants |
| `prism.upstream.request-timeout-ms` | `2500` | Max wait for an upstream response, or for headers when streaming |
| `prism.upstream.connect-timeout-ms` | `1000` | TCP connect timeout |
| `prism.upstream.stream-idle-timeout-ms` | `5000` | Max silence between SSE events before a stream is declared dead |
| `prism.upstream.max-in-flight-per-provider` | `16` | Bulkhead: concurrent calls per provider (streams hold a slot until they end). `0` disables it. |
| `prism.upstream.queue-timeout-ms` | `1000` | How long a call waits for a bulkhead slot before failing over |
| `prism.cache.ttl-minutes` | `1440` | Cache entry lifetime |
| `prism.cache.max-entries-per-scope` | `500` | Oldest entries in a scope are evicted past this |
| `spring.datasource.url` | `jdbc:h2:file:./var/prismdb` | Database. Delete `var/` for a fresh start. |

Set properties as `--prism.upstream.request-timeout-ms=4000` on the command line or in
`application.properties`.

### Tests

```bash
./mvnw test
```

There are 62 tests. The integration test boots the real app on a random port with in-process fake
providers, so no mock servers are needed. Coverage:

- cost math and exact header reconciliation
- rate-limiter correctness under 500 concurrent attempts
- budget reservations under a concurrent burst
- cache decisions: paraphrase vs near-miss, tenant/tier/context isolation, bypass rules
- router accuracy on the pack set and the fresh held-out set
- retry/failover dispositions, chain retries, circuit breakers, bulkheads
- end to end: streaming, mid-stream failure, failover, budget, rate limit, error types, admin auth

---

## API

### Data plane: `POST /v1/chat/completions`

OpenAI-compatible request and response. Authenticate with `Authorization: Bearer <virtual-key>`.
`model` is an alias (`fast`, `smart`, `auto`) or a priced model name, and must be on the key's
allowlist. Unknown request fields such as `temperature` are forwarded upstream unchanged.

**Response headers**

| Header | Value |
|---|---|
| `x-prism-provider` | Provider and model that served it, e.g. `alpha/alpha-small`; `cache:alpha/alpha-small` on a cache hit |
| `x-prism-cache` | `hit` or `miss` (a bypassed cache reports `miss`, plus `x-prism-cache-bypass: <reason>`) |
| `x-prism-fallback` | `true` only when a fallback in the chain served it |
| `x-prism-cost-usd` | Exact cost, e.g. `0.0000165`; `0` for cache hits. Not sent on upstream streams (cost lands in the log and usage API). |
| `x-prism-request-id` | Correlates with `/admin/logs` |
| `x-prism-route` | `auto` only: chosen tier and score, e.g. `smart; score=3.5` |
| `x-prism-cache-similarity` | Cache hits only: cosine similarity of the match |
| `x-prism-retries` | Present when retries were needed |
| `x-ratelimit-limit-requests` / `x-ratelimit-remaining-requests` | The key's rpm and what is left in the window |

**Errors.** Every rejection has an OpenAI-style body:
`{"error": {"message", "type", "code", "param"}}`. Checks run in this order:

| # | Check | Status | `type` |
|---|---|---|---|
| 1 | Missing, unknown or disabled virtual key | 401 | `authentication_error` |
| 2 | Malformed body (bad JSON, empty `messages`, bad roles) | 400 | `invalid_request_error` |
| 3 | Unknown model or alias | 404 | `not_found_error` |
| 4 | Model not on the key's allowlist | 403 | `model_not_allowed` |
| 5 | Rate limit exceeded (adds `Retry-After`) | 429 | `rate_limit_exceeded` |
| 6 | Monthly budget exhausted, counting requests in flight | 402 | `budget_exceeded` |
| — | Every provider in the chain failed | 502 | `upstream_error` |
| — | Provider rejected the request itself (e.g. invalid parameter) | 400 | `invalid_request_error` |

**Request headers.** The client may send `Cache-Control: no-cache` to skip the lookup but still
store the result, or `no-store` to keep the cache out entirely.

### Admin plane

Send the `X-Admin-Token: <token>` header, or `Authorization: Bearer <token>`. `GET /health` is public.

| Endpoint | Purpose |
|---|---|
| `GET /health` | Database reachable, keys loaded, providers configured |
| `GET /admin/usage?key=&from=&to=` | Totals for a key over inclusive UTC dates (default: this month): `requests` (served), `rejected`, `failed`, tokens, `cost_usd` (+ `cost_usd_exact`), `cache_hits`, budget status. Without `key`: one row per key. |
| `GET /admin/logs?key=&status=&limit=` | Recent request log entries, newest first |
| `GET /admin/stats` | Totals, counts by status, total and gateway-added latency percentiles |
| `GET /admin/cache/stats` | Hits / misses / bypassed / hit rate / live entries, overall and per key |
| `POST /admin/cache/similarity` `{"a","b"}` | How similar the cache considers two prompts, and the terms compared |
| `DELETE /admin/cache?key=` | Clear one key's cache, or all |
| `GET /admin/routing/eval?set=pack\|holdout\|holdout2` | Routing eval report (accuracy, per-case expected vs actual, reasons, length baseline) |
| `POST /admin/routing/classify` `{"prompt"}` | Explain how `auto` would route a prompt |
| `GET /admin/providers/health` | Per provider: rolling 60 s attempts, errors, error rate, avg/p95 latency, plus circuit-breaker state and bulkhead slots in use |
| `GET /admin/keys` | Key configuration (masked) and month-to-date spend |

One-command routing eval (gateway running): `python3 scripts/routing_eval.py`. Add `--set holdout`
or `--set holdout2` for the held-out sets, and `--json report.json` to save the full report.

---

## Architecture

```
client ──► ChatCompletionController ──► ChatGatewayService (one pipeline per request)
                                          │ 1 authenticate ─ KeyService (in-memory snapshot of virtual_keys)
                                          │ 2 validate     ─ ChatRequest
                                          │ 3 resolve      ─ ModelCatalog (aliases → [primary, fallbacks...])
                                          │ 4 allowlist
                                          │ 5 rate limit   ─ SlidingWindowRateLimiter (in memory, per-key lock)
                                          │ 6 auto route   ─ DifficultyRouter
                                          │ 7 budget       ─ BudgetService: spend (usage_monthly) + in-flight reservations
                                          │ 8 cache        ─ CachePolicy + SemanticCache (memory index, H2-backed)
                                          │ 9 upstream     ─ UpstreamInvoker ─► CircuitBreakers ─► BulkheadAdapter ─► ProviderAdapter ─► alpha / beta
                                          │10 meter + log  ─ CostCalculator, UsageRecorder (request_logs + usage_monthly, one tx)
                                          ▼
                     JSON reply, or SSE relay flushed chunk by chunk
```

```
src/main/java/com/prism/gateway/
  config/    gateway config + price table loaders, properties
  catalog/   alias → target chain resolution, startup validation
  keys/      virtual keys: entity, in-memory lookup, seed loader
  limits/    sliding-window rate limiter, budget admission with reservations
  routing/   difficulty router, routing eval
  cache/     embedder, cache policy (scope + bypass rules), semantic cache store
  provider/  adapter interface, OpenAI-compatible HTTP adapter, retry/failover invoker, circuit breakers,
             bulkheads, health tracker
  usage/     cost calculator, request log + monthly usage persistence
  gateway/   data-plane controller and pipeline
  admin/     admin API, admin auth, health, read-side queries
```

**Files outside `src/`:**

- From the Airtribe capstone pack, unchanged:
  - `PRISM_PROBLEM_STATEMENT.md` and `docs/`
  - `data/` (except the two `routing_holdout*.jsonl` files)
  - `scripts/mock_provider.py`, `smoke_test.py`, `load_test.py`, `validate_pack.py`
- Added for this project:
  - `scripts/routing_eval.py` (one-command routing eval)
  - `scripts/demo.py` (the 10-step demo flow)
  - `scripts/demo_helpers.sh` (shell shortcuts for live demos: `chat`, `stream`, `probe`, `alpha down`, …)
  - `scripts/compare_embeddings.py` (optional: cache matcher vs a real embedding model)
  - `data/routing_holdout.jsonl`, `data/routing_holdout2.jsonl` (held-out routing cases)
  - `verification/` (raw outputs behind [VERIFICATION.md](VERIFICATION.md))

**Storage** is H2 in file mode at `./var/prismdb`, managed through JPA.

| Table | Holds |
|---|---|
| `virtual_keys` | Tenants |
| `request_logs` | One row per request; no prompt or response bodies |
| `usage_monthly` | Per-key, per-month running totals for cheap budget checks |
| `cache_entries` | Cached completions |

Moving to PostgreSQL only means changing `spring.datasource.*`; the SQL is portable.

---

## Code map

Where each feature lives, in the order the demo shows it. Links jump to the line on GitHub. All Java
paths are under `src/main/java/com/prism/gateway/`.

### The request pipeline (start here)

| What | Code |
|---|---|
| The endpoint teams call: `POST /v1/chat/completions` | [`ChatCompletionController.complete`](src/main/java/com/prism/gateway/gateway/ChatCompletionController.java#L27) |
| **The whole pipeline, step by step**: auth → validate → model → allowlist → rate limit → route → budget → cache → upstream → meter | [`ChatGatewayService.handle`](src/main/java/com/prism/gateway/gateway/ChatGatewayService.java#L98) |
| Key check (401) | [`ChatGatewayService.authenticate`](src/main/java/com/prism/gateway/gateway/ChatGatewayService.java#L137) |
| Request validation (400) | [`ChatRequest.parse`](src/main/java/com/prism/gateway/gateway/ChatRequest.java) |
| Unknown model (404), allowlist (403), rate limit (429) | [`ChatGatewayService.admit`](src/main/java/com/prism/gateway/gateway/ChatGatewayService.java#L154) |
| Error responses in OpenAI format | [`ChatGatewayService.reject`](src/main/java/com/prism/gateway/gateway/ChatGatewayService.java#L472) |

### Keys, aliases and config

| What | Code |
|---|---|
| Seed the 4 teams from `data/seed_keys.json` on startup | [`SeedLoader.run`](src/main/java/com/prism/gateway/keys/SeedLoader.java#L52) |
| In-memory key lookup | [`KeyService`](src/main/java/com/prism/gateway/keys/KeyService.java) |
| Load providers, aliases and retry policy from `data/gateway_config.sample.json` | [`ConfigFiles.gatewayConfig`](src/main/java/com/prism/gateway/config/ConfigFiles.java#L34) |
| Load the price table | [`ConfigFiles.priceTable`](src/main/java/com/prism/gateway/config/ConfigFiles.java#L83) |
| `fast` → [alpha-small, beta-small] (alias → fallback chain) | [`ModelCatalog.targetsFor`](src/main/java/com/prism/gateway/catalog/ModelCatalog.java#L46) |

### Smart routing: `"model": "auto"`

| What | Code |
|---|---|
| Where `auto` is decided in the pipeline | [`ChatGatewayService.chooseTier`](src/main/java/com/prism/gateway/gateway/ChatGatewayService.java#L213) |
| **The difficulty classifier**: strip quotes, keep task sentences, score signals | [`DifficultyRouter.classify`](src/main/java/com/prism/gateway/routing/DifficultyRouter.java#L144) |
| The scoring signals and their weights (proof +3, lookup −2, …) | [`DifficultyRouter.SIGNALS`](src/main/java/com/prism/gateway/routing/DifficultyRouter.java#L48) |
| Expert vocabulary and multi-part bonuses | [`DOMAIN_TERMS`](src/main/java/com/prism/gateway/routing/DifficultyRouter.java#L105), [`MULTI_PART`](src/main/java/com/prism/gateway/routing/DifficultyRouter.java#L115) |
| "Is this sentence the task?" (question or instruction verb) | [`isTaskSentence`](src/main/java/com/prism/gateway/routing/DifficultyRouter.java#L223) |
| "What could be causing **this**?" inherits the previous sentence | [`refersBack`](src/main/java/com/prism/gateway/routing/DifficultyRouter.java#L215) |
| The threshold: score ≥ 2 → smart | [`COMPLEX_THRESHOLD`](src/main/java/com/prism/gateway/routing/DifficultyRouter.java#L38) |
| The routing eval (accuracy vs the length-only baseline) | [`RoutingEvalService.evaluate`](src/main/java/com/prism/gateway/routing/RoutingEvalService.java#L39), [`scripts/routing_eval.py`](scripts/routing_eval.py) |

### Semantic cache

| What | Code |
|---|---|
| Should this request use the cache? Per-team scope, time-sensitive bypass | [`CachePolicy.decide`](src/main/java/com/prism/gateway/cache/CachePolicy.java#L56) |
| Words that make a prompt time-sensitive ("current", "today", …) | [`CachePolicy.TIME_SENSITIVE`](src/main/java/com/prism/gateway/cache/CachePolicy.java#L32) |
| Turning a question into key words: synonyms, stopwords, stemming | [`LexicalEmbedder.tokens`](src/main/java/com/prism/gateway/cache/LexicalEmbedder.java#L77) |
| The similarity score (cosine) | [`SparseVector.cosine`](src/main/java/com/prism/gateway/cache/SparseVector.java#L37) |
| Find the closest cached question above the threshold | [`SemanticCache.lookup`](src/main/java/com/prism/gateway/cache/SemanticCache.java#L75) |
| Save a successful answer | [`SemanticCache.store`](src/main/java/com/prism/gateway/cache/SemanticCache.java#L100) |
| Building the cache-hit response (cost 0) | [`ChatGatewayService.cachedReply`](src/main/java/com/prism/gateway/gateway/ChatGatewayService.java#L355) |

### Rate limits and budgets

| What | Code |
|---|---|
| **Sliding-window rate limiter** (exactly N per 60 s, race-safe) | [`SlidingWindowRateLimiter.tryAcquire`](src/main/java/com/prism/gateway/limits/SlidingWindowRateLimiter.java#L30) |
| **Budget admission with in-flight reservations** | [`BudgetService.admit`](src/main/java/com/prism/gateway/limits/BudgetService.java#L51) |
| Where the budget is checked in the pipeline (402) | [`ChatGatewayService.reserveBudget`](src/main/java/com/prism/gateway/gateway/ChatGatewayService.java#L175) |
| Monthly spend: atomic `UPDATE … SET cost = cost + ?` | [`UsageStore.add`](src/main/java/com/prism/gateway/usage/UsageStore.java#L33) |

### Providers, failover and streaming

| What | Code |
|---|---|
| **Retry / failover loop** over the fallback chain | [`UpstreamInvoker.invoke`](src/main/java/com/prism/gateway/provider/UpstreamInvoker.java#L56), [`tryTarget`](src/main/java/com/prism/gateway/provider/UpstreamInvoker.java#L96) |
| Which errors retry vs fail over (500 → retry, 503 → switch, …) | [`UpstreamException.disposition`](src/main/java/com/prism/gateway/provider/UpstreamException.java#L57) |
| **Circuit breaker**: open after 5 failures, probe after 5 s | [`CircuitBreakers.allow`](src/main/java/com/prism/gateway/provider/CircuitBreakers.java#L37), [`onFailure`](src/main/java/com/prism/gateway/provider/CircuitBreakers.java#L72) |
| Jittered backoff between retries | [`UpstreamInvoker.sleep`](src/main/java/com/prism/gateway/provider/UpstreamInvoker.java#L174) |
| Bulkhead: max 16 calls per provider | [`BulkheadAdapter.acquire`](src/main/java/com/prism/gateway/provider/BulkheadAdapter.java#L61) |
| HTTP call to a provider, with timeouts | [`OpenAiCompatibleAdapter.complete`](src/main/java/com/prism/gateway/provider/OpenAiCompatibleAdapter.java#L52), [`openStream`](src/main/java/com/prism/gateway/provider/OpenAiCompatibleAdapter.java#L72) |
| **Streaming relay**: forward each chunk as it arrives; mid-stream failure handling | [`ChatGatewayService.relay`](src/main/java/com/prism/gateway/gateway/ChatGatewayService.java#L262) |
| Reading upstream SSE events, with an idle watchdog | [`UpstreamStream.nextData`](src/main/java/com/prism/gateway/provider/UpstreamStream.java#L46) |

### Metering, logs, admin and console

| What | Code |
|---|---|
| Cost = tokens × price per 1M | [`CostCalculator.cost`](src/main/java/com/prism/gateway/usage/CostCalculator.java#L28) |
| Log row + usage increment in one transaction | [`UsageRecorder.record`](src/main/java/com/prism/gateway/usage/UsageRecorder.java#L27) |
| What a request log row contains | [`RequestLogEntity`](src/main/java/com/prism/gateway/usage/RequestLogEntity.java) |
| Usage API query (`/admin/usage`) | [`AdminQueries.usage`](src/main/java/com/prism/gateway/admin/AdminQueries.java#L33) |
| All admin endpoints | [`AdminController`](src/main/java/com/prism/gateway/admin/AdminController.java) |
| Admin token check | [`AdminAuthInterceptor.preHandle`](src/main/java/com/prism/gateway/admin/AdminAuthInterceptor.java#L28) |
| The ops console page | [`static/console/index.html`](src/main/resources/static/console/index.html) |

### Tests (`src/test/java/com/prism/gateway/`)

| What | Code |
|---|---|
| End to end over real HTTP: headers, streaming, failover, cache privacy, budgets, … | [`GatewayIntegrationTest`](src/test/java/com/prism/gateway/gateway/GatewayIntegrationTest.java) |
| Retry, failover, circuit breaker, bulkhead | [`UpstreamInvokerTest`](src/test/java/com/prism/gateway/provider/UpstreamInvokerTest.java) |
| Router traps and held-out accuracy | [`DifficultyRouterTest`](src/test/java/com/prism/gateway/routing/DifficultyRouterTest.java) |
| Cache matching and isolation | [`CacheDecisionTest`](src/test/java/com/prism/gateway/cache/CacheDecisionTest.java) |
| Rate limiter under 500 concurrent attempts | [`SlidingWindowRateLimiterTest`](src/test/java/com/prism/gateway/limits/SlidingWindowRateLimiterTest.java) |
| Budget reservations | [`BudgetServiceTest`](src/test/java/com/prism/gateway/limits/BudgetServiceTest.java) |
| Cost math | [`CostCalculatorTest`](src/test/java/com/prism/gateway/usage/CostCalculatorTest.java) |
| In-process fake provider used by the tests | [`FakeProvider`](src/test/java/com/prism/gateway/provider/FakeProvider.java) |

Line numbers in the links point at the method as of this version; if the code moves, the file link
still opens the right file.

---

## Design decisions

### Check order and what counts against limits

The order is auth → validation → model → allowlist → rate limit → `auto` routing → budget. Each step
is cheaper or more fundamental than the next. An unauthenticated caller learns nothing about models
or limits. Budget comes after routing so its cost estimate uses the prices of the chain that will
actually serve the request.

Every request that passes the allowlist takes a rate-limit slot, including requests later rejected
for budget, cache hits and upstream failures. The rate limit protects the gateway as well as the
upstream spend. Rejections are logged but are not billed and not counted as served requests.

### Rate limiting: an exact sliding window, not a token bucket

`SlidingWindowRateLimiter` keeps the admission timestamps of the last 60 seconds per key. It checks
and records inside one per-key lock, so concurrent requests can never both take the last slot. That
is the read-then-write race the load test looks for.

I rejected a token bucket because it refills continuously. A 10 rpm bucket would admit an 11th
request within the same minute, which the load test counts as over-admission. Memory is O(rpm)
timestamps per key. State lives in memory, so this is a single-instance limiter (see limitations).

### Budgets and accounting

- **Atomic counters.** Spend lives in `usage_monthly`, one row per key per calendar month (UTC).
  Every increment is a single `UPDATE ... SET cost_usd = cost_usd + ?`, so updates are never lost.
  A new month has no row yet, so the budget resets when it's next read. The first insert of a
  month resolves races via the unique key plus a retry.
- **Exact decimals.** Cost uses decimal math at 12 places, from provider-reported `usage` and the
  price table. Headers print the exact value, so the sum of `x-prism-cost-usd` equals what the
  usage API reports. The load test confirms this.
- **One transaction.** The request log row and the usage increment are written together.
  `/admin/usage` sums the logs, and the budget reads the counter, so the two can't disagree.
- **Admission with reservations.** A request's cost is only known after it runs, so a plain
  "is spend below the budget?" check is a read-then-write race. Every request that arrives before
  the first is billed would be admitted. `BudgetService` closes it:
  - Under a per-key lock, a request is admitted only if **spend + the estimated cost of the key's
    in-flight requests < budget**.
  - The admitted request then reserves its own estimate: prompt tokens ≈ max(words, chars / 4),
    completion = `max_tokens` or 256, priced at the most expensive model in its chain.
  - The reservation is released when the real cost is recorded, on every path: served, cache hit,
    rejection, stream end.
- **Overshoot is bounded to about one request.** Sequential requests behave as before (admitted
  while spend is below budget), so the last admitted request can still overshoot by its own cost.
  That's what the budget-demo key shows: its first request costs more than the whole $0.00001
  budget. A concurrent burst can no longer multiply that. Ten simultaneous requests against a
  budget that covers one admit exactly one (`GatewayIntegrationTest`). Reservations are in memory,
  so this is exact per gateway instance; spend itself lives in the database.

### Routing, retries and failover

Aliases resolve to an ordered chain, e.g. `fast` → `alpha-small`, then `beta-small`. Each upstream
error is classified (`UpstreamException#disposition`):

| Upstream outcome | Action | Why |
|---|---|---|
| 500 / 502 / 504, malformed body, broken connection (reset, early EOF) | Retry the same provider with jittered exponential backoff (~200 ms, ~400 ms; 3 attempts) | Transient blips |
| Connection refused by a provider that succeeded within the last 2 s | Retry the same provider with backoff | It's up; its accept queue is momentarily full |
| 503, 429, connection refused (no recent success), timeout | Fail over to the next provider immediately | Retrying a dead, saturated or slow provider only adds latency |
| 401 / 403 / 404 from the provider | Fail over | This provider is misconfigured for the model |
| Other 4xx (e.g. 400) | Stop: return 400 to the caller | Another provider would reject it too |

- **Chain retry.** If every target in the chain failed only at the connection level (refused,
  reset, or bulkhead full), the chain is walked once more after a backoff. Under load, the mock's
  5-connection accept backlog can refuse both providers for a few milliseconds; that's saturation,
  not an outage. HTTP errors and timeouts never trigger a second pass.
- **Jitter.** Every backoff sleeps 50–150% of its nominal value. Requests refused in the same
  instant would otherwise all retry in the same instant and collide again.
- **Timeouts.** Every upstream call has a connect timeout and a response timeout. Streams also
  have an idle watchdog that closes a silent connection. A slow `alpha` (3 s injected latency)
  fails over to `beta` in about 2.6 s, and the client never hangs.
- **Circuit breakers (degradation-based routing).** Each provider has a breaker:
  - After **5 consecutive failed attempts** it opens, and that provider is skipped in every chain
    for **5 s**. Requests go straight to the fallback instead of each paying a timeout. While
    alpha is slow, requests drop from 2.5 s to about 11 ms once the breaker opens.
  - After the cooldown, **one** probe request is let through. Success closes the breaker, so
    traffic is back on the primary within about 5 s of recovery. Failure re-opens it.
  - A provider that answers with a 400 counts as alive.
  - If every breaker in a chain is open, the chain is tried anyway: a long shot beats a guaranteed 502.
  - Below the threshold, nothing changes. A single failure (as in the demo's down and slow drills)
    doesn't open the breaker, so recovery after it is immediate.
  - Breaker state is shown in `/admin/providers/health` and the console.
- **Bulkheads.** At most `max-in-flight-per-provider` (16) calls run against one provider at a
  time; streams hold their slot until they end. Extra callers wait up to `queue-timeout-ms` for a
  slot, then fail over. This is back-pressure, so it doesn't count against the provider's health or
  breaker. It keeps a burst from piling connections onto a saturated upstream: with the Python
  mock, every request in bursts at concurrency 30 and 50 is served (see VERIFICATION.md).
- **Streams fail over only before the first byte.** Headers aren't committed until an upstream has
  accepted the stream.
- **Mid-stream failure:** if the upstream breaks after streaming has started (connection lost,
  idle timeout, error chunk), the client gets
  `data: {"error": {"type": "upstream_error", "code": "stream_interrupted", ...}}` and the stream
  ends without `[DONE]`. Prism never restarts on another provider and splices outputs. The request
  is logged as `stream_interrupted` and nothing is billed. If the client disconnects mid-stream,
  the streamed portion is metered (estimated from text, flagged in the log) and the upstream
  connection is closed.

### Smart routing: the `auto` alias

`DifficultyRouter` uses engineered, explainable signals. No model call is involved, so it costs
microseconds and works offline. The key idea is that **prompt length measures how much material
was pasted, not how hard the task is.** So the router first isolates the task:

1. It removes quoted spans (single, double and curly quotes). Single quotes only count when they
   sit at word boundaries, so "don't" isn't mistaken for a quote. It also removes code blocks.
2. It splits the prompt into sentences and scores only **task sentences**: questions, and
   sentences that start with an imperative verb. Statements such as "Here is our roster: …" or
   raw log lines are material, and are ignored.
3. It scores the task text:
   - **Adds points for** formal proof or derivation, including "show that…" (+3).
   - **Adds +2 each for** explicit reasoning, trade-offs, quantitative reasoning (probability,
     expected value), security analysis, estimation, design, causal "why", symptom diagnosis
     ("what could be causing…") and algorithmic complexity.
   - **Adds +1.5 each for** planning, comparison, causal or failure analysis, and diagnosis.
   - **Adds +1 each for** recommendation, explanation, open-ended "how would you", edge cases
     (including "handling ties / nulls / duplicates…"), enumerated analysis and code generation.
   - Also **adds** expert domain vocabulary in any word form (+0.5 per distinct term, capped at +2)
     and multi-part demands ("…, and explain…", "including…", +1 each, capped at +2).
   - **Follow-up questions inherit their problem statement.** A short question that points back
     with a pronoun ("What could be causing **this**?", "Why does **it** happen?") is scored
     together with the sentence before it, unless that sentence is more than 40 words long (then
     it's pasted material, not a problem statement). "this Thursday" is a determiner, not a
     pronoun, so it doesn't trigger this.
   - **Subtracts for** lookup, transformation and extraction (−2), definition (−1.5), and
     short-output or yes/no questions (−1).
4. A score of 2 or more goes to `smart`, otherwise `fast`. Prompts over 8,000 characters are
   classified on their first and last 4,000.

Every matched signal is written to `route_reason` in the request log, e.g.
`complex (score 3.5 >= 2): formal proof/derivation (+3), domain terms [irrational] (+0.5)`.

**Results.** The signals were designed with the pack's set in view, so 100% there overstates
real-world accuracy. To measure generalization honestly, I used two held-out sets, each written and
labeled *before* the router saw it:

| Set | First router | Current router | Length-only baseline |
|---|---|---|---|
| `routing_eval.jsonl`, the 20 graded cases | 20/20 | **20/20** | 12/20 |
| `routing_holdout.jsonl`, 20 cases (used to diagnose v1's misses, so no longer clean) | 14/20 | 20/20 | 9/20 |
| `routing_holdout2.jsonl`, 24 cases, **fresh: never tuned on** | 18/24 | **23/24 (96%)** | 13/24 |

How the router was improved:

1. Its six misses on the first held-out set were analysed:
   - proofs phrased "show that"
   - vague follow-up questions whose difficulty is in the previous sentence
   - probability questions
   - threat models
   - SQL with edge cases
   - failure analysis
2. Each miss became a general rule (the signals above).
3. The fresh second set measured the result: **75% before, 96% after**.

No easy prompt is routed to `smart` in any of the three sets. The one remaining miss ("Write a
lock-free queue in C++ and explain the memory ordering") is left alone on purpose, because fixing it
would mean tuning on the measurement set. Misrouting a hard prompt to `fast` costs answer quality,
not money. For prompts with no strong signal either way, the natural next step is a small LLM judge,
or embedding nearest-neighbour against labeled examples.

### Semantic cache

- **Matching.** The documented local substitute for an embedding model is `LexicalEmbedder`.
  Prompts are lowercased, a small set of common synonyms is canonicalized ("2FA"/"MFA"/"two-factor",
  "sign in"/"log in", "money back"/"refund"…), and `n't` becomes `not` because negation changes
  meaning. Stopwords and question-framing words ("how", "what", "steps", "way"…) are dropped,
  suffixes are lightly stemmed, and the result is weighted by term frequency and L2-normalized.
  Similarity is the cosine; a hit needs similarity at or above the key's `similarity_threshold`.
- **Behavior on the pack's pairs:**

  | Pair | Similarity | Result |
  |---|---|---|
  | `req_cache_a1` / `a2` (paraphrase) | 1.00 | hit |
  | `a1` / `a3` (password reset vs 2FA reset) | 0.67 | miss |
  | `b1` / `b2` ("refund policy for annual plans" vs "can I get my money back") | 0.75 | miss |

  Pair B is the known limit of a lexical method: the two prompts share almost no vocabulary.
  Numbers and identifiers count as content, so "72°F" and "73°F" don't collide, and the smoke
  test's random salts keep earlier runs from hitting.
- **Why not a real embedding model?** I measured one before deciding
  (`scripts/compare_embeddings.py`, `all-MiniLM-L6-v2`). It **doesn't fix pair B**: 0.70, lower than
  the lexical 0.75. It **would introduce false hits**: "Convert 72°F" vs "Convert 73°F" scores 0.96,
  and the same question under different smoke-test salts 0.89. Its paraphrase-vs-near-miss margin
  (0.95 vs 0.76) is narrower than the lexical one (1.00 vs 0.67). At the seed thresholds (0.92 and
  0.85), the lexical matcher is the better choice for this cache. A dense model would need a much
  lower threshold *plus* an exact-entity guard for numbers and IDs. The `TextEmbedder` interface is
  the swap point.
- **Scope = key + tier + conversation context.** Entries never cross tenants, never cross tiers
  (a `fast` answer is never served for `smart`; for `auto`, the scope is the routed tier), and
  never cross conversations.
- **Multi-turn requests.** Only the **last user message** is compared semantically. Everything
  before it (system prompt, earlier turns) and the output-shaping parameters (`temperature`,
  `max_tokens`, `tools`, …) must match **exactly**, via a hash in the scope. A paraphrased
  follow-up in the same conversation can hit. The same words in a different conversation, where
  "it" means something else, can't.
- **The cache is bypassed when:**
  - the key has caching disabled
  - the client sends `Cache-Control: no-store`
  - `n > 1`
  - the last message isn't plain user text
  - the prompt has no content words
  - the prompt is **time-sensitive** ("current", "now", "today", "latest", "status of"…)

  `req_no_cache` is bypassed for that last reason. Only successful completions are stored:
  `finish_reason: stop` with non-empty content. Errors are never cached.
- **What a hit returns:** cost 0, `usage` zeroed (nothing was billed), a fresh `id`, and
  `x-prism-cache-similarity`. A streaming request that hits gets the cached answer replayed as SSE
  chunks ending in `[DONE]`. Streamed misses are assembled and cached too.
- **Storage and eviction.** Entries live in `cache_entries`, with an in-memory index rebuilt on
  startup, so lookups never touch the database. Entries expire after the TTL (24 h by default) and
  expired ones are purged at startup. Each scope keeps at most 500 entries, evicting the oldest.
  The embedder name is stored per entry, so switching embedders invalidates old vectors.

### Request logs, privacy and secrets

`request_logs` holds the fields from `docs/DATA_MODEL.md`, plus:

- `attempts`, the upstream trail, e.g. `alpha/alpha-small:503 -> beta/beta-small:ok`
- `upstream_latency_ms`, used for gateway-added latency
- `cache_similarity`, `error_type`, `error_message`

It deliberately **does not store prompt or response bodies**. The only copies are cache entries,
which expire with the TTL and can be cleared per key. Provider API keys appear only in the
outbound `Authorization` header: never in responses, logs or console views, and they are scrubbed
from relayed upstream error messages. Virtual keys are masked in the log API and console.

---

## Known limitations

- **Single instance.** Rate-limit windows, budget reservations, circuit breakers, bulkheads and
  the cache index are per process. Several gateway instances would need a shared store such as
  Redis (a Stretch goal). Spend and usage already live in the database.
- **Budget overshoot of about one request.** The last request admitted below the budget can
  overshoot by its own cost. Concurrent overshoot is prevented by reservations.
- **Lexical cache.** Paraphrases with almost no shared vocabulary miss (pair B). A small dense
  embedding model was measured and does worse at the seed thresholds (see above).
- **Heuristic router.** 96% on a fresh held-out set, but hard prompts with no recognizable task
  signal still go to `fast`.
- **One thread per stream.** The Tomcat platform-thread model is fine at demo scale; virtual
  threads (JDK 21+) would remove this ceiling.
- **Cache-hit usage.** Hits report zero `usage` tokens. Clients that count tokens from response
  bodies see what was billed, not what the prompt would have cost.
