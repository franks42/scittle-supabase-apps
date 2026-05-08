#!/usr/bin/env bash
# Phase 2: upload boot.js to the 'shell' public bucket.
# The shell HTML itself is served by the serve-shell Edge Function
# (see infra/deploy-functions.sh) because Supabase's public Storage
# route forces text/plain on HTML payloads. boot.js, served as JS,
# is not affected by that override.
# Idempotent (uses x-upsert).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

# shellcheck source=/dev/null
set -a; . ./.env; . ./.env.local; set +a

: "${SUPABASE_URL:?SUPABASE_URL not set (check .env.local)}"
: "${SUPABASE_ANON_KEY:?SUPABASE_ANON_KEY not set (check .env.local)}"
: "${SUPABASE_SECRET_KEY:?SUPABASE_SECRET_KEY not set (check .env)}"

echo "==> Ensuring 'shell' public bucket exists"
http_code=$(curl -sS -X POST "$SUPABASE_URL/storage/v1/bucket" \
  -H "apikey: $SUPABASE_SECRET_KEY" \
  -H "Authorization: Bearer $SUPABASE_SECRET_KEY" \
  -H "Content-Type: application/json" \
  -d '{"id":"shell","name":"shell","public":true}' \
  -o /tmp/bucket-resp.json -w '%{http_code}')
if [ "$http_code" = "200" ]; then
  echo "  bucket created"
elif grep -q "already exists" /tmp/bucket-resp.json 2>/dev/null; then
  echo "  bucket already exists"
else
  echo "  unexpected response (HTTP $http_code):"
  cat /tmp/bucket-resp.json
  exit 1
fi

upload () {
  local file="$1"
  local target="$2"
  local content_type="$3"
  curl -fsS -X POST "$SUPABASE_URL/storage/v1/object/shell/$target" \
    -H "apikey: $SUPABASE_SECRET_KEY" \
    -H "Authorization: Bearer $SUPABASE_SECRET_KEY" \
    -H "Content-Type: $content_type" \
    -H "Cache-Control: no-cache" \
    -H "x-upsert: true" \
    --data-binary "@$file" \
    -o /dev/null \
    -w "  POST $target -> HTTP %{http_code}\n"
}

echo "==> Uploading boot.js + boot.cljs to shell/ bucket"
upload "supabase/storage/shell/boot.js"   "boot.js"   "application/javascript"
upload "supabase/storage/shell/boot.cljs" "boot.cljs" "text/plain; charset=utf-8"

echo "==> Building dist/shell.html (substituted, ready for external static host)"
mkdir -p dist
sed -e "s#__SUPABASE_URL__#${SUPABASE_URL}#g" \
    -e "s#__SUPABASE_ANON_KEY__#${SUPABASE_ANON_KEY}#g" \
    supabase/storage/shell/shell.html > dist/shell.html
echo "  $(pwd)/dist/shell.html ($(wc -c < dist/shell.html) bytes)"

echo
echo "==> Done."
echo "On free tier, host dist/shell.html on GitHub Pages / Cloudflare Pages /"
echo "Netlify / Vercel — Supabase blocks HTML on the *.supabase.co domain."
echo "See §4.1 of docs/scittle-on-supabase.md for the platform behavior."
