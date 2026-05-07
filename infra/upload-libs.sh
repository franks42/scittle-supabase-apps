#!/usr/bin/env bash
# Phase 1.5 reproducer: download Scittle + supabase-js bundles from jsdelivr,
# upload them to the project's `libs` Storage bucket, then upload the plugin
# manifest. Idempotent: re-running overwrites with `x-upsert: true`.
#
# Requires:
#   - .env at repo root with SUPABASE_SECRET_KEY (gitignored).
#   - .env.local at repo root with SUPABASE_URL.
#   - The `libs` public bucket already exists on the project.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

# shellcheck source=/dev/null
set -a; . ./.env; . ./.env.local; set +a

: "${SUPABASE_URL:?SUPABASE_URL not set (check .env.local)}"
: "${SUPABASE_SECRET_KEY:?SUPABASE_SECRET_KEY not set (check .env)}"

SCITTLE_VERSION="0.8.31"
SUPABASE_JS_VERSION="2.45.4"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

declare -a BUNDLES=(
  "scittle-${SCITTLE_VERSION}.js|https://cdn.jsdelivr.net/npm/scittle@${SCITTLE_VERSION}/dist/scittle.js"
  "scittle-${SCITTLE_VERSION}-reagent.js|https://cdn.jsdelivr.net/npm/scittle@${SCITTLE_VERSION}/dist/scittle.reagent.js"
  "scittle-${SCITTLE_VERSION}-promesa.js|https://cdn.jsdelivr.net/npm/scittle@${SCITTLE_VERSION}/dist/scittle.promesa.js"
  "scittle-${SCITTLE_VERSION}-cljs-ajax.js|https://cdn.jsdelivr.net/npm/scittle@${SCITTLE_VERSION}/dist/scittle.cljs-ajax.js"
  "scittle-${SCITTLE_VERSION}-nrepl.js|https://cdn.jsdelivr.net/npm/scittle@${SCITTLE_VERSION}/dist/scittle.nrepl.js"
  "supabase-js-${SUPABASE_JS_VERSION}.js|https://cdn.jsdelivr.net/npm/@supabase/supabase-js@${SUPABASE_JS_VERSION}/dist/umd/supabase.js"
)

echo "==> Downloading bundles to $STAGE"
for entry in "${BUNDLES[@]}"; do
  name="${entry%%|*}"
  url="${entry##*|}"
  curl -fsSL -o "$STAGE/$name" "$url"
  printf "  %-48s %8d bytes\n" "$name" "$(wc -c < "$STAGE/$name")"
done

upload () {
  local file="$1"
  local target="$2"
  local content_type="$3"
  curl -fsS -X POST "$SUPABASE_URL/storage/v1/object/libs/$target" \
    -H "apikey: $SUPABASE_SECRET_KEY" \
    -H "Authorization: Bearer $SUPABASE_SECRET_KEY" \
    -H "Content-Type: $content_type" \
    -H "Cache-Control: public, max-age=31536000, immutable" \
    -H "x-upsert: true" \
    --data-binary "@$file" \
    -o /dev/null \
    -w "  POST $target -> HTTP %{http_code}\n"
}

echo "==> Uploading bundles to libs/ bucket"
for entry in "${BUNDLES[@]}"; do
  name="${entry%%|*}"
  upload "$STAGE/$name" "$name" "application/javascript"
done

echo "==> Uploading plugin manifest"
upload "$REPO_ROOT/supabase/storage/libs/plugin-manifest.json" \
       "plugin-manifest.json" \
       "application/json"

echo "==> Done. Verify with:"
echo "  curl -sI \"$SUPABASE_URL/storage/v1/object/public/libs/plugin-manifest.json\""
