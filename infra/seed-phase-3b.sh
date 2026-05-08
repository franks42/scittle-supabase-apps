#!/usr/bin/env bash
# Phase 3b seed: insert the seven namespaces that make up the editor demo.
# Re-runnable: deletes any prior rows for these names then inserts fresh
# v1 rows.

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

NAMES = ["app.utils", "app.supa", "app.wm", "app.cm6",
         "app.auth", "app.editor", "app.main"]
SEEDS = "supabase/seeds/phase-3b"

print("==> Removing prior versions of:", ", ".join(NAMES))
in_clause = "(" + ",".join(NAMES) + ")"
request("DELETE", f"/rest/v1/ns_modules?name=in.{in_clause}")

ROWS = [
    {"name": "app.utils",  "version": 1, "deps": [],
     "description": "Pure helpers — shout, add, now-string."},
    {"name": "app.supa",   "version": 1, "deps": [],
     "description": "Singleton supabase-js client cached on window."},
    {"name": "app.wm",     "version": 1, "deps": ["reagent.dom"],
     "description": "Plain-atom WinBox window manager."},
    {"name": "app.cm6",    "version": 1, "deps": ["reagent.core"],
     "description": "Reagent wrapper around CodeMirror 6."},
    {"name": "app.auth",   "version": 1, "deps": ["reagent.core", "app.supa"],
     "description": "Auth helper — anonymous sign-in for RLS; !user is an r/atom."},
    {"name": "app.editor", "version": 1,
     "deps": ["reagent.core", "app.supa", "app.auth", "app.wm", "app.cm6"],
     "description": "Windowed editor wired to ns_modules — load, save, re-eval."},
    {"name": "app.main",   "version": 1,
     "deps": ["reagent.core", "reagent.dom", "app.utils", "app.supa", "app.auth", "app.editor"],
     "description": "Phase 3b demo — list namespaces, click to edit and save."},
]

for row in ROWS:
    row["source"] = read(f"{SEEDS}/{row['name']}.cljs")
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
