#!/usr/bin/env bash
# Medication order scenario against a running EHRbase:
#   1. upload the CKM-based medication_order template
#   2. create patients (EHRs with subject id), write / read / update compositions, check versions
#   3. AQL queries by patient, by template, by medication
#   4. print approximate response times (orientation only, not a performance test)
#
# Usage (from repo root):
#   Git Bash / IntelliJ terminal with Git Bash:  scripts/medication-scenario.sh [runs]
#   PowerShell:  & "C:\Program Files\Git\bin\bash.exe" scripts/medication-scenario.sh [runs]
#   (plain "bash" in PowerShell starts WSL, not Git Bash)
#   runs = repetitions for read / AQL timings (default 10)
# Env overrides: BASE, AUTH
# Re-runnable: existing template / patients are reused, new compositions are added each run.

set -euo pipefail

BASE=${BASE:-http://localhost:8080/ehrbase}
AUTH=${AUTH:-ehrbase-user:SuperSecretPassword}
RUNS=${1:-10}
API=$BASE/rest/openehr/v1
ROOT=$(cd "$(dirname "$0")/.." && pwd)
COMP=$ROOT/requests/compositions
TEMPLATE=$ROOT/src/main/resources/templates/medication_order.opt
TEMPLATE_ID=medication_order
NS=patients

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
TIMES=$TMP/times

# ---------- helpers ----------
# call LABEL curl-args... ; sets CODE, MS; body in $TMP/body, headers in $TMP/headers
call() {
  local label=$1; shift
  local out
  out=$(curl -s -u "$AUTH" -o "$TMP/body" -D "$TMP/headers" -w '%{http_code} %{time_total}' "$@")
  CODE=${out% *}
  MS=$(awk -v t="${out#* }" 'BEGIN{printf "%.0f", t*1000}')
  echo "$label|$MS" >> "$TIMES"
}
expect() { # expect CODE... ; fail with body otherwise
  for c in "$@"; do [[ $CODE == "$c" ]] && return 0; done
  echo "  !! unexpected HTTP $CODE (expected $*)"; cat "$TMP/body"; echo; exit 1
}
header() { tr -d '\r' < "$TMP/headers" | grep -i "^$1:" | head -1 | sed -E 's/^[^:]+: *//; s/"//g'; }
body() { cat "$TMP/body"; }
aql_body() { # aql_body QUERY [param value]...
  local q=$1; shift; local params=""
  while (( $# )); do params+="${params:+,}\"$1\":\"$2\""; shift 2; done
  printf '{"q":"%s","query_parameters":{%s}}' "$q" "$params"
}
aql() { # aql LABEL QUERY [param value]... ; runs RUNS times, keeps last result
  local label=$1; shift
  local b; b=$(aql_body "$@")
  for _ in $(seq "$RUNS"); do
    call "$label" -X POST -H 'Content-Type: application/json' -H 'Accept: application/json' -d "$b" "$API/query/aql"
    expect 200
  done
  body | grep -oE '"rows":.*' | sed -E 's/\}$//'
}
step() { echo; echo "== $*"; }

# ---------- 1. template ----------
step "1. Upload template $TEMPLATE_ID"
call "upload template" -X POST -H 'Content-Type: application/xml' --data-binary @"$TEMPLATE" "$API/definition/template/adl1.4"
expect 201 409
[[ $CODE == 409 ]] && echo "  already uploaded (409), reusing" || echo "  uploaded (201) in ${MS} ms"

# ---------- 2. patients ----------
ehr_for() { # ehr_for SUBJECT_ID -> prints ehr_id (creates EHR if missing)
  local sid=$1
  call "get EHR by subject" -H 'Accept: application/json' "$API/ehr?subject_id=$sid&subject_namespace=$NS"
  if [[ $CODE == 200 ]]; then
    body | grep -A3 '"ehr_id"' | grep -oE '[0-9a-f-]{36}' | head -1; return
  fi
  local status
  status=$(printf '{"_type":"EHR_STATUS","archetype_node_id":"openEHR-EHR-EHR_STATUS.generic.v1","name":{"value":"EHR Status"},"subject":{"_type":"PARTY_SELF","external_ref":{"id":{"_type":"GENERIC_ID","value":"%s","scheme":"id_scheme"},"namespace":"%s","type":"PERSON"}},"is_queryable":true,"is_modifiable":true}' "$sid" "$NS")
  call "create EHR" -X POST -H 'Content-Type: application/json' -H 'Accept: application/json' -H 'Prefer: return=representation' -d "$status" "$API/ehr"
  expect 201
  body | grep -A3 '"ehr_id"' | grep -oE '[0-9a-f-]{36}' | head -1
}

step "2. Patients (EHR per subject id, namespace '$NS')"
P1=$(ehr_for LT-PAC-001); echo "  LT-PAC-001 -> $P1"
P2=$(ehr_for LT-PAC-002); echo "  LT-PAC-002 -> $P2"
P3=$(ehr_for LT-PAC-003); echo "  LT-PAC-003 -> $P3"

post() { # post EHR FILE -> prints composition version uid (uuid::system::1)
  call "create composition" -X POST -H 'Content-Type: application/json' -H 'Prefer: return=minimal' \
    --data-binary @"$2" "$API/ehr/$1/composition?templateId=$TEMPLATE_ID&format=FLAT"
  expect 201 204
  header ETag
}

step "3. Write compositions"
UID1=$(post "$P1" "$COMP/medication_order_metformin.flat.json");  echo "  LT-PAC-001 metformin  -> $UID1"
UID2=$(post "$P2" "$COMP/medication_order_lisinopril.flat.json"); echo "  LT-PAC-002 lisinopril -> $UID2"
UID3=$(post "$P3" "$COMP/medication_order_metformin.flat.json");  echo "  LT-PAC-003 metformin  -> $UID3"
UID4=$(post "$P3" "$COMP/medication_order_lisinopril.flat.json"); echo "  LT-PAC-003 lisinopril -> $UID4"
VOID=${UID1%%::*}

step "4. Read composition $UID1 (FLAT), $RUNS times"
for _ in $(seq "$RUNS"); do
  call "read composition (FLAT)" -H 'Accept: application/json' "$API/ehr/$P1/composition/$VOID?format=FLAT"
  expect 200
done
echo "  strength: $(body | grep -oE '"[^"]*strength_concentration\|magnitude" *: *[0-9.]+' | sed -E 's/.*: *//') mg"

step "5. Update composition: metformin 500 mg -> 1000 mg"
sed -E 's/"Metformin 500 mg tablet"/"Metformin 1000 mg tablet"/; s/(strength_concentration\|magnitude": )500/\11000/; s/Metformin 500 mg orally/Metformin 1000 mg orally/' \
  "$COMP/medication_order_metformin.flat.json" > "$TMP/metformin_v2.json"
call "update composition" -X PUT -H 'Content-Type: application/json' -H 'Prefer: return=minimal' -H "If-Match: $UID1" \
  --data-binary @"$TMP/metformin_v2.json" "$API/ehr/$P1/composition/$VOID?templateId=$TEMPLATE_ID&format=FLAT"
expect 200 204
NEW_UID=$(header ETag); echo "  new version: $NEW_UID"

step "6. Versions"
call "revision history" -H 'Accept: application/json' "$API/ehr/$P1/versioned_composition/$VOID/revision_history"
expect 200
echo "  revision history: $(body | grep -oE '[0-9a-f-]{36}::[^"]+::[0-9]+' | sort -u | tr '\n' ' ')"
for v in 1 2; do
  call "read specific version" -H 'Accept: application/json' "$API/ehr/$P1/composition/$VOID::$(echo "$UID1" | cut -d: -f3)::$v?format=FLAT"
  expect 200
  echo "  version $v strength: $(body | grep -oE '"[^"]*strength_concentration\|magnitude" *: *[0-9.]+' | sed -E 's/.*: *//') mg"
done
call "read latest" -H 'Accept: application/json' "$API/ehr/$P1/composition/$VOID?format=FLAT"; expect 200
echo "  latest    strength: $(body | grep -oE '"[^"]*strength_concentration\|magnitude" *: *[0-9.]+' | sed -E 's/.*: *//') mg"
call "stale update rejected" -X PUT -H 'Content-Type: application/json' -H 'Prefer: return=minimal' -H "If-Match: $UID1" \
  --data-binary @"$TMP/metformin_v2.json" "$API/ehr/$P1/composition/$VOID?templateId=$TEMPLATE_ID&format=FLAT"
expect 412
echo "  update with stale If-Match (version 1) -> HTTP $CODE (optimistic locking works)"

MED="m/activities[at0001]/description[at0002]/items"
step "7. AQL by patient (LT-PAC-003), $RUNS runs"
aql "AQL by patient" "SELECT c/context/start_time/value AS ordered, $MED[at0070]/value/value AS medication, $MED[at0009]/value/value AS directions FROM EHR e CONTAINS COMPOSITION c CONTAINS INSTRUCTION m[openEHR-EHR-INSTRUCTION.medication_order.v3] WHERE e/ehr_status/subject/external_ref/id/value = \$patient AND e/ehr_status/subject/external_ref/namespace = '$NS' ORDER BY c/context/start_time/value" patient LT-PAC-003

step "8. AQL by template ($TEMPLATE_ID): compositions per patient, $RUNS runs"
aql "AQL by template" "SELECT e/ehr_status/subject/external_ref/id/value AS patient, COUNT(c/uid/value) AS orders FROM EHR e CONTAINS COMPOSITION c WHERE c/archetype_details/template_id/value = \$template" template "$TEMPLATE_ID"

step "9. AQL by medication (ATC A10BA02 metformin, latest versions), $RUNS runs"
aql "AQL by medication" "SELECT e/ehr_status/subject/external_ref/id/value AS patient, $MED[at0070]/value/value AS medication, c/uid/value AS composition FROM EHR e CONTAINS COMPOSITION c CONTAINS INSTRUCTION m[openEHR-EHR-INSTRUCTION.medication_order.v3] WHERE $MED[at0070]/value/defining_code/code_string = \$atc" atc A10BA02

# ---------- timings ----------
step "Response times (ms) - $BASE"
echo
printf '| %-24s | %3s | %6s | %6s | %6s | %6s |\n' operation n min median avg max
printf '|%s|%s|%s|%s|%s|%s|\n' "--------------------------" "-----" "--------" "--------" "--------" "--------"
cut -d'|' -f1 "$TIMES" | awk '!seen[$0]++' | while read -r label; do
  grep -F "$label|" "$TIMES" | cut -d'|' -f2 | sort -n | awk -v l="$label" '
    { v[NR]=$1; s+=$1 }
    END { med = (NR%2) ? v[(NR+1)/2] : (v[NR/2]+v[NR/2+1])/2
          printf "| %-24s | %3d | %6d | %6.0f | %6.0f | %6d |\n", l, NR, v[1], med, s/NR, v[NR] }'
done
