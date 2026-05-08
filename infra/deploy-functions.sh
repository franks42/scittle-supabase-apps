#!/usr/bin/env bash
# Deploy all Edge Functions defined under supabase/functions/.
# Per-function JWT verification is configured in supabase/config.toml.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

if ! command -v supabase >/dev/null 2>&1; then
  echo "supabase CLI not found on PATH" >&2
  exit 1
fi

# Functions are configured in supabase/config.toml. The link state lives
# under supabase/.temp/. The CLI requires a non-interactive flag for
# automation: --use-api avoids the Docker-based deploy path.
echo "==> Deploying functions"
for fn_dir in supabase/functions/*/; do
  fn="$(basename "$fn_dir")"
  if [ -f "$fn_dir/index.ts" ]; then
    echo "  - $fn"
    supabase functions deploy "$fn" --use-api
  fi
done

echo "==> Done."
