// Phase 4 live-proxy demo.
//
// Browser-callable Edge Function that fetches a fresh cat fact from
// catfact.ninja and returns it. The URL is hardcoded (no SSRF surface);
// CORS headers are set so cljs in app.facts can call this via
// supabase.functions.invoke('cat-fact-proxy').
//
// verify_jwt = true (config.toml). Anonymous JWTs satisfy that — the
// shell's auth flow obtains one on first save, and most visitors will
// already be signed in by the time they click the proxy button.

const CORS_HEADERS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers":
    "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
};

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response(null, { headers: CORS_HEADERS });
  }

  try {
    const upstream = await fetch("https://catfact.ninja/fact", {
      headers: { "Accept": "application/json" },
    });
    const body = await upstream.text();
    return new Response(body, {
      status: upstream.status,
      headers: {
        ...CORS_HEADERS,
        "Content-Type":
          upstream.headers.get("Content-Type") ?? "application/json",
        "Cache-Control": "no-store",
      },
    });
  } catch (e) {
    const message = e instanceof Error ? e.message : String(e);
    return new Response(JSON.stringify({ error: message }), {
      status: 502,
      headers: { ...CORS_HEADERS, "Content-Type": "application/json" },
    });
  }
});
