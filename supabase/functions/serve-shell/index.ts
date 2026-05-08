// Phase 2 shell server.
//
// HONEST CAVEAT: on the default *.supabase.co domain, Supabase wraps
// any HTML response from an Edge Function with
//
//     Content-Type: text/plain
//     Content-Security-Policy: default-src 'none'; sandbox
//     X-Content-Type-Options: nosniff
//
// regardless of the Content-Type this function sets. Browsers honor
// the sandbox CSP and refuse to execute any <script> inside the
// document. This is intentional platform behavior — Supabase prevents
// arbitrary HTML hosting on its own domain to limit abuse vectors
// (open-redirect/XSS staging on a trusted-looking URL).
//
// The design doc §4.1 anticipates this and notes that it "works
// seamlessly behind a custom domain" (Pro+). Without a custom domain,
// the static shell needs to live somewhere off *.supabase.co
// (Cloudflare Pages, GitHub Pages, etc.) — see Phase 2.5 in
// docs/scittle-on-supabase.md.
//
// This function is kept in place because:
//  - It is the right shape once a custom domain is attached.
//  - It documents the attempted approach for future readers.
//  - It is deployed with verify_jwt = false (config.toml) so a custom
//    domain can route here without auth surprises.

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SUPABASE_ANON_KEY = Deno.env.get("SUPABASE_ANON_KEY") ?? "";
const ROOT_NAMESPACE = Deno.env.get("SHELL_ROOT_NAMESPACE") ?? "app.main";

const html = `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width,initial-scale=1" />
  <title>Scittle on Supabase</title>
  <style>
    body { font-family: system-ui, sans-serif; margin: 2rem auto; max-width: 720px; padding: 0 1rem; line-height: 1.5; color: #222; }
    #app { min-height: 6rem; padding: 1.5rem; border: 1px dashed #ccc; border-radius: 6px; background: #fafafa; }
    .err { color: #b00020; font-family: ui-monospace, monospace; white-space: pre-wrap; }
    button { font: inherit; padding: 0.4rem 0.8rem; border-radius: 4px; border: 1px solid #888; background: #f4f4f4; cursor: pointer; }
    button:hover { background: #eaeaea; }
    code { background: #f0f0f0; padding: 0.05rem 0.3rem; border-radius: 3px; font-size: 0.9em; }
  </style>
</head>
<body>
  <h1>Scittle on Supabase</h1>
  <p style="color:#666;font-size:0.9rem">App loaded dynamically from <code>ns_modules</code>; Scittle bundle and plugins served from <code>libs/</code> Storage bucket.</p>
  <div id="app">Booting…</div>

  <script>
    window.__SUPA_CONFIG__ = {
      url: ${JSON.stringify(SUPABASE_URL)},
      anonKey: ${JSON.stringify(SUPABASE_ANON_KEY)},
      rootNamespace: ${JSON.stringify(ROOT_NAMESPACE)}
    };
  </script>

  <script src="${SUPABASE_URL}/storage/v1/object/public/libs/scittle-0.8.31.js"></script>
  <script src="${SUPABASE_URL}/storage/v1/object/public/libs/supabase-js-2.45.4.js"></script>
  <script src="${SUPABASE_URL}/storage/v1/object/public/shell/boot.js"></script>
</body>
</html>`;

Deno.serve(() => {
  return new Response(html, {
    status: 200,
    headers: {
      "Content-Type": "text/html; charset=utf-8",
      "Cache-Control": "no-cache",
    },
  });
});
