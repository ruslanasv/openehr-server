# openEHR REST API – curl examples (EHRbase)

Run in **Git Bash** from the repo root (on Windows plain `bash` in PowerShell starts WSL – use Git Bash).
The blocks build on each other: ids returned by one call are stored in shell variables for the next.
The same requests as a Postman collection: `postman/`. Spec: https://specifications.openehr.org/releases/ITS-REST/latest

## Setup

```bash
BASE=http://localhost:8080/ehrbase/rest/openehr/v1
AUTH="ehrbase-user:SuperSecretPassword"
c() { curl -s -u "$AUTH" "$@"; echo; }          # print body
etag() { grep -i '^etag:' | tr -d '\r"' | sed 's/^[Ee][Tt][Aa][Gg]: *//'; }   # read ETag from headers
```

## Server status

```bash
curl -s -u "$AUTH" -H 'Accept: application/json' http://localhost:8080/ehrbase/rest/status
```

## EHR

Create an EHR for a patient (subject id in namespace `patients`):

```bash
PATIENT=CURL-$(date +%s)
EHR_ID=$(curl -s -u "$AUTH" -X POST "$BASE/ehr" \
  -H 'Content-Type: application/json' -H 'Accept: application/json' -H 'Prefer: return=representation' \
  -d '{
    "_type": "EHR_STATUS",
    "archetype_node_id": "openEHR-EHR-EHR_STATUS.generic.v1",
    "name": {"value": "EHR Status"},
    "subject": {"_type": "PARTY_SELF", "external_ref": {
      "id": {"_type": "GENERIC_ID", "value": "'"$PATIENT"'", "scheme": "id_scheme"},
      "namespace": "patients", "type": "PERSON"}},
    "is_queryable": true, "is_modifiable": true
  }' | grep -A3 '"ehr_id"' | grep -oE '[0-9a-f-]{36}' | head -1)
echo "patient $PATIENT -> EHR $EHR_ID"
```

Create an EHR without a subject (server generates everything): `curl -s -u "$AUTH" -X POST "$BASE/ehr" -H 'Prefer: return=representation'`

Read the EHR by id, by subject, and its EHR_STATUS:

```bash
c -H 'Accept: application/json' "$BASE/ehr/$EHR_ID"
c -H 'Accept: application/json' "$BASE/ehr?subject_id=$PATIENT&subject_namespace=patients"
c -H 'Accept: application/json' "$BASE/ehr/$EHR_ID/ehr_status"
```

## Templates

Upload (201 created, 409 if it already exists), list, web template (all FLAT paths), example composition:

```bash
c -X POST "$BASE/definition/template/adl1.4" -H 'Content-Type: application/xml' \
  --data-binary @src/main/resources/templates/medication_order.opt -o /dev/null -w 'upload: HTTP %{http_code}'
c -H 'Accept: application/json' "$BASE/definition/template/adl1.4"
c -H 'Accept: application/openehr.wt+json' "$BASE/definition/template/adl1.4/medication_order" | head -c 300
c -H 'Accept: application/json' "$BASE/definition/template/adl1.4/medication_order/example?format=FLAT" | head -20
```

## Composition: create, read, update, versions, delete

Create (FLAT format). The response ETag is the version uid `uuid::system::1`:

```bash
UID_V1=$(curl -s -u "$AUTH" -o /dev/null -D - -X POST \
  "$BASE/ehr/$EHR_ID/composition?templateId=medication_order&format=FLAT" \
  -H 'Content-Type: application/json' -H 'Prefer: return=minimal' \
  --data-binary @requests/compositions/medication_order_metformin.flat.json | etag)
COMP_ID=${UID_V1%%::*}
echo "version uid $UID_V1, composition id $COMP_ID"
```

Read latest version as FLAT, canonical JSON, canonical XML:

```bash
c -H 'Accept: application/json' "$BASE/ehr/$EHR_ID/composition/$COMP_ID?format=FLAT" | grep strength_concentration
c -H 'Accept: application/json' "$BASE/ehr/$EHR_ID/composition/$COMP_ID" | head -c 300
c -H 'Accept: application/xml'  "$BASE/ehr/$EHR_ID/composition/$COMP_ID" | head -c 300
```

Update: `If-Match` must carry the current version uid (optimistic locking) – creates version 2:

