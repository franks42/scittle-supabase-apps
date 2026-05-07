# Scittle-on-Supabase: Requirements & Solution Design

A single-vendor, dynamically loaded ClojureScript application platform where
Supabase is both the host and the runtime backing store. The browser runs
Scittle (sci) and pulls source code on demand via the Supabase REST API;
external data is ingested into Postgres by Supabase itself, or proxied live
through Edge Functions when CORS would otherwise block the browser; users
edit code in a browser CodeMirror widget that persists back to the same
Postgres tables. The first thing the browser loads is itself a Scittle app
— a launcher that picks among other apps stored in the same database.

> **Status (revision 4).** This revision leads with a §1 Vision section so
> the rest of the document derives from an explicit mental model rather
> than an implicit one. The core mechanism — fetching ClojureScript
> namespaces from a database and evaluating them into the browser's sci
> interpreter — has been validated end-to-end against stock
> `scittle@0.8.31`. See the accompanying `scittle-supabase-poc.html`. The
> design has since expanded beyond the browser-only frame: the same
> `ns_modules` table can in principle feed an Edge Function evaluator
> (via `nbb` on Deno) and even an in-Postgres evaluator (via `plv8` +
> Scittle) without schema changes. Sections §4.8 and §4.9 cover those
> extensions; §5 surveys the prior art that informs the design. The
> reference deployment targets the Supabase free tier; tier upgrades are
> a scaling concern, not a design prerequisite.

---

## 1. Vision

The mental model the rest of this document derives from. §2 (Requirements)
and §4 (Solution) read as concrete realizations of these pillars.

### 1.1 Browser as runtime, no build step

Scittle (sci) runs in the browser. ClojureScript code is data in Postgres,
fetched and evaluated on demand. There is no compile/bundle step in the
production loop — edit a row, the next page load (or a live re-eval mid-
session) reflects the change. The whole authoring → running cycle stays
in the browser.

### 1.2 Supabase plays five roles

Supabase is not just the database. It plays five distinct roles around the
browser-resident Scittle runtime:

1. **Static web server** for the bootstrap shell (`shell.html` plus a small
   JS bootstrapper).
2. **JavaScript library host** — Scittle core and plugin bundles
   (`scittle.js`, `scittle.reagent.js`, `scittle.promesa.js`, …) served
   from Supabase Storage rather than an external CDN. This is what makes
   the single-vendor claim literal rather than aspirational.
3. **Code repository** — the `ns_modules` table holds every ClojureScript
   namespace the apps are built from, one row per `(name, version)`.
4. **Data store** — both for data ingested from external services and for
   app-owned tables created by the apps themselves.
5. **Application server** — Edge Functions (Deno) and `pg_net` reach
   external HTTP services on behalf of the browser. Two patterns:
   *scheduled ingestion* (poll → store in Postgres → browser reads later)
   and *live proxy* (browser request → Edge Function fetches synchronously
   → response flows back).

One vendor, one auth boundary, one place to look.

### 1.3 The bootstrap app is a launcher

What loads first is itself a Scittle app: `app.launcher`, a menu that
enumerates available apps from `ns_modules` and dispatches to a chosen
one. On selection, the launcher fetches that app's namespace closure,
ensures the required Scittle plugins are loaded (per §1.2 role 2), and
evaluates the closure into the running sci context.

New apps appear by adding rows to `ns_modules`, not by redeploying the
shell. Plugin requirements are derived from the chosen app's
`(:require ...)` forms, so apps that don't use Reagent never pay for it.

### 1.4 The browser is also the IDE

Apps are created, edited, configured, and versioned from inside the
running Scittle environment. CodeMirror with `@nextjournal/clojure-mode`
is the editor; saving writes a new row (or version) to `ns_modules`;
re-eval lands the change into the live sci context. Authoring and running
happen in the same place — there is no separate dev environment to set
up, no compile step, no redeploy.

### 1.5 What this is not

A few clarifications, framed as boundaries:

- **Not a circumvention of CORS.** The design routes third-party HTTP
  calls through Supabase because browsers enforce CORS and HTTP servers
  don't. The bypass is technical, not legal — see §4.6 for the full
  rationale, including what the design *cannot* do (override target
  terms of service, reach into a user's third-party logged-in session,
  evade target rate limits).
- **Not a full ClojureScript compiler.** Only the sci-supported subset
  is available. Macros and advanced compilation features behave per
  sci's documented support matrix.
- **Not an npm-in-the-browser system.** JS interop in the browser
  happens through globals registered at shell-load time, per Scittle's
  standard `js-libraries` mechanism. (Edge-side nbb evaluators do
  support npm modules; see §4.9.)
- **Not a system that exposes the secret/service-role key to the browser.**
  Admin operations — schema changes, function deploys, secret writes —
  happen via the Supabase CLI or dashboard. The running app uses the
  publishable/anon key plus per-user JWTs. Multi-tenancy is enforced by
  Postgres RLS, not by client-side checks.

---

## 2. Requirements

### 2.1 Hosting

- R1. The bootstrap HTML page (the "shell") is served by Supabase. No
  external CDN, Vercel, Netlify, or Cloudflare Pages in the critical path.
- R2. The shell loads Scittle and a small JS bootstrapper, then hands
  control to ClojureScript code that lives in the database.
- R3. A custom domain with TLS is supported (production-grade, paid
  Pro+ feature) or the default `<ref>.supabase.co/storage/v1/...` URL
  is acceptable (development-grade, free-tier-compatible). The
  reference design targets the default URL initially; custom-domain
  migration is a Phase 5+ scaling concern, not a design prerequisite.
- R4. Scittle core and plugin JS bundles are served from Supabase
  Storage (public bucket), not from an external CDN. CDN loading
  remains acceptable for development.

