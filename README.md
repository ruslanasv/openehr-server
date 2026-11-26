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

## Java / Maven project: test data generator

The repo is a Maven project (Java 21). `./mvnw` (Windows: `mvnw.cmd`) downloads Maven 3.10.2 on first use,
so the locally installed Maven version does not matter. Open the folder in IntelliJ as a Maven project.

    ./mvnw test                     # unit tests, no server needed
    ./mvnw package                  # tests + runnable jar target/data-generator.jar
    java -jar target/data-generator.jar --ehrs 100 --compositions 5 --threads 8 --seed 42
    java -jar target/data-generator.jar --help
    ./mvnw compile exec:java -Dexec.args="--ehrs 10"      # run without packaging

The generator creates N EHRs with M random compositions each, using the
[openEHR SDK](https://github.com/ehrbase/openEHR_SDK) 2.36.0 (same version as the server):

- **Entity classes generated from the templates** by the SDK `generator-maven-plugin` during the build
  (`src/main/resources/templates/*.opt` → `target/generated-sources/ehrbase-generator`, package
  `io.github.ruslanasv.openehr.model`), e.g. `MinimalDiagnosisV0Composition`, `BloodPressureObservation`,
  `SeverityDefiningCode` (enum). They are not committed; IntelliJ picks them up after a Maven reload/build.
- **Templates**: `minimal_diagnosis.opt` and `minimal_vital_signs.opt` are hand-written; `medication_order.opt` comes from
  [ehrbase/performance-test](https://github.com/ehrbase/performance-test) (Apache-2.0, CKM archetypes) with one local change:
  the medication item is `DV_CODED_TEXT` bound to ATC (upstream: free `DV_TEXT`), so orders always carry an ATC code.
- **Factories** (`src/main/java/io/github/ruslanasv/openehr/generator`) fill the entities with random data –
  ICD-10 diagnoses, vital signs with realistic distributions, ATC medication orders – and convert them to RM
  compositions (`GeneratedDtoToRmConverter`).
- **SDK REST client** (`DefaultRestClient`) uploads missing templates (`ensureExistence`), creates EHRs and posts
  compositions; basic auth and the connection pool are set up in `EhrbaseClients`.
- **Unit tests** validate generated compositions against their templates with the SDK `LocatableValidator`,
  check reproducibility (same seed → same composition) and that medication items carry ATC codes.

Patients get subject ids `GEN-<run>-0001…` in namespace `generated` (separate from hand-made test data);
the same `--seed` produces the same data. Prints throughput and latency per operation.

Adding a template: put the `.opt` into `src/main/resources/templates`, list it in the `generator-maven-plugin`
configuration (`pom.xml`) and in `ClasspathTemplateProvider`, write a `CompositionFactory` for the generated
entity class and register it in `CompositionFactories`.

## Project layout

    docker-compose*.yml, .env.ehrbase, nginx/   EHRbase + PostgreSQL (+ load balancer)
    pom.xml, mvnw, src/main/java                data generator (openEHR SDK)
    src/main/resources/templates                operational templates (.opt), used by all tools below
    src/test/java                               unit tests
    requests/                                   IntelliJ HTTP client requests + sample FLAT compositions
    postman/                                    Postman collection / newman
    scripts/medication-scenario.sh              curl-based scenario with versions, AQL and timings
    docs/                                       curl examples, scenario results

## API request examples

- Postman collection + environment: `postman/` (see `postman/README.md`, also runnable with newman via Docker)
- curl: `docs/api-examples-curl.md`

## IntelliJ HTTP client

IntelliJ: open requests/openehr.http, choose environment `local`, run requests.
Templates are in src/main/resources/templates/, sample FLAT compositions in requests/compositions/.
