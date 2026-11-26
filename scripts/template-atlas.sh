#!/usr/bin/env bash
# Generates docs/template-atlas.html: an interactive, human-readable view of the templates on a running EHRbase
# (structure, required fields, allowed values, FLAT / AQL paths) with values from the sample FLAT compositions
# and composition counts per template.
#
# Sources: scripts/template-atlas/page.html (layout, styles) + scripts/template-atlas/atlas.js (rendering).
# Data: web templates (GET /definition/template/adl1.4/{id}, Accept: application/openehr.wt+json),
#       AQL counts, requests/compositions/*.flat.json. All embedded, so the output is one self-contained file.
#
# Usage (from repo root, stack running):
#   Git Bash:    scripts/template-atlas.sh [output]        (default output: docs/template-atlas.html)
#   PowerShell:  & "C:\Program Files\Git\bin\bash.exe" scripts/template-atlas.sh
# Env overrides: BASE, AUTH
# To add a template: upload it, add a sample FLAT composition and a line to TEMPLATES below.

set -euo pipefail

BASE=${BASE:-http://localhost:8080/ehrbase}
AUTH=${AUTH:-ehrbase-user:SuperSecretPassword}
API=$BASE/rest/openehr/v1
ROOT=$(cd "$(dirname "$0")/.." && pwd)
SRC=$ROOT/scripts/template-atlas
COMP=$ROOT/requests/compositions
OUT=${1:-$ROOT/docs/template-atlas.html}

# key | template id | sample FLAT composition (requests/compositions) | one-line description (no double quotes)
TEMPLATES=(
  "vitals|minimal_vital_signs.v0|vital_signs.flat.json|A nurse records blood pressure, pulse, temperature and weight. Each observation holds one or more timed events."
  "diagnosis|minimal_diagnosis.v0|diagnosis.flat.json|A doctor records one or more diagnoses, each coded in ICD-10, with onset date and severity."
  "medication|medication_order|medication_order_lisinopril.flat.json|A doctor prescribes medication. The medication item is coded in ATC; dose, route, timing and order dates follow."
)

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

# aql_count QUERY -> prints the single number of a COUNT result
aql_count() {
  local body
  body=$(curl -sf -u "$AUTH" -H 'Content-Type: application/json' -X POST "$API/query/aql" -d "{\"q\":\"$1\"}") \
    || { echo "AQL failed: $1" >&2; exit 1; }
  echo "$body" | grep -o '"rows":\[\[[0-9]*' | grep -o '[0-9]*$' || echo 0
}

echo "Fetching from $API"
EHRS=$(aql_count "SELECT COUNT(e/ehr_id/value) FROM EHR e")
DATA_TEMPLATES=""
for line in "${TEMPLATES[@]}"; do
  IFS='|' read -r key id file blurb <<< "$line"
  [ -f "$COMP/$file" ] || { echo "Missing sample composition: $COMP/$file" >&2; exit 1; }
  code=$(curl -s -u "$AUTH" -H 'Accept: application/openehr.wt+json' -o "$TMP/wt-$key.json" -w '%{http_code}' \
    "$API/definition/template/adl1.4/$id")
  [ "$code" = 200 ] || { echo "Template $id: HTTP $code (uploaded? stack running?)" >&2; exit 1; }
  count=$(aql_count "SELECT COUNT(e/ehr_id/value) FROM EHR e CONTAINS COMPOSITION c WHERE c/archetype_details/template_id/value = '$id'")
  printf '  %-24s %5s compositions\n' "$id" "$count"
  DATA_TEMPLATES+="${DATA_TEMPLATES:+,}{\"key\":\"$key\",\"templateId\":\"$id\",\"file\":\"$file\",\"count\":$count,\"blurb\":\"$blurb\"}"
done
echo "  EHRs: $EHRS"

embed() { printf '<script type="application/json" id="%s">' "$1"; cat "$2"; printf '</script>\n'; }

mkdir -p "$(dirname "$OUT")"
{
  echo '<!doctype html>'
  echo '<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover"></head><body>'
  cat "$SRC/page.html"
  printf '<script type="application/json" id="atlas-data">{"generated":"%s","ehrs":%s,"templates":[%s]}</script>\n' \
    "$(date +%Y-%m-%d)" "$EHRS" "$DATA_TEMPLATES"
  for line in "${TEMPLATES[@]}"; do
    IFS='|' read -r key id file blurb <<< "$line"
    embed "wt-$key" "$TMP/wt-$key.json"
    embed "ex-$key" "$COMP/$file"
  done
  printf '<script>\n'; cat "$SRC/atlas.js"; printf '</script>\n'
  echo '</body></html>'
} > "$OUT"

echo "Wrote $OUT ($(wc -c < "$OUT") bytes)"
