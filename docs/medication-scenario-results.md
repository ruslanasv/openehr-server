# Medication order scenario – results (2026-11-26)

Script: `scripts/medication-scenario.sh [runs]` (re-runnable; reuses template and patients, adds new compositions).

## Environment

- EHRbase 2.38.0 ×2 behind nginx (`docker-compose.scale.yml`), PostgreSQL 16.2 ×1
- Docker Desktop (WSL2) on a Windows 10 laptop, Docker VM: 4 CPUs, 8 GB RAM
- Client: curl from the same machine, via nginx on `localhost:8080`, basic auth
- Data volume: practically empty – 3 patients, ~15 compositions

## 1. Template

`src/main/resources/templates/medication_order.opt` – template `medication_order` (since 2026-11-26 locally modified: medication item constrained to DV_CODED_TEXT with terminology ATC) from the public
[ehrbase/performance-test](https://github.com/ehrbase/performance-test) repository, built on CKM archetypes
`COMPOSITION.encounter.v1`, `INSTRUCTION.medication_order.v3`, `CLUSTER.medication.v1`.
Uploaded via `POST /definition/template/adl1.4` → 201 (409 on repeat upload).

## 2. EHR & composition lifecycle

| Step | Request | Result |
|---|---|---|
| Create patient EHR with subject id (`LT-PAC-001`, namespace `patients`) | `POST /ehr` with EHR_STATUS | 201 |
| Find EHR by subject | `GET /ehr?subject_id=&subject_namespace=` | 200 |
| Write medication order (FLAT) | `POST /ehr/{id}/composition?templateId=medication_order&format=FLAT` | 204, ETag `uuid::local.ehrbase.org::1` |
| Read | `GET /ehr/{id}/composition/{uuid}?format=FLAT` | 200, strength 500 mg |
| Update metformin 500 → 1000 mg | `PUT …/composition/{uuid}` with `If-Match: …::1` | 204, ETag `…::2` |
| Revision history | `GET /ehr/{id}/versioned_composition/{uuid}/revision_history` | versions 1 and 2 |
| Read version 1 / version 2 / latest | `GET …/composition/{uuid}::local.ehrbase.org::{n}` | 500 / 1000 / 1000 mg |
| Update with stale `If-Match: …::1` | `PUT` | **412** – optimistic locking works |

## 3. AQL

Medication item path: `m/activities[at0001]/description[at0002]/items[at0070]` in `INSTRUCTION.medication_order.v3`.

| Query | Filter | Example result |
|---|---|---|
| By patient | `e/ehr_status/subject/external_ref/id/value = $patient` | LT-PAC-003 → Metformin 500 mg, Lisinopril 10 mg |
| By template | `c/archetype_details/template_id/value = 'medication_order'`, `COUNT` per patient | LT-PAC-001: n, LT-PAC-002: n, LT-PAC-003: 2n |
| By medication | `…items[at0070]/value/defining_code/code_string = 'A10BA02'` (ATC) | patients with metformin; LT-PAC-001 shows only latest version (1000 mg) |

AQL over `COMPOSITION` returns only the latest version of each composition.

## 4. Approximate response times (ms)

Orientation only – single client, sequential requests, empty database, laptop.

**Run 1 – right after startup (cold JVM), 10 repetitions for reads / AQL**

| operation                |   n |    min | median |    avg |    max |
|--------------------------|-----|--------|--------|--------|--------|
| upload template (409)    |   1 |    145 |    145 |    145 |    145 |
| get EHR by subject       |   3 |     15 |     31 |     27 |     36 |
| create EHR               |   3 |     47 |     53 |    142 |    327 |
| create composition       |   4 |    132 |    194 |    219 |    356 |
| read composition (FLAT)  |  10 |     34 |     58 |    139 |    556 |
| update composition       |   1 |    182 |    182 |    182 |    182 |
| revision history         |   1 |    125 |    125 |    125 |    125 |
| read specific version    |   2 |     62 |     62 |     62 |     62 |
| read latest              |   1 |     55 |     55 |     55 |     55 |
| stale update rejected    |   1 |    103 |    103 |    103 |    103 |
| AQL by patient           |  10 |     53 |     63 |    226 |    940 |
| AQL by template          |  10 |     24 |     33 |     46 |    105 |
| AQL by medication        |  10 |     43 |     56 |     54 |     74 |

**Run 2 – warmed up, 20 repetitions for reads / AQL**

| operation                |   n |    min | median |    avg |    max |
|--------------------------|-----|--------|--------|--------|--------|
| upload template (409)    |   1 |    247 |    247 |    247 |    247 |
| get EHR by subject       |   3 |     24 |     38 |     53 |     97 |
| create composition       |   4 |     93 |    102 |    102 |    108 |
| read composition (FLAT)  |  20 |     29 |     50 |     53 |    110 |
| update composition       |   1 |     85 |     85 |     85 |     85 |
| revision history         |   1 |     34 |     34 |     34 |     34 |
| read specific version    |   2 |     24 |     27 |     27 |     30 |
| read latest              |   1 |     42 |     42 |     42 |     42 |
| stale update rejected    |   1 |     47 |     47 |     47 |     47 |
| AQL by patient           |  20 |     28 |     34 |     38 |     66 |
| AQL by template          |  20 |     17 |     24 |     26 |     37 |
| AQL by medication        |  20 |     23 |     28 |     31 |     49 |

**Takeaways (rough):** warmed up, reads and AQL ≈ 25–55 ms, writes (create/update composition) ≈ 85–110 ms.
The first requests after startup are several times slower (up to ~1 s) – JVM warm-up, each of the
2 instances warms up separately. These numbers say nothing about behaviour with real data volumes or concurrent users;
that needs a proper performance test (e.g. ehrbase/performance-test, Gatling/JMeter) with a loaded database.