### 2.2 Code storage and runtime loading

- R5. ClojureScript namespaces are stored as rows in a Postgres table,
  one row per `(name, version)` pair, alongside a `deps` array.
- R6. The browser obtains namespace source from the database via Supabase
  REST/RPC at runtime, not from static `.cljs` files on disk.
- R7. The relevant closure of namespaces is loaded at app boot (one
  round-trip via a recursive CTE). Individual namespaces can be re-fetched
  and re-evaluated mid-session without a page refresh, so an editor save
  immediately affects the running interpreter.
- R8. Versioning is supported. A namespace row can be updated and the next
  page load (or explicit reload) picks up the new version without any
  redeploy of the shell.
- R9. Access control is enforced by Postgres RLS, not by client-side
  checks.
- R10. The shell loads a launcher namespace (`app.launcher`) that
  enumerates available apps from `ns_modules` and dispatches to a chosen
  one. No app name is hardcoded in the static shell.
- R11. The launcher (or per-app loader) determines which Scittle plugins
  a chosen app's namespace closure requires and loads only those JS
  bundles. Plugins not needed by the running app are never fetched.

### 2.3 Server-side HTTP

(Reframed from "External data ingestion" — the vision covers both
scheduled ingestion *and* live proxy patterns.)

- R12. Supabase itself is responsible for fetching data from external HTTP
  endpoints. The browser does not proxy or trigger third-party calls
  except indirectly through Supabase. Two complementary patterns:
  - **R12a (scheduled ingestion).** Periodic fetches via `pg_cron` +
    `pg_net`, or scheduled Edge Functions, with results stored in
    Postgres for the browser to read later.
  - **R12b (live proxy).** Browser-initiated calls reach an Edge Function
    that performs the actual fetch synchronously and returns the
    response. Used when the request is user-specific or doesn't fit a
    polling cadence.
- R13. Ingestion can be scheduled (cron-style) and/or triggered by row
  events (DB triggers / webhooks).
- R14. Secrets used by ingestion or by the live proxy (API keys, tokens)
  are stored server-side in Supabase Vault, never exposed to the browser.

### 2.4 In-browser editing and IDE

- R15. Users can edit ClojureScript code in a CodeMirror widget embedded
  in the page, with proper Clojure syntax highlighting and structural
  editing (paredit-style key bindings).
- R16. Saving the editor's content writes a new row (or updates an
  existing row) in the namespace table.
- R17. The newly saved code is loaded into the running interpreter on
  demand, by re-evaluating the source returned from the database. No
  full page refresh required.
- R18. Users can create a new app from inside the running Scittle
  environment — scaffold the initial namespace, register it so the
  launcher discovers it, and open the editor on it.

### 2.5 Operating constraints

- R19. The reference design runs entirely on the Supabase free tier. All
  resource budgets stay within free-tier ceilings (500 MB Postgres, 1 GB
  Storage, 500K Edge Function invocations/month, 5 GB egress/month, 150 s
  function timeout). Tier upgrades are a scaling concern, not a design
  prerequisite.

### 2.6 Non-goals (explicitly out of scope)

- N1. Compiling user code with the full ClojureScript / Closure compiler.
  Only the sci-supported subset is available.
- N2. Loading arbitrary npm packages at runtime in the browser. JS interop
  there happens through globals registered up front in the shell, per
  Scittle's standard `js-libraries` mechanism. (Edge-side nbb evaluators
  do support npm modules; see §4.9.)
- N3. Multi-tenancy beyond what RLS provides.
- N4. Exposing the secret / service-role key to the browser. Admin
  operations are CLI/dashboard-only.

---

## 3. Architecture overview

```
                  ┌──────────────────────────────────────────────┐
                  │                  Supabase                    │
                  │                                              │
   Browser ◄──── Storage buckets ─────                           │
        │         shell/  shell.html, boot.js                    │
        │         libs/   scittle.js, scittle.reagent.js, …      │
        │         (custom-domain optional, paid Pro+)            │
        │                                                        │
        │         ┌────────────────────────────────────────┐     │
        │         │ Postgres                               │     │
        │ REST ──►│   ns_modules(name, version, source,    │     │
        │ /RPC    │               deps, refs, updated_at)  │     │
        │         │   ns_closure(root)  -- recursive CTE   │     │
        │         │   ns_impact(root)   -- reverse CTE     │     │
        │         │   external_data(...)                   │     │
        │         │                                        │     │
        │         │   pg_net ─► external HTTP              │     │
        │         │   pg_cron ─► scheduled ingestion       │     │
        │         │   triggers ─► Edge Functions           │     │
        │         └────────────────────────────────────────┘     │
        │                                                        │
        │         ┌────────────────────────────────────────┐     │
        └─◄──────►│ Edge Functions (Deno)                  │     │
                  │   /ingest, /transform, /proxy/*        │     │
                  │   authored in TS, or in cljs via nbb   │     │
                  │   (can also pull namespaces from       │     │
                  │   ns_modules and evaluate them)        │     │
                  └────────────────────────────────────────┘     │
                  └──────────────────────────────────────────────┘

Browser process (boot sequence):
  ┌──────────────────────────────────────────────────────────────┐
  │ shell.html                                                   │
  │  ├─ <script> scittle.js          (Storage: libs/)            │
  │  ├─ <script> supabase-js         (Storage: libs/)            │
  │  └─ <script> boot.js                                         │
  │       1. supabase.rpc('ns_closure', {root:'app.launcher'})   │
  │       2. topo-sort + sequential eval_string                  │
  │       3. (app.launcher/start!)  → renders menu of apps       │
  │       4. user picks app → fetch its closure                  │
  │       5. resolve plugin needs from (:require ...) forms      │
  │       6. fetch+eval missing plugin bundles from libs/        │
  │       7. topo-eval cljs closure, call (chosen-app/start!)    │
  └──────────────────────────────────────────────────────────────┘
```

