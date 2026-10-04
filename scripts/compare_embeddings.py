#!/usr/bin/env python3
"""Compares Prism's cache matcher against a real sentence-embedding model on the pack's cache pairs.

Optional: needs `pip install sentence-transformers` (downloads all-MiniLM-L6-v2 on first use) and a
running gateway for the lexical scores (POST /admin/cache/similarity). Used to justify the choice of
matcher in VERIFICATION.md; nothing in the gateway depends on it.

    python3 scripts/compare_embeddings.py [--url http://localhost:8080] [--admin-token prism-admin-dev]
"""

import argparse
import json
import urllib.request

PAIRS = [
    ("A paraphrase", "hit", "How do I reset my password on the dashboard?",
     "What are the steps to reset my dashboard password?"),
    ("A3 near-miss", "miss", "How do I reset my password on the dashboard?",
     "How do I reset my two-factor authentication on the dashboard?"),
    ("B paraphrase", "hit", "What is the refund policy for annual plans?",
     "If I bought an annual plan, can I get my money back?"),
    ("salted repeat", "miss", "(1a2b3c4d) What is a load balancer?", "(9f8e7d6c) What is a load balancer?"),
    ("unrelated", "miss", "(1a2b3c4d) What is a load balancer?", "(1a2b3c4d) Compare TCP and UDP for game servers."),
    ("72F vs 73F", "miss", "Convert 72 degrees Fahrenheit to Celsius.", "Convert 73 degrees Fahrenheit to Celsius."),
]


def lexical(url, token, a, b):
    req = urllib.request.Request(url.rstrip("/") + "/admin/cache/similarity",
                                 data=json.dumps({"a": a, "b": b}).encode(), method="POST",
                                 headers={"Content-Type": "application/json", "X-Admin-Token": token})
    with urllib.request.urlopen(req, timeout=10) as resp:
        return json.loads(resp.read())["similarity"]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://localhost:8080")
    parser.add_argument("--admin-token", default="prism-admin-dev")
    parser.add_argument("--model", default="sentence-transformers/all-MiniLM-L6-v2")
    args = parser.parse_args()

    from sentence_transformers import SentenceTransformer  # optional dependency
    model = SentenceTransformer(args.model)

    print(f"{'pair':<16} {'want':<5} {'lexical':>8} {'embedding':>10}   (thresholds: search 0.92, free-tier 0.85)")
    for name, want, a, b in PAIRS:
        e = model.encode([a, b], normalize_embeddings=True)
        dense = float(e[0] @ e[1])
        lex = lexical(args.url, args.admin_token, a, b)
        print(f"{name:<16} {want:<5} {lex:>8.3f} {dense:>10.3f}")


if __name__ == "__main__":
    main()
