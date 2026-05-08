#!/usr/bin/env bash
# Phase 3a seed: insert (or replace) app.utils, app.wm, app.cm6, app.main
# into ns_modules. Re-runnable: deletes any prior rows for these names
# (DELETE bypasses RLS via service role) then inserts fresh v1 rows.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

# shellcheck source=/dev/null
set -a; . ./.env; . ./.env.local; set +a

: "${SUPABASE_URL:?SUPABASE_URL not set}"
: "${SUPABASE_SECRET_KEY:?SUPABASE_SECRET_KEY not set}"

python3 - <<PY
import json, os, urllib.request, urllib.error

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

NAMES = ["app.utils", "app.wm", "app.cm6", "app.main"]
SEEDS = "supabase/seeds/phase-3a"

print("==> Removing prior versions of:", ", ".join(NAMES))
in_clause = "(" + ",".join(NAMES) + ")"
request("DELETE", f"/rest/v1/ns_modules?name=in.{in_clause}")

ROWS = [
    {
        "name": "app.utils",
        "version": 1,
        "source": read(f"{SEEDS}/app.utils.cljs"),
        "deps": [],
        "description": "Demo utility namespace — pure functions and timestamp helper."
    },
    {
        "name": "app.wm",
        "version": 1,
        "source": read(f"{SEEDS}/app.wm.cljs"),
        "deps": ["reagent.dom"],
        "description": "Window manager built on WinBox — open/close floating windows."
    },
    {
        "name": "app.cm6",
        "version": 1,
        "source": read(f"{SEEDS}/app.cm6.cljs"),
        "deps": ["reagent.core"],
        "description": "Reagent wrapper around CodeMirror 6 (clojure-mode language)."
    },
    {
        "name": "app.main",
        "version": 1,
        "source": read(f"{SEEDS}/app.main.cljs"),
        "deps": ["reagent.core", "reagent.dom", "app.utils", "app.wm", "app.cm6"],
        "description": "Phase 3a demo: opens a CM6 editor in a WinBox window."
    },
]

for row in ROWS:
    print(f"==> Inserting {row['name']} v1")
    request("POST", "/rest/v1/ns_modules", row)

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