---

## 4. Solution

### 4.1 Hosting the shell on Supabase Storage

A public Storage bucket holds `shell.html`, a tiny `boot.js`, and any
static assets. Two notes worth being honest about:

- Supabase has historically been ambivalent about static hosting and at
  various points overrode the `Content-Type` on HTML to `text/plain` for
  abuse-prevention reasons. As of the current state of the platform,
  HTML is served correctly when the upload sets the right content type
  explicitly, and works seamlessly behind a custom domain.
- For production, attach a custom domain (paid Pro+ feature) so the
  shell loads from `app.example.com` rather than the raw Storage URL.
  This also removes any same-origin awkwardness with the REST endpoint.
  On free tier, the shell loads from
  `https://<ref>.supabase.co/storage/v1/object/public/shell/shell.html`,
  which works but is not pretty.

If at any point Supabase Storage hosting becomes a friction point, the
shell can be moved to Cloudflare Pages or similar with zero changes to
the rest of the architecture — the entire dynamic surface lives in
Postgres + Edge Functions.

### 4.2 Schema for code storage

```sql
create table public.ns_modules (
  name        text not null,
  version     int  not null default 1,
  source      text not null,
  deps        text[] not null default '{}',  -- declared (:require ...) targets
  refs        text[] not null default '{}',  -- referenced fully-qualified vars (optional)
  description text,
  updated_at  timestamptz not null default now(),
  updated_by  uuid references auth.users(id),
  primary key (name, version)
);

create view public.ns_modules_current as
  select distinct on (name) *
  from public.ns_modules
  order by name, version desc;

-- Forward closure: all transitive deps of `root`. Used by the loader.
create or replace function public.ns_closure(root text)
returns table (name text, source text, deps text[], version int) as $$
  with recursive walk(name) as (
    select root
    union
    select unnest(m.deps)
    from public.ns_modules_current m
    join walk w on w.name = m.name
  )
  select m.name, m.source, m.deps, m.version
  from public.ns_modules_current m
  join walk w on w.name = m.name;
$$ language sql stable;

-- Reverse closure: namespaces transitively impacted by changing `target`.
-- Used by the editor for "what will this break" warnings.
create or replace function public.ns_impact(target text)
returns table (name text) as $$
  with recursive walk(name) as (
    select target
    union
    select m.name
    from public.ns_modules_current m
    join walk w on w.name = any(m.deps)
  )
  select name from walk where name <> target;
$$ language sql stable;

-- RLS
alter table public.ns_modules enable row level security;

create policy "read published code"
  on public.ns_modules for select
  using (true);  -- or: using (auth.role() = 'authenticated')

create policy "authors can write"
  on public.ns_modules for insert
  with check (auth.uid() is not null);
```

The recursive CTE makes the default loading strategy a one round-trip
operation rather than N. The reverse CTE is what lets §4.8's impact-graph
features work.

### 4.3 Browser bootstrap

The browser's boot sequence has three phases. Each phase uses only the
three exported entry points on the stock Scittle bundle:
`scittle.core.eval_string`, `scittle.core.eval_script_tags`, and
`scittle.core.disable_auto_eval`. No custom Scittle build, no plugin
compile step.

#### 4.3.1 Shell phase

The static `shell.html` loads `scittle.js` and `supabase-js` (script tags
pointing at the `libs/` Storage bucket — see §4.4), then loads `boot.js`,
which contains the launcher bootstrapper. No plugins yet — plugins come
on-demand once the launcher knows which app the user wants.

```html
<!doctype html>
<html>
<head>
  <script src="https://<ref>.supabase.co/storage/v1/object/public/libs/scittle-0.8.31.js"></script>
  <script src="https://<ref>.supabase.co/storage/v1/object/public/libs/supabase-js-2.x.y.js"></script>
</head>
<body>
  <div id="app">Loading…</div>
  <script src="https://<ref>.supabase.co/storage/v1/object/public/shell/boot.js"></script>
</body>
</html>
```

#### 4.3.2 Launcher phase

`boot.js` fetches the closure for `app.launcher` (a fixed root) and
evaluates it:

```javascript
const sb = supabase.createClient(SUPABASE_URL, SUPABASE_PUBLISHABLE_KEY);

function topoSort(rootName, byName) {
  const visited = new Set(), order = [];
  (function visit(name) {
    if (visited.has(name)) return;
    visited.add(name);
    const row = byName[name];
    if (!row) throw new Error('missing namespace: ' + name);
    (row.deps || []).forEach(visit);
    order.push(name);
  })(rootName);
  return order;
}

async function loadAndEval(rootName) {
  const { data: rows, error } = await sb.rpc('ns_closure', { root: rootName });
  if (error) throw error;
  const byName = Object.fromEntries(rows.map(r => [r.name, r]));
  const order  = topoSort(rootName, byName);
  // Eval in dependency order. Each (ns foo …) form registers the
  // namespace as a side effect, so subsequent (:require [foo]) inside
  // later sources resolves naturally — no custom load-fn needed.
  for (const n of order) scittle.core.eval_string(byName[n].source);
}

async function bootLauncher() {
  await loadAndEval('app.launcher');
  scittle.core.eval_string('(app.launcher/start!)');
}

bootLauncher().catch(e => {
  document.getElementById('app').textContent = 'Boot failed: ' + e.message;
  console.error(e);
});
```

The launcher renders a menu of available apps (queried from `ns_modules`
or a separate `apps` registry — see §8 Open questions).

#### 4.3.3 App-launch phase

When the user picks an app, the launcher resolves and loads it:

