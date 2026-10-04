# Short shell helpers for recording the Prism demo (bash / Git Bash). Each one is a thin curl wrapper,
# so what you see on screen is exactly what a client would send.
#
#   source scripts/demo_helpers.sh
#
#   chat <key> <model> "<prompt>"   non-streaming request; prints status, x-prism-* headers, answer
#   stream <key> "<prompt>"         streaming request with the 'fast' alias; tokens print as they arrive
#   probe <key> "<prompt>"          one line: serving provider, fallback flag, and request time
#   alpha down|slow|flaky|ok        failure injection on mock provider alpha
#   lastlog <key>                   the newest request-log entry for a key
#   usage <key>                     month-to-date requests, tokens and cost from the usage API
#   circuit                         circuit-breaker state per provider
#
# Keys: search, research, free, budget (the seeded virtual keys).

PRISM=${PRISM:-http://localhost:8080}
ADMIN_TOKEN=${ADMIN_TOKEN:-prism-admin-dev}

_key() {
  case "$1" in
    search) echo prism-sk-search-1a2b3c ;;
    research) echo prism-sk-research-4d5e6f ;;
    free) echo prism-sk-free-7g8h9i ;;
    budget) echo prism-sk-budget-demo-0j1k2l ;;
    *) echo "$1" ;;
  esac
}

_body() { # model prompt [stream]
  python -c 'import json,sys; b={"model": sys.argv[1], "messages": [{"role": "user", "content": sys.argv[2]}]}
if len(sys.argv) > 3: b["stream"] = True
print(json.dumps(b))' "$@"
}

chat() {
  curl -s -i "$PRISM/v1/chat/completions" -H "Authorization: Bearer $(_key "$1")" \
    -H "Content-Type: application/json" -d "$(_body "$2" "$3")" \
  | tr -d '\r' | python -c '
import json, sys
head, _, body = sys.stdin.read().partition("\n\n")
lines = head.splitlines()
print(lines[0])
for l in lines[1:]:
    if l.lower().startswith(("x-prism", "retry-after")):
        print("  " + l)
try:
    j = json.loads(body)
    if "error" in j:
        print("  error:", j["error"]["type"], "-", j["error"]["message"])
    else:
        print("  answer:", j["choices"][0]["message"]["content"][:110])
        print("  usage: ", j["usage"])
except ValueError:
    print(body[:200])'
}

stream() {
  curl -s -N "$PRISM/v1/chat/completions" -H "Authorization: Bearer $(_key "$1")" \
    -H "Content-Type: application/json" -d "$(_body fast "$2" stream)" \
  | python -u -c '
import json, sys
for line in sys.stdin:
    line = line.strip()
    if not line.startswith("data: "):
        continue
    data = line[6:]
    if data == "[DONE]":
        print("\n[DONE]")
        break
    chunk = json.loads(data)
    if chunk.get("usage"):
        print("\n  usage:", chunk["usage"], end="")
    for choice in chunk.get("choices", []):
        print(choice.get("delta", {}).get("content", ""), end="", flush=True)'
}

# One-line timing probe: which provider served it and how long the gateway took (curl only, no python
# start-up in the measurement). Keep the prompt free of double quotes.
probe() {
  curl -s -o /dev/null -D - -w "time=%{time_total}s\n" "$PRISM/v1/chat/completions" \
    -H "Authorization: Bearer $(_key "$1")" -H "Content-Type: application/json" -H "Cache-Control: no-store" \
    -d "{\"model\": \"fast\", \"messages\": [{\"role\": \"user\", \"content\": \"$2\"}]}" \
  | tr -d '\r' | grep -iE "^x-prism-provider|^x-prism-fallback|^time=" | tr '\n' ' '
  echo
}

alpha() {
  case "$1" in
    down)  cfg='{"mode": "down"}' ;;
    slow)  cfg='{"mode": "ok", "latency_ms": 3000}' ;;
    flaky) cfg='{"mode": "ok", "fail_rate": 0.3}' ;;
    ok)    cfg='{"mode": "ok", "fail_rate": 0, "latency_ms": 0}' ;;
    *) echo "usage: alpha down|slow|flaky|ok"; return 1 ;;
  esac
  curl -s -X POST http://localhost:9001/admin/config -d "$cfg"; echo
}

lastlog() {
  curl -s -H "X-Admin-Token: $ADMIN_TOKEN" "$PRISM/admin/logs?limit=1&key=$(_key "$1")" | python -c '
import json, sys
r = json.load(sys.stdin)[0]
for k in ("status", "requested_model", "resolved_provider", "resolved_model", "cache", "fallback", "attempts",
          "route_tier", "route_reason", "prompt_tokens", "completion_tokens", "cost_usd_exact", "latency_ms"):
    if r.get(k) not in (None, ""):
        print(f"  {k:18} {r[k]}")'
}

# Month-to-date totals for a key from the usage API, on one line.
usage() {
  curl -s -H "X-Admin-Token: $ADMIN_TOKEN" "$PRISM/admin/usage?key=$(_key "$1")" | python -c '
import json, sys
u = json.load(sys.stdin)
print("  requests=%s  prompt_tokens=%s  completion_tokens=%s  cost_usd=%s" % (
    u["requests"], u["prompt_tokens"], u["completion_tokens"], u["cost_usd_exact"]))'
}

circuit() {
  curl -s -H "X-Admin-Token: $ADMIN_TOKEN" "$PRISM/admin/providers/health" \
  | python -c 'import json,sys; d=json.load(sys.stdin); print("  " + "   ".join(k + ": " + v["circuit"] for k, v in d.items()))'
}

echo "Prism demo helpers loaded: chat, stream, probe, alpha, lastlog, usage, circuit  (gateway: $PRISM)"
