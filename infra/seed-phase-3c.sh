#!/usr/bin/env bash
# Phase 3c seed: insert the eight namespaces (phase-3b set + app.launcher)
# into ns_modules, and a single 'demo' row into the apps table so the
# launcher has something to dispatch to. Re-runnable.

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
         "app.auth", "app.editor", "app.main", "app.launcher"]
SEEDS = "supabase/seeds/phase-3c"

print("==> Removing prior versions of:", ", ".join(NAMES))
in_clause = "(" + ",".join(NAMES) + ")"
request("DELETE", f"/rest/v1/ns_modules?name=in.{in_clause}")

NS_ROWS = [
    {"name": "app.utils",    "version": 1, "deps": [],
     "description": "Pure helpers — shout, add, now-string."},
    {"name": "app.supa",     "version": 1, "deps": [],
     "description": "Singleton supabase-js client cached on window."},
    {"name": "app.wm",       "version": 1, "deps": ["reagent.dom"],
     "description": "Plain-atom WinBox window manager."},
    {"name": "app.cm6",      "version": 1, "deps": ["reagent.core"],
     "description": "Reagent wrapper around CodeMirror 6."},
    {"name": "app.auth",     "version": 1, "deps": ["reagent.core", "app.supa"],
     "description": "Auth helper — anonymous sign-in for RLS; !user is an r/atom."},
    {"name": "app.editor",   "version": 1,
     "deps": ["reagent.core", "app.supa", "app.auth", "app.wm", "app.cm6"],
     "description": "Windowed editor wired to ns_modules — load, save, re-eval."},
    {"name": "app.main",     "version": 1,
     "deps": ["reagent.core", "reagent.dom", "app.utils", "app.supa", "app.auth", "app.editor"],
     "description": "Phase 3b demo — list namespaces, click to edit and save."},
    {"name": "app.launcher", "version": 1,
     "deps": ["reagent.core", "reagent.dom", "app.supa", "app.auth", "app.wm"],
     "description": "Lists rows from the apps table and dispatches via window.__dispatch__."},
]

for row in NS_ROWS:
    row["source"] = read(f"{SEEDS}/{row['name']}.cljs")
    print(f"==> Inserting {row['name']} v1")
    request("POST", "/rest/v1/ns_modules", row)

print("==> Upserting apps rows")
APP_ROWS = [
    {"id": "demo",
     "display_name": "Namespace Browser (Phase 3b demo)",
     "description": "Lists ns_modules rows; click to open the editor on any of them.",
     "root_ns": "app.main",
     "background": "#0f766e",
     "published": True},
]
for app in APP_ROWS:
    request("DELETE", f"/rest/v1/apps?id=eq.{app['id']}")
    print(f"==> Inserting app row '{app['id']}' -> {app['root_ns']}")
    request("POST", "/rest/v1/apps", app)

print("==> Verifying ns_closure(app.launcher)")
req = urllib.request.Request(
    base + "/rest/v1/rpc/ns_closure",
    data=json.dumps({"root": "app.launcher"}).encode(),
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

print("==> Listing apps")
req = urllib.request.Request(
    base + "/rest/v1/apps?select=id,display_name,root_ns",
    headers={"apikey": key, "Authorization": "Bearer " + key},
)
with urllib.request.urlopen(req) as resp:
    print("  " + resp.read().decode())
PY

echo "==> Done."