```javascript
const LOADED_PLUGINS = new Set();          // tracks fetched plugin bundles
const PLUGIN_MANIFEST = {                  // see §4.4
  "reagent.*":   "libs/scittle-0.8.31-reagent.js",
  "promesa.*":   "libs/scittle-0.8.31-promesa.js"
};

async function launchApp(rootName) {
  // 1. Fetch the chosen app's closure
  const { data: rows } = await sb.rpc('ns_closure', { root: rootName });
  const byName = Object.fromEntries(rows.map(r => [r.name, r]));

  // 2. Determine which Scittle plugins the closure requires
  const required = pluginsRequiredFor(rows);                  // walks (:require ...)
  const missing  = required.filter(p => !LOADED_PLUGINS.has(p));

  // 3. Fetch+eval missing plugin bundles from Storage
  for (const p of missing) {
    const url = STORAGE_URL + '/' + PLUGIN_MANIFEST[p];
    const src = await fetch(url).then(r => r.text());
    (0, eval)(src + '\n//# sourceURL=' + p + '.js');
    LOADED_PLUGINS.add(p);
  }

  // 4. Topo-eval the cljs closure, call start!
  const order = topoSort(rootName, byName);
  for (const n of order) scittle.core.eval_string(byName[n].source);
  scittle.core.eval_string('(' + rootName + '/start!)');
}
```

This entire flow has been verified against `scittle@0.8.31` in the
accompanying PoC (with the launcher and plugin-resolution steps simulated
by `window.NS_DB`). The mechanism — orchestrate load order from JS, defer
to Scittle for evaluation — is the shape of the design that survives
contact with the actual bundle.

**Library-loading mechanism note.** Classic-script bundles (Scittle core,
plugins) can be loaded equivalently via `<script src=...>` or via
`fetch(url).then(r => r.text()).then(src => (0,eval)(src))`. The launcher
uses fetch+eval for plugins so it can sequence loads against an app-
specific manifest, and append a `//# sourceURL=...` line for clean
DevTools attribution. CSP impact is nil — sci itself already requires
`'unsafe-eval'`.

**Sci context lifecycle.** Switching apps does *not* get a fresh sci
context by default. Re-eval'ing a different root's namespaces into the
same context is fine for the launcher's own purposes but means the
previously-running app's state and DOM mounts persist unless the
launcher tears them down. This is a design knob — see §8 Open questions.

### 4.4 Library hosting and on-demand plugin loading

This is what makes R1 (no external CDN) and R4 (Scittle bundles in
Storage) literal rather than aspirational. Different apps in `ns_modules`
need different plugin sets — this section describes how the launcher
matches plugins to apps without forcing every shell load to fetch every
plugin.

**Storage layout.** A public `libs/` bucket holds:

```
libs/
  scittle-0.8.31.js
  scittle-0.8.31-reagent.js
  scittle-0.8.31-promesa.js
  scittle-0.8.31-cljs-ajax.js
  scittle-0.8.31-nrepl.js
  supabase-js-2.x.y.js
  codemirror-bundle.js     (loaded when the IDE is opened)
```

Each upload pins `Content-Type: application/javascript` and
`Cache-Control: public, max-age=31536000, immutable`. Bundles are
versioned by filename — cache-bust on upgrade is just changing the
manifest reference; the immutable header is safe because the URL
identity changes on version bump. Free-tier 1 GB Storage is more than
enough; the entire Scittle bundle set is a few hundred kilobytes.

**URLs.** Public-bucket reads are served at
`https://<ref>.supabase.co/storage/v1/object/public/libs/<file>`. No
auth header needed.

**Plugin manifest.** A small static JSON (or a `plugins` table in
Postgres — see §8 Open questions) maps cljs namespace prefixes to plugin
bundle URLs:

```json
{
  "reagent.*":   "libs/scittle-0.8.31-reagent.js",
  "promesa.*":   "libs/scittle-0.8.31-promesa.js",
  "cljs-http.*": "libs/scittle-0.8.31-cljs-ajax.js"
}
```

The launcher reads this once at boot.

**Resolution algorithm.** Given the chosen app's closure, walk every
declared `(:require [foo.bar …])`. For each `foo.bar` not already
registered in the running sci context, look up the plugin bundle in the
manifest and add it to a "to-load" set. Fetch+eval the set in order
(core plugins like Reagent before composite ones), then topo-eval the
cljs closure.

**Caching.** Loaded plugins are tracked in a JS-side `Set` so they don't
re-load when the user switches between apps that share plugins.

**Migration vs. CDN.** Phase 1 can keep CDN URLs in the manifest;
Phase 5 (hardening) flips the manifest to Storage URLs once the bucket
is populated. Zero code change in the launcher.

### 4.5 Loading strategies

**Default (works against stock Scittle): eager closure + topo eval.**
The pattern shown in §4.3. One round-trip pulls the closure, topo-sort
resolves order, sequential `eval_string` populates the sci context. Cold
start is one `RPC` round-trip plus a few milliseconds per namespace for
parsing and evaluation. For a typical app of 10–30 small namespaces,
that's well under 200 ms total on a warm Supabase project.

**Lazy per-require (requires a custom Scittle build).**
True on-demand resolution, where each `(:require [some.ns])` triggers
its own DB lookup, requires sci's `:async-load-fn` mechanism living in
`sci.async`. Stock Scittle does not bundle `sci.async`, and
`scittle.core/register-plugin!` (which would be the wiring point) is
not exported to JavaScript — only `eval-string`, `eval-script-tags`,
and `disable-auto-eval` are. Achieving lazy resolution therefore
requires a custom Scittle build that adds `sci.async` to its
namespaces map and exposes a JS-callable hook for installing
`:async-load-fn`. Roughly thirty extra lines on top of
`scittle/core.cljs`.

