# Prism: explainer video script

**Target length:** about 11 minutes. Each section lists what to **show**, the commands to **type**,
what to **say**, and what to **point at** in the output.

The order follows the problem statement's *Recommended Demo Flow*. It covers everything the
deliverables name explicitly: streaming, smart routing, budget rejection, rate limiting, a semantic
cache hit on a paraphrased prompt, and live provider failover.

> Narration is written in first person for you to read or adapt. Don't read it word for word;
> glance at it, then say it your own way.

---

## Before you record (5 minutes)

**1. Start from a fresh database.** This makes the cache start empty and gives the budget-demo key
its budget back.

```bash
# stop any running gateway first (Ctrl+C in its terminal), then:
cd /d/prism-gateway
rm -rf var/prismdb*
```

**2. Open four terminals.** Use Git Bash, not PowerShell: PowerShell's `curl` is a different
command. Set a large font (16–18 pt) and a dark theme.

In **every** terminal, first go to the project folder. A new Git Bash window starts in your home
folder, and every command below expects to be run from the project root:

```bash
cd /d/prism-gateway
```

| Terminal | Run | Visible on screen? |
|---|---|---|
| **T1** | `python scripts/mock_provider.py --port 9001 --name alpha` | Small, but visible: its request log scrolls during failover |
| **T2** | `python scripts/mock_provider.py --port 9002 --name beta` | Small, but visible |
| **T3** | `./mvnw spring-boot:run` (or `java -jar target/prism-gateway-0.0.1-SNAPSHOT.jar`) | Can be minimised after startup |
| **T4** | Your main demo terminal. Run `source scripts/demo_helpers.sh` | Large: this is where the video happens |

**3. Open the browser** at <http://localhost:8080/console/>, enter the admin token
`prism-admin-dev`, and leave the tab ready.

**4. Do a dry run of section 1** to check everything is up. Then do the fresh-database reset
(step 1) again.

**Recording tips:**

