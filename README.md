# Local openEHR server (EHRbase + PostgreSQL)

## Single instance

    docker compose up -d        # start
    docker compose logs -f ehrbase
    docker compose down         # stop (data kept in volume ehrdb-data)
    docker compose down -v      # stop and wipe the database

## Scaled: 2+ EHRbase instances, 1 PostgreSQL, nginx load balancer

    docker compose -f docker-compose.yml -f docker-compose.scale.yml up -d
    docker compose -f docker-compose.yml -f docker-compose.scale.yml up -d --scale ehrbase=3
    docker compose -f docker-compose.yml -f docker-compose.scale.yml logs -f ehrbase
    docker compose -f docker-compose.yml -f docker-compose.scale.yml down

Same URL as before (port 8080 is now nginx). Response header `X-Upstream` shows which instance answered.
Both modes use the same compose project and volume, so you can switch between them without losing data
(switching back to single: `down` with both files, then `docker compose up -d`).

Notes:
- All instances share the DB; templates uploaded through one instance are immediately usable on the others.
- `SYSTEM_NAME` / `SERVER_NODENAME` must be identical on all instances (they come from `.env.ehrbase`).
- If an instance dies abruptly, the first request routed to it gets a 502; nginx then marks it down
  and sends everything to the remaining instances. Clients should retry idempotent requests.
- Each instance has its own DB connection pool: instances x pool size must stay below Postgres `max_connections` (100).

## Endpoints

- REST API:   http://localhost:8080/ehrbase/rest/openehr/v1
- Swagger UI: http://localhost:8080/ehrbase/swagger-ui/index.html
- Status:     http://localhost:8080/ehrbase/rest/status (Spring management endpoints are disabled by default)
- API auth:   ehrbase-user / SuperSecretPassword (admin: ehrbase-admin / EvenMoreSecretPassword)
- Postgres:   localhost:5432, db `ehrbase`, user postgres/postgres (or ehrbase/ehrbase)

## Medication order scenario script

Uploads the CKM medication_order template, creates patients, writes/reads/updates compositions,
checks versions, runs AQL queries and prints response times. Results: `docs/medication-scenario-results.md`.

    scripts/medication-scenario.sh 10                                           # Git Bash
    & "C:\Program Files\Git\bin\bash.exe" scripts/medication-scenario.sh 10     # PowerShell

On Windows it needs Git Bash (part of Git for Windows); plain `bash` in PowerShell starts WSL instead.
To use Git Bash in the IntelliJ terminal: Settings > Tools > Terminal > Shell path = `C:\Program Files\Git\bin\bash.exe`.

## API request examples

- Postman collection + environment: `postman/` (see `postman/README.md`, also runnable with newman via Docker)
- curl: `docs/api-examples-curl.md`

## IntelliJ HTTP client

IntelliJ: open requests/openehr.http, choose environment `local`, run requests.
Templates are in requests/templates/, sample FLAT compositions in requests/compositions/.