This is worth doing only when eager closure prefetch becomes too
coarse — typically when the app is large enough that most users never
reach most feature namespaces in a session. The default strategy
should be adopted first; lazy resolution can be retrofitted later
without changing the schema.

**Hybrid (in the lazy build, optional).**
Prefetch a "core" set (UI shell, routing, common utils) at boot,
install `:async-load-fn` for everything else. Best UX for medium-to-
large apps if the custom build path is on the table.

### 4.6 Server-side HTTP

A note on why this lives in Supabase rather than the browser: same-origin
policy and CORS are browser-enforced restrictions, not properties of
HTTP itself. A Postgres server calling `net.http_get` or a Deno Edge
Function calling `fetch` has no origin context to enforce against —
it's just an HTTPS client. Whatever the target returns lands in
Postgres (or flows back through the function), and the browser reads
it via Supabase's own REST/Functions endpoint, which sets permissive
CORS headers automatically. This sidesteps the per-target CORS dance
that pure-frontend apps either have to negotiate or pay a third-party
proxy to handle.

The bypass is technical, not legal. It does not override a target site's
terms of service, authentication requirements, or rate limits. The
target sees Supabase's IP rather than the user's, which means
rate-limit hits, IP bans, and abuse complaints land on the project
rather than on individual users. Some targets actively block datacenter
IP ranges or run bot detection that will refuse a server-side fetch for
reasons unrelated to CORS. Per-user authenticated data still requires
the user to hand credentials to the server (OAuth token in Vault, etc.)
— the server cannot reach into a third-party logged-in session on the
user's behalf.

Within those bounds, the architectural payoff is real: any public JSON
API, RSS feed, partner endpoint, or scraping target you are entitled
to access can be pulled into Postgres tables (or proxied live) through
the regular Supabase client.

Three complementary mechanisms, all server-side:

#### 4.6.1 `pg_net` + `pg_cron` (scheduled, SQL-driven)

For periodic syncs (e.g. price feeds, RSS, public APIs) where the
response shape is simple JSON that can be inserted with
`jsonb_to_recordset` or unpacked in a PL/pgSQL function. Secrets
retrieved from Supabase Vault.

```sql
create or replace function public.refresh_external_data()
returns void
language plpgsql
security definer
as $$
declare
  api_key text := vault.read_secret('EXTERNAL_API_KEY');
begin
  perform net.http_get(
    url     := 'https://api.example.com/v1/feed',
    headers := jsonb_build_object('Authorization', 'Bearer '||api_key)
  );
end$$;

select cron.schedule(
  'refresh-external-data',
  '*/15 * * * *',
  $$select public.refresh_external_data()$$
);
```

Responses land in `net._http_response` and are processed into the
target table either by the same function (using `pg_net`'s sync wait
helpers) or by a follow-on cron job that reads completed responses.
**The browser cannot call `pg_net` directly** — it's a Postgres
function, not exposed via REST. Browser-initiated server fetches go
through one of the next two patterns.

#### 4.6.2 Edge Functions for code (scheduled or triggered)

For anything that needs real code: pagination loops, OAuth flows,
retry logic, response transformation. Triggered by cron (`pg_cron`
calls the function URL via `pg_net`), by DB triggers, or by direct
HTTPS invocation from the browser.

#### 4.6.3 Live proxy via Edge Function (browser-initiated)

The complement to scheduled ingestion: when the browser needs a
synchronous round-trip to a third-party service that CORS won't allow
directly.

- **Invocation URL.** Browser calls
  `https://<ref>.supabase.co/functions/v1/<name>` via
  `supabase.functions.invoke(...)`, which adds the user's JWT as
  `Authorization`. Custom-domain version is a Phase 5+ upgrade.
- **JWT verification on by default.** `verify_jwt = true` — the
  function rejects unauthenticated calls. Per-user authorization
  inside the function uses the JWT's `sub` (user ID) the same way
  RLS does.
- **Server-side URL/host allowlist.** The function enforces an
  allowlist of permitted target hosts inside its own logic. Without
  this, the function is an open relay and any signed-in user can
  use it to fetch arbitrary URLs. The allowlist lives in the
  function's source (or in a config table) and is part of the
  security model, not a nicety.
- **Vault-backed credentials.** Tokens for upstream services are
  read via `Deno.env.get('UPSTREAM_TOKEN')` (set with
  `supabase secrets set`) and added server-side. The browser never
  sees them.
- **Free-tier budget awareness.** 500K invocations/month, 2M
  function-seconds, 150 s per-call timeout, 5 GB egress/month.
  Live-proxy traffic counts against all four. For low-traffic apps
  this is generous; for anything user-facing at scale, monitor
  usage from day one.
- **Tradeoff vs. ingestion.** Live proxy is right when the browser
  needs a request that's user-specific or doesn't fit a polling
  cadence; ingestion is right when many users will read the same
  data and a cache TTL is acceptable.

### 4.7 In-browser IDE

CodeMirror 6 with `@nextjournal/clojure-mode` provides Clojure syntax,
Lezer-based incremental parsing, and a paredit-style keymap. The
editor's contents are saved to `ns_modules` as a new row, then
re-evaluated into the running interpreter so the running app sees the
change immediately:

```javascript
async function saveAndReload(nsName, source, deps) {
  // Write the new version to Postgres
  const { error } = await sb.from('ns_modules').insert({
    name: nsName, source, deps,
    version: await nextVersionFor(nsName)
  });
  if (error) throw error;

  // Re-eval into the running sci context. The (ns nsName ...) form at
  // the top of `source` re-registers the namespace and replaces its
  // vars in place.
  scittle.core.eval_string(source);
}
```

