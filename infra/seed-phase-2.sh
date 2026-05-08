#!/usr/bin/env bash
# Phase 2 seed: insert demo namespaces (app.utils, app.main) into ns_modules.
# Uses the secret key to bypass RLS. Re-runnable: deletes both rows first
# (DELETE bypasses RLS via service role), then inserts.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

# shellcheck source=/dev/null
set -a; . ./.env; . ./.env.local; set +a

: "${SUPABASE_URL:?SUPABASE_URL not set}"
: "${SUPABASE_SECRET_KEY:?SUPABASE_SECRET_KEY not set}"

# Use Python to JSON-escape the source files, since bash heredocs and
# JSON quoting don't mix well with parens, brackets, and quotes.
python3 - <<PY
import json, os, sys, urllib.request

base = os.environ["SUPABASE_URL"]
key  = os.environ["SUPABASE_SECRET_KEY"]

def request(method, path, body=None):
    url = base + path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method, headers={
        "apikey": key,
        "Authorization": "Bearer " + key,
        "Content-Type": "application/json",
        "Prefer": "return=minimal",
    })
    try:
        with urllib.request.urlopen(req) as resp:
            print(f"  {method} {path} -> HTTP {resp.status}")
    except urllib.error.HTTPError as e:
        print(f"  {method} {path} -> HTTP {e.code}: {e.read().decode()}")
        raise

def read(path):
    with open(path) as f: return f.read()

print("==> Removing any prior versions of app.utils and app.main")
request("DELETE", "/rest/v1/ns_modules?name=in.(app.utils,app.main)")

print("==> Inserting app.utils v1")
request("POST", "/rest/v1/ns_modules", {
    "name":    "app.utils",
    "version": 1,
    "source":  read("supabase/seeds/phase-2/app.utils.cljs"),
    "deps":    [],
    "description": "Phase 2 demo: small utility namespace, no plugin requires."
})

print("==> Inserting app.main v1")
request("POST", "/rest/v1/ns_modules", {
    "name":    "app.main",
    "version": 1,
    "source":  read("supabase/seeds/phase-2/app.main.cljs"),
    "deps":    ["reagent.core", "reagent.dom", "app.utils"],
    "description": "Phase 2 demo: Reagent root, depends on app.utils."
})

print("==> Verifying ns_closure(app.main)")
req = urllib.request.Request(
    base + "/rest/v1/rpc/ns_closure",
    data=json.dumps({"root": "app.main"}).encode(),
    method="POST",
    headers={
        "apikey": key,
        "Authorization": "Bearer " + key,
        "Content-Type": "application/json",
    },
)
with urllib.request.urlopen(req) as resp:
    rows = json.loads(resp.read())
print(f"  closure has {len(rows)} rows: {sorted(r['name'] for r in rows)}")
PY

echo "==> Done."