```bash
sed -e 's/"Metformin 500 mg tablet"/"Metformin 1000 mg tablet"/' \
    -e 's/\(strength_concentration|magnitude": \)500/\11000/' \
    requests/compositions/medication_order_metformin.flat.json > /tmp/metformin_v2.json
UID_V2=$(curl -s -u "$AUTH" -o /dev/null -D - -X PUT \
  "$BASE/ehr/$EHR_ID/composition/$COMP_ID?templateId=medication_order&format=FLAT" \
  -H 'Content-Type: application/json' -H 'Prefer: return=minimal' -H "If-Match: $UID_V1" \
  --data-binary @/tmp/metformin_v2.json | etag)
echo "new version $UID_V2"
```

Versions: revision history, a specific version, and a stale update (rejected with **412**):

```bash
c -H 'Accept: application/json' "$BASE/ehr/$EHR_ID/versioned_composition/$COMP_ID/revision_history" | grep -oE '[0-9a-f-]{36}::[^"]+'
c -H 'Accept: application/json' "$BASE/ehr/$EHR_ID/composition/$UID_V1?format=FLAT" | grep strength_concentration
c -H 'Accept: application/json' "$BASE/ehr/$EHR_ID/composition/$UID_V2?format=FLAT" | grep strength_concentration
c -o /dev/null -w 'stale update: HTTP %{http_code}' -X PUT \
  "$BASE/ehr/$EHR_ID/composition/$COMP_ID?templateId=medication_order&format=FLAT" \
  -H 'Content-Type: application/json' -H "If-Match: $UID_V1" --data-binary @/tmp/metformin_v2.json
```

Delete (logical – takes the current version uid; history is kept, the composition disappears from AQL):

```bash
LIS_UID=$(curl -s -u "$AUTH" -o /dev/null -D - -X POST \
  "$BASE/ehr/$EHR_ID/composition?templateId=medication_order&format=FLAT" \
  -H 'Content-Type: application/json' -H 'Prefer: return=minimal' \
  --data-binary @requests/compositions/medication_order_lisinopril.flat.json | etag)
c -o /dev/null -w 'delete: HTTP %{http_code}' -X DELETE "$BASE/ehr/$EHR_ID/composition/$LIS_UID"
c -o /dev/null -w 'read deleted: HTTP %{http_code}' "$BASE/ehr/$EHR_ID/composition/${LIS_UID%%::*}"
```

## AQL

POST with parameters (`$name` in the query, values in `query_parameters`):

```bash
# by patient
c -X POST "$BASE/query/aql" -H 'Content-Type: application/json' -H 'Accept: application/json' -d '{
  "q": "SELECT c/context/start_time/value AS ordered, m/activities[at0001]/description[at0002]/items[at0070]/value/value AS medication FROM EHR e CONTAINS COMPOSITION c CONTAINS INSTRUCTION m[openEHR-EHR-INSTRUCTION.medication_order.v3] WHERE e/ehr_status/subject/external_ref/id/value = $patient",
  "query_parameters": {"patient": "'"$PATIENT"'"}
}' | grep -oE '"rows":.*'

# by template
c -X POST "$BASE/query/aql" -H 'Content-Type: application/json' -H 'Accept: application/json' -d '{
  "q": "SELECT e/ehr_status/subject/external_ref/id/value AS patient, COUNT(c/uid/value) AS orders FROM EHR e CONTAINS COMPOSITION c WHERE c/archetype_details/template_id/value = $template",
  "query_parameters": {"template": "medication_order"}
}' | grep -oE '"rows":.*'

# by medication (ATC code)
c -X POST "$BASE/query/aql" -H 'Content-Type: application/json' -H 'Accept: application/json' -d '{
  "q": "SELECT e/ehr_status/subject/external_ref/id/value AS patient, m/activities[at0001]/description[at0002]/items[at0070]/value/value AS medication FROM EHR e CONTAINS COMPOSITION c CONTAINS INSTRUCTION m[openEHR-EHR-INSTRUCTION.medication_order.v3] WHERE m/activities[at0001]/description[at0002]/items[at0070]/value/defining_code/code_string = $atc",
  "query_parameters": {"atc": "A10BA02"}
}' | grep -oE '"rows":.*'
```

GET with the query in the URL (`-G --data-urlencode` does the encoding):

```bash
c -G "$BASE/query/aql" -H 'Accept: application/json' --data-urlencode 'q=SELECT e/ehr_id/value FROM EHR e LIMIT 3' | grep -oE '"rows":.*'
```