**Reload semantics.** Re-evaluating an updated namespace replaces its
vars in the sci context. Code that captured old refs through closures
will keep the old behavior — same caveat as `:reload` in standard
Clojure. For Reagent components, nudging an atom (or remounting via
`rdom/render`) makes the new defs visible on the next render. A
practical pattern is to keep the top-level `start!` idempotent so the
editor save handler can call it again after re-eval.

**Creating a new app.** The launcher exposes a "New app" action. The
flow:

1. Prompt for an app name (e.g. `app.dashboard`).
2. Scaffold a starter namespace:
   ```clojure
   (ns app.dashboard
     (:require [reagent.core :as r]
               [reagent.dom :as rdom]))

   (defn root []
     [:div "hello from app.dashboard"])

   (defn ^:export start! []
     (rdom/render [root] (.getElementById js/document "app")))
   ```
3. Insert into `ns_modules` (initial version 1) and register in the
   app registry (a row in `apps`, or a tag on `ns_modules` — see §8
   Open questions).
4. Re-render the launcher menu so the new app appears.
5. Open the editor on the newly created namespace.

### 4.8 The dep graph as a queryable object

Once `deps` lives in Postgres, the dependency graph itself is a
first-class queryable object rather than something inferred from a
file tree. Several features fall out of this with very little
additional code:

- **Forward closure** is the loader (§4.3, `ns_closure`).
- **Reverse closure / impact graph** is the editor's "what will this
  break" warning (§4.2, `ns_impact`). On every save, the editor can
  show the user the list of namespaces whose behaviour might change,
  before the save commits.
- **Module-level dependency injection.** A namespace's `deps` row
  points at whatever implementation it currently uses. Swap the
  Stripe implementation for the Adyen one with an UPDATE; calling
  code is unchanged. This is a pattern that file systems make
  awkward and package managers handle clumsily.
- **Time-travel.** With `updated_at` already on every row (and an
  optional history table or temporal view), the loader can be
  parameterised by `as_of timestamptz` and reproduce the state of the
  app at an arbitrary past moment. Useful for reproducing reported
  bugs and for audit.
- **Permission-driven code surfaces.** RLS policies on `ns_modules`
  make the closure that an unauthenticated user pulls smaller than
  the closure pulled by a paid user. A premium feature literally
  doesn't exist in the free-tier user's bundle. No application-side
  branching needed.

**Required discipline.** Declared `deps` must match the actual
`(:require ...)` forms in `source`, or the loader will think it has
the closure when it doesn't. The robust fix is to derive `deps`
automatically from the source on insert/update — either an Edge
Function with `tools.reader`, or a Postgres trigger that runs a small
parser. The same parser-on-save pass can populate a `refs text[]`
column with referenced fully-qualified vars, which then enables
fine-grained queries like "find every namespace that uses
`app.payments/charge!`" — symbol-level impact analysis as a single
SQL query. The same parser also feeds the launcher's plugin-
resolution algorithm in §4.4.

### 4.9 Multi-runtime extension (optional)

The browser is the primary runtime, but the same `ns_modules` table
can feed evaluators in two other places without schema changes. Both
V8-based, both running sci flavours.

