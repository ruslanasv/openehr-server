# Postman collection

- `openehr-ehrbase.postman_collection.json` – 31 requests with tests, in folders:
  0 Server · 1 EHR · 2 Templates · 3 Composition lifecycle (create / read FLAT, JSON, XML / update / versions / 412) ·
  4 More compositions · 5 AQL (by patient, template, medication, ICD-10, vital signs, GET) · 6 Delete
- `local.postman_environment.json` – `base`, `user`, `password` for the local server
- `generate-collection.js` – regenerates both files from `requests/` (compositions are embedded, templates referenced as files)

## Postman app

1. Import both JSON files, select environment **openEHR local**.
2. Settings > General > **Working directory** = repo root – needed for the template upload requests (file bodies).
3. Run folders top to bottom (or Run collection). Each request stores ids for the next ones; a new patient id is generated each run.

## Command line (newman via Docker, no install)

From repo root in Git Bash, with the stack running:

    # scaled stack (docker-compose.scale.yml) – through nginx
    MSYS_NO_PATHCONV=1 docker run --rm --network openehr_default -v "$(pwd -W):/etc/newman" postman/newman \
      run postman/openehr-ehrbase.postman_collection.json -e postman/local.postman_environment.json \
      --env-var base=http://ehrbase-lb/ehrbase --working-dir /etc/newman

    # single-instance stack: use --env-var base=http://ehrbase:8080/ehrbase

Expected: 31 requests, 51 assertions, 0 failed.

## Regenerate after changing sample files

    MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd -W):/repo" --entrypoint node postman/newman /repo/postman/generate-collection.js
