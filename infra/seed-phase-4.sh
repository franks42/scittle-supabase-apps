#!/usr/bin/env bash
# Phase 4 seed: ten namespaces (phase-3d set + app.facts) into ns_modules,
# plus a 'facts' row in apps pointing at app.facts.

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
         "app.auth", "app.parser", "app.editor",
         "app.main", "app.launcher", "app.facts"]
SEEDS = "supabase/seeds/phase-4"

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
    {"name": "app.parser",   "version": 1, "deps": ["clojure.edn"],
     "description": "Extract :require deps from a (ns ...) form."},
    {"name": "app.editor",   "version": 1,
     "deps": ["clojure.string", "reagent.core", "app.supa", "app.auth", "app.parser", "app.wm", "app.cm6"],
     "description": "Windowed editor wired to ns_modules; parser-on-save derives deps."},
    {"name": "app.main",     "version": 1,
     "deps": ["reagent.core", "reagent.dom", "clojure.string", "app.utils", "app.supa", "app.auth", "app.editor"],
     "description": "Phase 3b demo — list namespaces, click to edit and save."},
    {"name": "app.launcher", "version": 1,
     "deps": ["reagent.core", "reagent.dom", "app.supa", "app.auth", "app.wm"],
     "description": "Lists rows from the apps table and dispatches via window.__dispatch__."},
    {"name": "app.facts",    "version": 1,
     "deps": ["reagent.core", "reagent.dom", "app.supa", "app.auth"],
     "description": "Phase 4 demo — scheduled ingestion + live-proxy via Edge Function."},
]

for row in NS_ROWS:
    row["source"] = read(f"{SEEDS}/{row['name']}.cljs")
    print(f"==> Inserting {row['name']} v1")
    request("POST", "/rest/v1/ns_modules", row)

print("==> Upserting apps rows")
APP_ROWS = [
    {"id": "demo",
     "display_name": "Namespace Browser (Phase 3 demo)",
     "description": "Lists ns_modules rows; click to open the editor.",
     "root_ns": "app.main",
     "background": "#0f766e",
     "published": True},
    {"id": "facts",
     "display_name": "Cat Facts (Phase 4 demo)",
     "description": "Scheduled ingestion via pg_cron + live-proxy Edge Function.",
     "root_ns": "app.facts",
     "background": "#0ea5e9",
     "published": True},
]
for app in APP_ROWS:
    request("DELETE", f"/rest/v1/apps?id=eq.{app['id']}")
    print(f"==> Inserting app row '{app['id']}' -> {app['root_ns']}")
    request("POST", "/rest/v1/apps", app)

print("==> Listing apps")
req = urllib.request.Request(
    base + "/rest/v1/apps?select=id,display_name,root_ns&order=display_name",
    headers={"apikey": key, "Authorization": "Bearer " + key},
)
with urllib.request.urlopen(req) as resp:
    print("  " + resp.read().decode())
PY

echo "==> Done."