**Edge Functions in cljs (via nbb on Deno).**
Supabase Edge Functions run on Deno, which is V8 + Node-flavoured
APIs. [nbb](https://github.com/babashka/nbb) — sci packaged for
Node/Deno, by the same author as Scittle — runs there cleanly. The
pattern Borkdude documented for AWS Lambda ports almost verbatim:

```ts
// supabase/functions/api/index.ts
import { loadFile } from 'npm:nbb';
const { handler } = await loadFile('./handler.cljs');
Deno.serve(handler);
```

The interesting variant for this design is the *dynamic* one: a small
TypeScript shell that, on request, fetches the relevant cljs from
`ns_modules` and evaluates it via nbb. Hot-swap of business logic
without redeploying functions. Cold start is sensitive to nbb load
time (measured ~100 ms in the AWS Lambda case), so this is
appropriate for webhooks and non-latency-critical APIs, less so for
hot-path endpoints. Caveat: Supabase officially supports only
TypeScript Edge Functions. Operationally Deno doesn't care — JS is
JS — but you give up dashboard type-checking and AI-assistant
coverage. No public template exists for nbb-on-Supabase-Edge as of
this writing; you'd be the first to publish one.

**plv8 + Scittle (exploratory only).**
`plv8` exposes V8 inside Postgres, and the stock Scittle bundle has
been verified to work in a non-DOM V8 context. So in principle you
can have a Postgres function whose body evaluates Clojure code stored
in another Postgres row. Test results: arithmetic, transducers,
macros, multi-ns require, and DB-stored namespace evaluation all
work. **The catch:** plv8 is deprecated in Postgres 17, which is what
new Supabase projects increasingly run on. There's no drop-in
replacement inside the database. Treat this as a "spike for fun"
path, not a production target — the Edge-Function evaluator is the
sustainable home for server-side clj evaluation.

**The architectural payoff of multi-runtime.**
A namespace that performs only data transforms or validation can run
in all three places: browser (instant feedback), Edge (trusted
check), plv8 (last-line-of-defence in a constraint or trigger). UI
namespaces run only in the browser; filesystem-touching or npm-using
namespaces run only in Edge. The discipline that buys this is keeping
environment-coupled code in clearly-named namespaces (`app.ui.*`
browser-only, `app.io.*` edge-only, `app.pure.*` runs everywhere).
This is the runtime equivalent of the Clojure/ClojureScript `cljc`
split, but expressed as data in the DB rather than build
configuration.

---

## 5. Prior art

The pattern of "user-authored code stored in a backend, evaluated client-
side" is not new. Surveying what exists clarifies where this design is
borrowing well-trodden patterns and where it's genuinely novel.

**Supabase as a host for dynamic JavaScript.** Supabase has an official
walkthrough — [Executing Dynamic JavaScript Code on Supabase with Edge
Functions](https://supabase.com/blog/supabase-dynamic-functions) —
accompanied by [mansueli/supa-dynamic](https://github.com/mansueli/supa-dynamic).
JS lives in Postgres (or in a request body) and is evaluated by an Edge
Function via `new Function()`. Same "code as data" shape as this design,
but the runtime sits on the server, not in the browser. Their security
framing (authorization checks, scoping the eval context, Vault for
secrets) is directly relevant to the in-browser case.

**Internal-tool builders: code in DB, eval in browser.** This is the
architecturally closest pattern, just packaged as products.
[Appsmith](https://github.com/appsmithorg/appsmith) is open-source and
the most direct match — JavaScript snippets authored in a browser editor
are stored in Appsmith's backend and evaluated client-side at runtime.
[Tooljet](https://github.com/ToolJet/ToolJet) and
[Budibase](https://github.com/Budibase/budibase) follow the same pattern
with different feature emphases. Retool is the proprietary incumbent
doing the same thing. These products are proof that the runtime model
works at scale; the difference is they ship a whole platform around it,
where this design exposes the raw mechanism with Supabase as the literal
storage.

**Notebook runtimes.** [Observable Notebooks](https://observablehq.com)
is the most sophisticated example of "JS lives centrally, executes in
the browser with a reactive runtime" — their
[runtime](https://github.com/observablehq/runtime) is open-source and
designed for exactly this loop. Cells are stored on Observable's
servers, fetched, and evaluated in a topologically-aware reactive
scheduler in the browser. Conceptually very close to this Scittle plan;
the main differences are vanilla JS rather than ClojureScript, and a
finer-grained (cell-level) dep model.

**Server-side ClojureScript on serverless / edge platforms.** Well-
trodden territory. Concrete references:

- AWS Lambda + nbb: Borkdude's own
  [Creating an AWS Lambda function with nbb](https://blog.michielborkent.nl/aws-lambda-nbb.html);
  [vharmain/nbb-serverless-example](https://github.com/vharmain/nbb-serverless-example);
  JUXT's [AWS Lambda, now with first class parentheses](https://www.juxt.pro/blog/nbb-lambda/);
  [NickCellino/nbb-comments](https://github.com/NickCellino/nbb-comments)
  (production blog comments service).
- AWS Lambda + shadow-cljs (older AOT path):
  [nervous-systems/cljs-lambda](https://github.com/nervous-systems/cljs-lambda).
- Cloudflare Workers + squint: Borkdude's
  [Writing a Cloudflare worker with squint and bun](https://blog.michielborkent.nl/squint-cloudflare-bun.html).

These give a credible answer to "is server-side cljs production-grade":
yes, multiple companies run it.

**The gap.** No public Scittle-on-Supabase project, and no public
nbb-on-Supabase-Edge template. The combination — bare Supabase as the
backing store, sci as the evaluator on both sides — is what makes this
design distinctive. The runtime patterns themselves are well-validated;
the integration is novel.

---

## 6. Risk register and mitigations

| Risk                                                          | Mitigation                                                                                                                                          |
| ------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------- |
| Supabase Storage hosting friction (MIME, custom domain).      | Pin upload `content-type`. Use custom domain for production (Pro+). Architecture allows moving the shell to Pages without touching the runtime.    |
| Lazy `:require` resolution requires custom Scittle build.     | Default to eager-closure + topo-eval (works against stock Scittle, validated in PoC). Treat lazy resolution as a later optimization, not a blocker. |
| Macro-heavy namespaces behave subtly differently under sci.   | Keep DB-loaded code to data + functions + Reagent components. Audit any macro use against sci's documented support matrix.                          |
| Arbitrary code execution in users' browsers.                  | RLS on `ns_modules` is mandatory, not optional. Treat insert/update permissions as you would treat the ability to deploy a new app version.         |
| Cold-start latency from N sequential evals.                   | Eager closure is one RPC round-trip. Per-namespace eval is microseconds. Consider compressing sources or storing pre-parsed forms only if measured. |
| Reload semantics: closures hold old refs after re-eval.       | Standard Clojure caveat. Document the editor workflow: keep `start!` idempotent, prefer atom-driven re-renders for Reagent.                         |
| Declared `deps` drift from `(:require ...)` in source.        | Parse source on insert/update via Edge Function or trigger; populate `deps` (and `refs`) automatically. Don't trust authors to keep both in sync.   |
| `pg_net` response storage TTL (default 6 hours).              | Either consume responses promptly in the same cron run, or extend `pg_net.ttl` via role config.                                                     |
| Edge Functions in cljs is off-spec for Supabase.              | Operationally fine (Deno doesn't care), but loses dashboard type-checking and AI-assistant coverage. Treat as Phase 7+ option, not foundation.      |
| `plv8` deprecated in Postgres 17.                             | Don't depend on plv8 for any production path. Edge Functions are the sustainable home for server-side cljs evaluation.                              |
| Plugin manifest drift from `(:require ...)` in source.        | Same risk shape as the `deps` drift row. Same mitigation: parse on save, derive plugin needs automatically. Don't maintain the manifest by hand.    |
| Storage as JS host vs. CDN performance.                       | Cold-start latency on Storage is higher than jsDelivr's edge cache. Mitigation: long immutable cache headers; bundles cached in browser after first load; CDN remains an option for development. |
| Free-tier project auto-pause (~7 days inactivity).            | Shell returns 503 during the pause window. Mitigation for development: cheap external uptime ping, or accept manual unpause. Production graduates off free tier. |
| Free-tier resource ceilings.                                  | 500 MB Postgres, 1 GB Storage, 500K Edge Function invocations/month, 5 GB egress, 150 s function timeout. Monitor from day one (Supabase dashboard exposes all four). Plan tier upgrade on approach. |
| Edge Function open-relay risk if live proxy lacks allowlist.  | Explicit URL/host allowlist enforced inside the function (§4.6.3). Treated as a security requirement, not optional hardening.                       |
| Secret/service-role key exposure to the browser.              | Catastrophic — bypasses RLS. Mitigation: never embedded in client code; only used in CLI deploys, dashboard, and Edge Function env vars (set via `supabase secrets set`).                       |

---

## 7. Phased implementation plan

1. **Phase 0 — Spike.** ✅ Complete. Stock Scittle from CDN, simulated DB
   in JS, async fetch + topo-sort + sequential `eval_string`, Reagent
   render, mid-session reload. See `scittle-supabase-poc.html`.
2. **Phase 1 — Schema and RLS.** Production-shape `ns_modules`, the
   `_current` view, the `ns_closure` and `ns_impact` RPCs, and policies.
3. **Phase 1.5 — Library hosting.** Upload Scittle bundles to a public
   `libs/` Storage bucket. Define the plugin manifest (static JSON or
   `plugins` table). Manifest can point at CDN URLs initially; flipping
   it to Storage URLs is a one-line change deferred to Phase 5.
4. **Phase 2 — Real backend wiring.** Replace the in-memory `NS_DB`
   with `supabase.rpc('ns_closure', ...)`. Confirm latency budget on a
   real project. Add error UI and a retry button.
5. **Phase 3 — Editor + parser-on-save + launcher + app creation.**
   CodeMirror with clojure-mode, save flow, version increment, immediate
   re-eval into the running interpreter. Launcher menu enumerating
   apps. App-creation action. Add the source-parser pass that derives
   `deps` (and optionally `refs`) automatically from the `ns` form.
   Wire `ns_impact` into the editor as a "this change will affect: …"
   warning. Wire the parser's `(:require ...)` extraction into the
   plugin-resolution algorithm of §4.4.
6. **Phase 4 — Ingestion and live proxy.** First scheduled `pg_net`
   job; first live-proxy Edge Function with JWT verification + URL
   allowlist; Vault-backed secrets; target tables consumed by the in-
   browser app.
7. **Phase 5 — Hardening.** Custom domain (requires Pro+ upgrade) if
   moving off free tier; flip plugin manifest from CDN to Storage URLs;
   error surfacing in the UI; basic telemetry via Edge Function logs.
8. **Phase 6 (optional) — Lazy resolution.** Custom Scittle build with
   `sci.async` and an exported hook for `:async-load-fn`. Only pursued
   if measured cold-start cost of eager prefetch becomes a problem.
9. **Phase 7 (optional) — Edge-side cljs evaluator.** Spike an Edge
   Function whose handler is loaded from `ns_modules` via nbb.
   Validates the multi-runtime story (§4.9). Start with a non-latency-
   critical webhook endpoint to absorb the cold-start cost gracefully.

---

## 8. Open questions

- Which Scittle plugins are required in the shell up front (Reagent
  only, or also re-frame, replicant, cljs-ajax, nrepl)? Each adds
  bytes to the bootstrap. With on-demand plugin loading (§4.4) this
  question shrinks to "which plugins does the launcher itself need."
- Editor authoring model: per-user drafts (`editor_sessions` table)
  vs. direct writes to `ns_modules`. Affects collaboration story.
- Should `ns_modules` carry a `published` boolean so editing doesn't
  immediately mutate the running app for other users?
- Should namespaces declare an explicit *runtime* tag (`:browser`,
  `:edge`, `:any`) so the multi-runtime story (§4.9) is enforced
  rather than conventional? A small column, big payoff in clarity.
- Is the parser-on-save trigger best implemented in PL/pgSQL with
  regex, in plv8 with `tools.reader`, or in an Edge Function called
  from a trigger via `pg_net`? Tradeoffs: in-DB is atomic but limited;
  Edge is more capable but introduces async coupling.
- nREPL over WebSockets via Scittle's nREPL plugin would give a real
  REPL experience pointing at the running browser; could subsume the
  in-browser editor for power users.
- **App registry shape.** Separate `apps` table (with `name`,
  `root_ns`, `display_name`, `published`, …), or convention (e.g. any
  namespace matching `app.*.main` is an app), or a `kind text` column
  on `ns_modules`?
- **Sci context isolation between apps.** Single shared sci context
  with launcher-owned teardown of the previous app's DOM/atoms, or a
  fresh context per app launch (which loses any "open multiple apps
  side-by-side" possibility)?
- **Where the launcher itself lives.** Is `app.launcher` just another
  row in `ns_modules` (consistent, eats own dogfood, but bootstrap has
  to handle "launcher row missing/broken"), or hardcoded in the
  static shell (simpler, but the launcher cannot be edited from
  inside the IDE)?
- **Plugin manifest shape.** Static JSON in Storage (simple, immutable
  per deploy), or a `plugins` table in Postgres (queryable, RLS-able,
  but adds a round-trip to boot)?
- **Eager vs. lazy plugin loading default.** Eager-load all plugins in
  the shell (simplest, biggest cold-start cost), lazy-load per app
  (best fit for launcher pattern, requires the manifest), or hybrid
  (eager-load a "core set" of Reagent + Promesa, lazy for the rest)?
- **Custom Scittle build delivery.** If the lazy-resolve `sci.async`
  branch in §4.5 is taken, does the custom build live in Storage as a
  drop-in replacement for `scittle.js` (loadable per-app via the
  manifest, so different apps can use different Scittle builds), or
  is it the project-wide default in the shell?
