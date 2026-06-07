#!/usr/bin/env bash
#
# test-ask-engine.sh — smoke-test AuraMath's Ask engine against its registered databases.
#
# AuraMath reads the target databases from ~/config.secrets at startup (see
# scripts/config.secrets.example), so this script sends NO credentials — it just
# drives the API:
#   1. GET  /api/ask/databases          (list the registered databases, no creds)
#   2. POST /api/ask {question[, databases]}   (ask, fanning out + collating across DBs)
#   3. GET  /api/ask/admin/metrics      (counters after the run)
#
# Usage:
#   ./scripts/test-ask-engine.sh "compare total revenue in orders vs amounts billed"
#   DATABASES=orders,billing ./scripts/test-ask-engine.sh "..."   # restrict to a subset
#
# Env vars (all optional):
#   AURA_BASE   AuraMath base URL    (default: http://localhost:8081)
#   QUESTION    question to ask      (default: a generic count question; or pass as $1)
#   DATABASES   comma-separated subset of registered DB names (default: all registered)
#   MODEL       model id override    (default: server default)
#   MAX_ROWS    per-query row cap    (default: server default)
#
# Prerequ: AuraMath running, ~/config.secrets populated, and (for /api/ask) the
# Anthropic key set in src/main/resources/secrets.txt. Step 1 needs no API key.
#
set -euo pipefail

AURA_BASE="${AURA_BASE:-http://localhost:8081}"
QUESTION="${1:-${QUESTION:-How many rows are in the largest table of each database?}}"

have_jq() { command -v jq >/dev/null 2>&1; }
pretty()  { if have_jq; then jq .; else cat; fi; }

# Build a JSON string array from a comma-separated list (empty -> []).
json_array() {
  local IFS=','; local out=""
  for item in ${1:-}; do
    item="$(echo "$item" | xargs)"
    [ -z "$item" ] && continue
    out="${out:+$out,}\"$item\""
  done
  echo "[$out]"
}

echo "==> AuraMath:  $AURA_BASE"
echo "==> Question:  $QUESTION"
[ -n "${DATABASES:-}" ] && echo "==> Databases: $DATABASES" || echo "==> Databases: (all registered)"
echo

# ---- 1. list registered databases -----------------------------------------
echo "===================================================================="
echo "1) GET /api/ask/databases"
echo "===================================================================="
DBS_RESP=$(curl -sS "${AURA_BASE}/api/ask/databases")
echo "$DBS_RESP" | pretty
if have_jq && [ "$(echo "$DBS_RESP" | jq 'length')" = "0" ]; then
  echo
  echo "No databases registered. Populate ~/config.secrets (see scripts/config.secrets.example) and restart." >&2
  exit 1
fi
echo

# ---- 2. ask --------------------------------------------------------------
echo "===================================================================="
echo "2) POST /api/ask"
echo "===================================================================="
# Assemble the request body with only the fields that are set.
BODY="{\"question\": $(printf '%s' "$QUESTION" | (have_jq && jq -Rs . || printf '"%s"' "$QUESTION"))"
[ -n "${DATABASES:-}" ] && BODY="$BODY, \"databases\": $(json_array "$DATABASES")"
[ -n "${MODEL:-}" ]     && BODY="$BODY, \"model\": \"$MODEL\""
[ -n "${MAX_ROWS:-}" ]  && BODY="$BODY, \"maxRows\": $MAX_ROWS"
BODY="$BODY}"

ASK_RESP=$(curl -sS -w $'\n%{http_code}' -X POST "${AURA_BASE}/api/ask" \
  -H 'Content-Type: application/json' -d "$BODY")
ASK_CODE="${ASK_RESP##*$'\n'}"
ASK_BODY="${ASK_RESP%$'\n'*}"

echo "HTTP $ASK_CODE"
echo "$ASK_BODY" | pretty
echo
if have_jq && [ "$ASK_CODE" = "200" ]; then
  echo "-- answer --";          echo "$ASK_BODY" | jq -r '.answer // "(no answer)"'; echo
  echo "-- per-database SQL --"; echo "$ASK_BODY" | jq -r '.subQueries[]? | "[\(.database)] \(.sql)"'; echo
elif have_jq && [ "$ASK_CODE" = "400" ]; then
  # A clarification: show the question and the specific data the engine says is missing.
  echo "-- clarification --"; echo "$ASK_BODY" | jq -r '.clarificationQuestion // "(none)"'
  echo "-- missing data --";  echo "$ASK_BODY" | jq -r '(.missingData // [])[]? | "- \(.)"'; echo
fi

# ---- 3. metrics ----------------------------------------------------------
echo "===================================================================="
echo "3) GET /api/ask/admin/metrics"
echo "===================================================================="
curl -sS "${AURA_BASE}/api/ask/admin/metrics" | pretty
echo

echo "Done."