- Record at 1080p.
- Zoom the browser to 125% so the tables are readable.
- Pause between sections and cut in editing. You don't need one perfect take.
- If a command misbehaves, see [If something goes wrong](#if-something-goes-wrong).

---

## 0. Intro (0:00 – 0:45)

**Code to show:** [`ChatGatewayService.handle`](src/main/java/com/prism/gateway/gateway/ChatGatewayService.java#L98): the pipeline, one step per line. Scroll through it while describing the steps.

**Show:** the README on GitHub, scrolled to the architecture diagram.

**Say:**
> "This is Prism, an LLM gateway. It sits between applications and model providers. Every team
> calls one OpenAI-compatible endpoint with a virtual key. The gateway authenticates it, enforces
> rate limits and monthly budgets, picks a model, answers repeated questions from a semantic cache,
> fails over when a provider goes down, and meters every token and every dollar.
>
> It's built with Spring Boot and an embedded H2 database. Behind it there are two mock providers,
> alpha and beta, that speak the OpenAI wire format and let me inject failures live.
>
> Every request goes through this pipeline: authenticate, validate, resolve the model, check the
> allowlist, rate limit, route, reserve budget, check the cache, call upstream with retries and
> failover, then meter and log. Let me show each part working."

---

## 1. Everything is running (0:45 – 1:15)

**Show:** T4, with T1 and T2 visible in the corner.

**Type:**
```bash
curl -s localhost:9001/health; echo; curl -s localhost:9002/health; echo; curl -s localhost:8080/health
```

**Point at:** `"status":"ok"`, `"virtual_keys":4`, `"providers":["alpha","beta"]`.

**Say:**
> "Two mock providers and the gateway. The four tenants were seeded from the capstone's
> seed_keys.json: a search team, a research team, a free tier with a ten-requests-per-minute limit,
> and a budget-demo key with a tiny budget."

---

## 2. A normal request and the header contract (1:15 – 2:00)

**Code to show:** [`ModelCatalog.targetsFor`](src/main/java/com/prism/gateway/catalog/ModelCatalog.java#L46) (alias → chain) and [`CostCalculator.cost`](src/main/java/com/prism/gateway/usage/CostCalculator.java#L28) (tokens × price).

**Type:**
```bash
chat search fast "What is a message queue and when should I use one?"
```

**Point at, top to bottom:**
- `x-prism-provider: alpha/alpha-small`: the `fast` alias resolved to alpha's small model
- `x-prism-cache: miss`
- `x-prism-fallback: false`
- `x-prism-cost-usd: 0.0000…`: computed from the provider's reported token usage and the price table
- the `usage` line

**Say:**
> "`chat` is just a curl wrapper. The caller asked for the alias `fast` and never sees provider
> names or credentials. Every response carries the x-prism headers: which provider served it,
> whether it came from the cache, whether it was a fallback, and what it cost. The cost comes from
> the provider's own usage numbers times the price table, in exact decimals. That's why the totals
> reconcile with the usage API later."

---

## 3. Streaming, token by token (2:00 – 2:40)

**Code to show:** [`ChatGatewayService.relay`](src/main/java/com/prism/gateway/gateway/ChatGatewayService.java#L262): the loop that forwards each chunk, and the mid-stream error branch at its end.

**Type:**
```bash
stream search "Explain how server-sent events work."
```

**Point at:** words appear progressively, not all at once, and the stream ends with `[DONE]`.

**Say:**
> "Same endpoint with stream set to true. The gateway forwards each chunk the moment it arrives
> from the provider. Nothing is buffered, and you can see the words come in one at a time. The
> final chunk carries the usage, so streamed requests are metered too.
>
> One design decision here: failover can only happen *before* the first byte. If a provider breaks
> mid-stream, the client gets an error event, never half of one provider's answer spliced onto
> another's."

---

## 4. Semantic cache: a paraphrase hits (2:40 – 4:00)

**Code to show:** [`LexicalEmbedder.tokens`](src/main/java/com/prism/gateway/cache/LexicalEmbedder.java#L77) (question → key words) and [`CachePolicy.decide`](src/main/java/com/prism/gateway/cache/CachePolicy.java#L56) (per-team scope, time-sensitive bypass).

**Type, one at a time:**
```bash
chat search fast "How do I reset my password on the dashboard?"
chat search fast "What are the steps to reset my dashboard password?"
chat search fast "How do I reset my two-factor authentication on the dashboard?"
chat search fast "What is the current status of the payments service?"
```

**Point at:**
1. First request: `x-prism-cache: miss`, with a cost.
2. **The paraphrase:** `x-prism-cache: hit`, `x-prism-cost-usd: 0`, `x-prism-cache-similarity: 1.0`,
   and `x-prism-provider: cache:alpha/alpha-small`. **This is the key moment; pause on it.**
3. Two-factor reset: `miss`. It shares words but asks something different.
4. Payments status: `miss` plus `x-prism-cache-bypass: time-sensitive prompt`.

**Optionally, show why:**
```bash
curl -s -X POST localhost:8080/admin/cache/similarity -H "X-Admin-Token: prism-admin-dev" -H "Content-Type: application/json" -d '{"a":"How do I reset my password on the dashboard?","b":"How do I reset my two-factor authentication on the dashboard?"}'
```
**Point at:** `similarity: 0.6667`, below the search team's 0.92 threshold.

**Say:**
> "The second question is worded completely differently, but it's the same question, so it's
> served from the cache at zero cost. The third one shares most of its words, but it's about
> two-factor authentication, not the password. It scores 0.67 and correctly misses.
>
> The cache is scoped per key, so one team can never receive another team's cached answer. It
> also never serves time-sensitive questions like 'current status', because a cached answer
> would be stale.
>
> For matching I use a normalized bag of content words. I measured a real embedding model,
> MiniLM, before choosing. It would have treated 'convert 72 degrees' and 'convert 73 degrees'
> as the same question, so for this cache the lexical approach is safer. The comparison is in the
> verification report."

---

## 5. Smart routing with `auto` (4:00 – 5:30)

**Code to show:** [`DifficultyRouter.SIGNALS`](src/main/java/com/prism/gateway/routing/DifficultyRouter.java#L48) (the weighted signals) and [`DifficultyRouter.classify`](src/main/java/com/prism/gateway/routing/DifficultyRouter.java#L144) (task sentences → score → tier).

**Type:**
```bash
chat research auto "Prove that the square root of 2 is irrational."
lastlog research
```
**Point at:** `x-prism-route: smart`, provider `alpha/alpha-large`, and in the log,
`route_reason: complex (score 3.5 >= 2): formal proof/derivation (+3), ...`

```bash
chat research auto "Here is our on-call roster: Monday - Priya, Tuesday - Chen, Wednesday - Amara, Thursday - Diego, Friday - Fatima, Saturday - Lukas, Sunday - Mei. Who is on call this Thursday?"
lastlog research
```
**Point at:** `x-prism-route: fast`, `alpha/alpha-small`, `route_reason: simple ... fact lookup (-2)`.

```bash
python scripts/routing_eval.py | tail -26
```
**Point at:** each case's expected vs actual, then `accuracy: 20/20 = 100.0%` against
`baseline (length-only): 12/20 = 60.0%`.

**Say:**
> "With model auto, the gateway judges each prompt's difficulty. The proof is a one-liner, but
> it's hard, so it goes to the smart tier. The roster question is long but trivial, so it goes to
> fast. A length-based router would get both wrong.
>
> The trick is that prompt length measures how much *material* someone pasted, not how hard the
> *task* is. So the router strips quoted text and pasted data, scores only the actual question or
> instruction, and logs every signal it matched, so every decision is explainable.
>
> On the 20 graded cases it gets 20 out of 20, versus 60% for length-only. Because I designed the
> rules looking at that set, I also tested on 24 prompts it had never seen. It gets 96% there,
> and it never sends an easy prompt to the expensive model."

---

## 6. Rate limiting (5:30 – 6:10)

**Code to show:** [`SlidingWindowRateLimiter.tryAcquire`](src/main/java/com/prism/gateway/limits/SlidingWindowRateLimiter.java#L30): about 15 lines; the `synchronized` block is the race-safety.

**Type:**
```bash
for i in $(seq 1 13); do echo -n "request $i: "; chat free fast "burst test $i" | head -1; done
chat free fast "one more"
```

**Point at:** ten `HTTP/1.1 200`s, then `429`s. The last command shows `rate_limit_exceeded` and
a `Retry-After` header.

**Say:**
> "The free tier allows ten requests per minute. The eleventh is rejected with a clear
> rate_limit_exceeded error and a Retry-After header. The limiter is an exact sliding window with
> the check and the update under one lock. A token bucket would refill and let an eleventh request
> through inside the same minute, and the load test checks for exactly that."

---

## 7. Budgets (6:10 – 7:00)

**Code to show:** [`BudgetService.admit`](src/main/java/com/prism/gateway/limits/BudgetService.java#L51): spend + in-flight reservations < budget.

**Type:**
```bash
chat budget fast "What is a message queue?"
chat budget fast "What is a message queue?"
```

**Point at:** the first is a `200` with a cost. The second is **`402 budget_exceeded`**: "Monthly
budget of $0.00001 exhausted for this key".

**Say:**
> "This key has a deliberately tiny monthly budget, and its cache is off so a free cache hit can't
> hide the rejection. The first request uses up the budget; the second is rejected with
> budget_exceeded.
>
> The subtle part is concurrency. A request's cost is only known after it runs, so a naive check
> would let a whole burst through before the first one is billed. Prism reserves each in-flight
> request's estimated cost. Ten simultaneous requests against a budget that covers one admit
> exactly one, and there's a test for that."

---

## 8. Live provider failover (7:00 – 9:00)

**Code to show:** [`UpstreamException.disposition`](src/main/java/com/prism/gateway/provider/UpstreamException.java#L57) (retry vs switch), [`UpstreamInvoker.invoke`](src/main/java/com/prism/gateway/provider/UpstreamInvoker.java#L56) (the chain loop) and [`CircuitBreakers.allow`](src/main/java/com/prism/gateway/provider/CircuitBreakers.java#L37).

**Keep T1 (alpha's log) visible.** This is the second must-show moment.

**8a. Provider down:**
```bash
alpha down
chat search fast "What is a circuit breaker in distributed systems?"
lastlog search
```
**Point at:** `x-prism-provider: beta/beta-small`, `x-prism-fallback: true`, and in the log,
`attempts: alpha/alpha-small:503 -> beta/beta-small:ok`.

**Say:**
> "I've just taken alpha down. The same request is served by beta, the next model in the fast
> alias's fallback chain. The client still gets a 200. The fallback header is true, and the log
> records exactly what happened."

**8b. Provider slow, and the circuit breaker:**
```bash
alpha slow
for i in $(seq 1 7); do echo -n "request $i: "; probe search "slow test $i about backpressure"; done
circuit
```
**Point at:** requests 1–4 take `time=2.5s` (timeout, then beta). From request 5 on, about
`time=0.03s`. `circuit` shows `alpha: open`.

You'll see four slow requests, not five: the breaker also counted the failure from 8a, when alpha
was down.

**Say:**
> "Now alpha is alive but slow: three seconds per response. Every upstream call has a timeout, so
> the client never hangs. After 2.5 seconds the gateway gives up on alpha and beta answers.
>
> After five failures in a row (including the one from a moment ago, when alpha was down), the
> circuit breaker opens. Alpha is skipped entirely and requests drop from two and a half seconds
> to about thirty milliseconds. Provider health isn't just a dashboard number; it drives routing."

**8c. Recovery:**
```bash
alpha ok
sleep 5
chat search fast "Is alpha back? What is a bulkhead pattern?"
circuit
```
**Point at:** `x-prism-provider: alpha/alpha-small`, `x-prism-fallback: false`, `alpha: closed`.

**Say:**
> "After a five-second cooldown, one probe request is allowed through to alpha. It succeeds, the
> circuit closes, and traffic is back on the primary."

---

## 9. Ops console (9:00 – 10:00)

**Code to show:** [`AdminController`](src/main/java/com/prism/gateway/admin/AdminController.java): the endpoints the console reads.

**Show:** the browser tab. Press **Refresh**.

**Point at, top to bottom:**
1. **Tiles:** total requests, spend, cache hit rate, fallbacks, gateway-added latency.
2. **Usage by key:** the budget-demo row's **red, full budget bar**; the free tier's rejected count.
3. **Semantic cache:** the search team's hits and hit rate.
4. **Provider health:** alpha's errors from the failover drill, and the circuit column.
5. **Recent requests:** filter the status to `rejected_rate_limit`, then back to all. Click an
   `auto` row to expand it and show the **routing reason**; click a fallback row to show the
   **attempt trail**.
6. Scroll down and press **Run** on the routing eval.

**Say:**
> "This is the ops console for the platform team. Usage and spend per key, the budget-demo key
> shown as exhausted, cache hit rates, provider health with the circuit state, and every request,
> including every rejection, with its routing reason and full attempt trail. Prompts and
> responses themselves aren't stored in the log; that's a deliberate privacy decision."

---

## 10. Verification scripts (10:00 – 11:00)

> Run this at least 60 seconds after section 6, so the free-tier window has cleared.

**Type:**
```bash
python scripts/smoke_test.py --url http://localhost:8080 --key prism-sk-search-1a2b3c --model fast | tail -14
usage free
python scripts/load_test.py --url http://localhost:8080 --key prism-sk-free-7g8h9i --model fast --requests 30 --concurrency 10 --rpm-limit 10
usage free
```

**Point at:**
- Smoke test: `17 passed, 1 warnings, 0 failed`.
- Load test: `accepted (200): 10`, `Over-admission check OK`.
- **The two `usage` lines.** The difference between them equals the load test's client-side
  totals exactly.

  From the dry run, for example: before `requests=10 prompt_tokens=30 completion_tokens=178`, after
  `requests=20 prompt_tokens=150 completion_tokens=436`. The difference is 10 requests, 120 prompt
  tokens and 258 completion tokens, matching the load test's 10 / 120 / 258.

**Say:**
> "The provided smoke test passes. The one warning is the second paraphrase pair, which shares
> almost no words; the test only requires one pair to hit, and the verification report explains
> it.
>
> Now the load test: thirty concurrent requests at a ten-per-minute key. Exactly ten are accepted,
> with zero over-admission. I took the key's usage just before and just after. The difference is
> ten requests, a hundred and twenty prompt tokens and two hundred and fifty-eight completion
> tokens, exactly what the load test counted on the client side. Accounting stays exact under
> concurrency because every increment is an atomic database update in the same transaction as the
> log row."

*(Say the numbers you actually see; they vary slightly between runs because the mock's replies
differ in length.)*

---

## 11. Close (11:00 – 11:30)

**Show:** VERIFICATION.md, the summary table at the top.

**Say:**
> "Everything I showed is reproducible. There are 62 automated tests, including the rate limiter
> under 500 concurrent attempts, budget reservations, circuit breakers, mid-stream failures and
> cache isolation. The verification report has the raw outputs and the honest limitations: the
> router can still miss a hard prompt that has no recognizable signal, and the rate limiter and
> breakers are per instance, so scaling out would need Redis. That's Prism. Thanks for watching."

---

## If something goes wrong

| Symptom | Fix |
|---|---|
| Section 4's first request is already a `hit` | The cache has data from an earlier run. `curl -s -X DELETE -H "X-Admin-Token: prism-admin-dev" "localhost:8080/admin/cache?key=prism-sk-search-1a2b3c"` |
| Section 7's first request is already `402` | The budget-demo key spent its monthly budget in an earlier run. Stop the gateway, `rm -rf var/prismdb*`, restart. |
| Section 6 shows `429` immediately, or section 10's load test accepts fewer than 10 | The free-tier minute window is still full from a previous burst. Wait 60 seconds. |
| `401 Invalid virtual key` right after startup | The gateway is still loading keys; `/health` shows `"status":"starting"`. Wait a second. |
| `curl: command not found`, or curl output looks like PowerShell objects | You're in PowerShell. Switch to Git Bash. |
| Section 8b never opens the circuit | Alpha isn't slow. Re-run `alpha slow` and check T1's output. |
| Section 8b shows five slow requests instead of four | You skipped 8a or re-ran it. Either is fine; the breaker opens after five consecutive failures in total. |
| Anything left broken after the failover drill | `alpha ok` restores alpha to healthy. |
