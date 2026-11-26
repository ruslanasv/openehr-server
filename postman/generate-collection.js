// Generates postman/openehr-ehrbase.postman_collection.json + local environment from the repo sample files.
// Run from repo root (Git Bash), no local Node needed:
//   MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd -W):/repo" --entrypoint node postman/newman /repo/postman/generate-collection.js
const fs = require('fs');
const R = '/repo';
const read = p => fs.readFileSync(`${R}/${p}`, 'utf8').replace(/\r\n/g, '\n');
const API = '{{base}}/rest/openehr/v1';

const J = { key: 'Accept', value: 'application/json' };
const CT_JSON = { key: 'Content-Type', value: 'application/json' };
const CT_XML = { key: 'Content-Type', value: 'application/xml' };

function req(name, method, url, { headers = [], body, test = [], pre = [], description } = {}) {
  const item = { name, request: { method, header: headers, url } };
  if (description) item.request.description = description;
  if (body !== undefined) {
    if (typeof body === 'string') item.request.body = { mode: 'raw', raw: body, options: { raw: { language: 'json' } } };
    else item.request.body = body;
  }
  const event = [];
  if (pre.length) event.push({ listen: 'prerequest', script: { type: 'text/javascript', exec: pre } });
  if (test.length) event.push({ listen: 'test', script: { type: 'text/javascript', exec: test } });
  if (event.length) item.event = event;
  return item;
}
const status = (...codes) => codes.length === 1
  ? `pm.test("HTTP ${codes[0]}", () => pm.response.to.have.status(${codes[0]}));`
  : `pm.test("HTTP ${codes.join('/')}", () => pm.expect(pm.response.code).to.be.oneOf([${codes.join(', ')}]));`;
const saveEtag = (v, uuidVar) => [
  `const etag = (pm.response.headers.get("ETag") || "").replace(/"/g, "");`,
  `pm.test("ETag has version uid", () => pm.expect(etag).to.match(/::\\d+$/));`,
  `pm.collectionVariables.set("${v}", etag);`,
  ...(uuidVar ? [`pm.collectionVariables.set("${uuidVar}", etag.split("::")[0]);`] : []),
];
const flat = file => file;
const aql = (q, params) => JSON.stringify(params ? { q, query_parameters: params } : { q }, null, 2);
const rowsTest = (min = 1) => [
  status(200),
  `pm.test("returns rows", () => pm.expect(pm.response.json().rows.length).to.be.at.least(${min}));`,
];

const metformin = read('requests/compositions/medication_order_metformin.flat.json');
const metforminV2 = metformin
  .replace('"Metformin 500 mg tablet"', '"Metformin 1000 mg tablet"')
  .replace(/(strength_concentration\|magnitude": )500/, '$11000')
  .replace('Metformin 500 mg orally', 'Metformin 1000 mg orally');
const lisinopril = read('requests/compositions/medication_order_lisinopril.flat.json');
const diagnosis = read('requests/compositions/diagnosis.flat.json');
const vitals = read('requests/compositions/vital_signs.flat.json');
const MED = 'm/activities[at0001]/description[at0002]/items';
const STRENGTH = 'medication_order/medication_order/order:0/medication/strength_concentration|magnitude';

const ehrStatus = JSON.stringify({
  _type: 'EHR_STATUS',
  archetype_node_id: 'openEHR-EHR-EHR_STATUS.generic.v1',
  name: { value: 'EHR Status' },
  subject: { _type: 'PARTY_SELF', external_ref: { id: { _type: 'GENERIC_ID', value: '{{patient_id}}', scheme: 'id_scheme' }, namespace: 'patients', type: 'PERSON' } },
  is_queryable: true, is_modifiable: true,
}, null, 2);

const uploadOpt = (name, path) => req(`Upload template: ${name}`, 'POST', `${API}/definition/template/adl1.4`, {
  headers: [CT_XML],
  body: { mode: 'file', file: { src: path } },
  test: [status(201, 409), `// 409 = template already uploaded`],
  description: `Uploads \`${path}\`. In the Postman app, set Settings > General > Working directory to the repo root (or re-select the file in the Body tab).`,
});

const collection = {
  info: {
    name: 'openEHR – EHRbase local',
    schema: 'https://schema.getpostman.com/json/collection/v2.1.0/collection.json',
    description: [
      'openEHR REST API examples against a local EHRbase (see README).',
      '',
      'Run the folders top to bottom: each request stores ids (ehr_id, med_uid, …) in collection variables for the next ones.',
      'Environment: postman/local.postman_environment.json (base, user, password).',
      'Command line: see postman/README.md (newman via Docker).',
    ].join('\n'),
  },
  auth: { type: 'basic', basic: [
    { key: 'username', value: '{{user}}', type: 'string' },
    { key: 'password', value: '{{password}}', type: 'string' },
  ] },
  variable: ['patient_id', 'ehr_id', 'med_uid', 'med_id', 'med_uid_v1', 'lis_uid', 'lis_id', 'diag_uid', 'vitals_uid']
    .map(key => ({ key, value: '' })),
  item: [
    { name: '0 Server', item: [
      req('Status', 'GET', `{{base}}/rest/status`, { headers: [J], test: [status(200),
        `pm.test("has EHRbase version", () => pm.expect(pm.response.json().ehrbase_version).to.be.a("string"));`] }),
    ] },
    { name: '1 EHR', item: [
      req('Create EHR with subject id', 'POST', `${API}/ehr`, {
        headers: [J, CT_JSON, { key: 'Prefer', value: 'return=representation' }],
        pre: [`pm.collectionVariables.set("patient_id", "PM-" + Date.now());`],
        body: ehrStatus,
        test: [status(201), `pm.collectionVariables.set("ehr_id", pm.response.json().ehr_id.value);`],
        description: 'Creates an EHR whose EHR_STATUS.subject points to patient {{patient_id}} (namespace "patients"). A new patient id is generated on each run.',
      }),
      req('Get EHR by id', 'GET', `${API}/ehr/{{ehr_id}}`, { headers: [J], test: [status(200)] }),
      req('Get EHR by subject id', 'GET', `${API}/ehr?subject_id={{patient_id}}&subject_namespace=patients`, {
        headers: [J], test: [status(200), `pm.test("same EHR", () => pm.expect(pm.response.json().ehr_id.value).to.eql(pm.collectionVariables.get("ehr_id")));`] }),
      req('Get EHR_STATUS', 'GET', `${API}/ehr/{{ehr_id}}/ehr_status`, { headers: [J], test: [status(200)] }),
    ] },
    { name: '2 Templates', item: [
      uploadOpt('medication_order (CKM)', 'requests/templates/ckm/medication_order.opt'),
      uploadOpt('minimal_diagnosis', 'requests/templates/minimal_diagnosis.opt'),
      uploadOpt('minimal_vital_signs', 'requests/templates/minimal_vital_signs.opt'),
      req('List templates', 'GET', `${API}/definition/template/adl1.4`, { headers: [J], test: [status(200),
        `pm.test("medication_order present", () => pm.expect(pm.response.json().map(t => t.template_id)).to.include("medication_order"));`] }),
      req('Get web template (all FLAT paths)', 'GET', `${API}/definition/template/adl1.4/medication_order`, {
        headers: [{ key: 'Accept', value: 'application/openehr.wt+json' }], test: [status(200)] }),
      req('Get example composition (FLAT)', 'GET', `${API}/definition/template/adl1.4/medication_order/example?format=FLAT`, {
        headers: [J], test: [status(200)] }),
    ] },
    { name: '3 Composition lifecycle (medication order)', item: [
      req('Create composition – metformin 500 mg (FLAT)', 'POST', `${API}/ehr/{{ehr_id}}/composition?templateId=medication_order&format=FLAT`, {
        headers: [CT_JSON, { key: 'Prefer', value: 'return=minimal' }], body: flat(metformin),
        test: [status(201, 204), ...saveEtag('med_uid', 'med_id'), `pm.collectionVariables.set("med_uid_v1", etag);`] }),
      req('Read composition (FLAT)', 'GET', `${API}/ehr/{{ehr_id}}/composition/{{med_id}}?format=FLAT`, {
        headers: [J], test: [status(200), `pm.test("strength 500", () => pm.expect(pm.response.json()["${STRENGTH}"]).to.eql(500));`] }),
      req('Read composition (canonical JSON)', 'GET', `${API}/ehr/{{ehr_id}}/composition/{{med_id}}`, {
        headers: [J], test: [status(200), `pm.test("is COMPOSITION", () => pm.expect(pm.response.json()._type).to.eql("COMPOSITION"));`] }),
      req('Read composition (canonical XML)', 'GET', `${API}/ehr/{{ehr_id}}/composition/{{med_id}}`, {
        headers: [{ key: 'Accept', value: 'application/xml' }], test: [status(200)] }),
      req('Update composition – metformin 1000 mg', 'PUT', `${API}/ehr/{{ehr_id}}/composition/{{med_id}}?templateId=medication_order&format=FLAT`, {
        headers: [CT_JSON, { key: 'Prefer', value: 'return=minimal' }, { key: 'If-Match', value: '{{med_uid}}' }], body: flat(metforminV2),
        test: [status(200, 204), ...saveEtag('med_uid'), `pm.test("version 2", () => pm.expect(etag).to.match(/::2$/));`],
        description: 'If-Match must carry the current version uid (optimistic locking).' }),
      req('Revision history', 'GET', `${API}/ehr/{{ehr_id}}/versioned_composition/{{med_id}}/revision_history`, {
        headers: [J], test: [status(200), `pm.test("2 versions", () => pm.expect(pm.response.json().items.length).to.eql(2));`] }),
      req('Read version 1', 'GET', `${API}/ehr/{{ehr_id}}/composition/{{med_uid_v1}}?format=FLAT`, {
        headers: [J], test: [status(200), `pm.test("version 1 still 500", () => pm.expect(pm.response.json()["${STRENGTH}"]).to.eql(500));`] }),
      req('Read latest version', 'GET', `${API}/ehr/{{ehr_id}}/composition/{{med_id}}?format=FLAT`, {
        headers: [J], test: [status(200), `pm.test("latest is 1000", () => pm.expect(pm.response.json()["${STRENGTH}"]).to.eql(1000));`] }),
      req('Update with stale If-Match → 412', 'PUT', `${API}/ehr/{{ehr_id}}/composition/{{med_id}}?templateId=medication_order&format=FLAT`, {
        headers: [CT_JSON, { key: 'Prefer', value: 'return=minimal' }, { key: 'If-Match', value: '{{med_uid_v1}}' }], body: flat(metforminV2),
        test: [status(412)] }),
    ] },
    { name: '4 More compositions', item: [
      req('Create medication order – lisinopril', 'POST', `${API}/ehr/{{ehr_id}}/composition?templateId=medication_order&format=FLAT`, {
        headers: [CT_JSON, { key: 'Prefer', value: 'return=minimal' }], body: flat(lisinopril), test: [status(201, 204), ...saveEtag('lis_uid', 'lis_id')] }),
      req('Create diagnosis (ICD-10)', 'POST', `${API}/ehr/{{ehr_id}}/composition?templateId=minimal_diagnosis.v0&format=FLAT`, {
        headers: [CT_JSON, { key: 'Prefer', value: 'return=minimal' }], body: flat(diagnosis), test: [status(201, 204), ...saveEtag('diag_uid')] }),
      req('Create vital signs', 'POST', `${API}/ehr/{{ehr_id}}/composition?templateId=minimal_vital_signs.v0&format=FLAT`, {
        headers: [CT_JSON, { key: 'Prefer', value: 'return=minimal' }], body: flat(vitals), test: [status(201, 204), ...saveEtag('vitals_uid')] }),
    ] },
    { name: '5 AQL', item: [
      req('By patient – medications', 'POST', `${API}/query/aql`, { headers: [J, CT_JSON], test: rowsTest(2),
        body: aql(`SELECT c/context/start_time/value AS ordered, ${MED}[at0070]/value/value AS medication, ${MED}[at0009]/value/value AS directions FROM EHR e CONTAINS COMPOSITION c CONTAINS INSTRUCTION m[openEHR-EHR-INSTRUCTION.medication_order.v3] WHERE e/ehr_status/subject/external_ref/id/value = $patient AND e/ehr_status/subject/external_ref/namespace = 'patients' ORDER BY c/context/start_time/value`, { patient: '{{patient_id}}' }) }),
      req('By template – compositions per patient', 'POST', `${API}/query/aql`, { headers: [J, CT_JSON], test: rowsTest(),
        body: aql(`SELECT e/ehr_status/subject/external_ref/id/value AS patient, COUNT(c/uid/value) AS orders FROM EHR e CONTAINS COMPOSITION c WHERE c/archetype_details/template_id/value = $template`, { template: 'medication_order' }) }),
      req('By medication – ATC A10BA02 (metformin)', 'POST', `${API}/query/aql`, { headers: [J, CT_JSON], test: rowsTest(),
        body: aql(`SELECT e/ehr_status/subject/external_ref/id/value AS patient, ${MED}[at0070]/value/value AS medication, c/uid/value AS composition FROM EHR e CONTAINS COMPOSITION c CONTAINS INSTRUCTION m[openEHR-EHR-INSTRUCTION.medication_order.v3] WHERE ${MED}[at0070]/value/defining_code/code_string = $atc`, { atc: 'A10BA02' }) }),
      req('Diagnoses by ICD-10 code', 'POST', `${API}/query/aql`, { headers: [J, CT_JSON], test: rowsTest(),
        body: aql(`SELECT e/ehr_status/subject/external_ref/id/value AS patient, d/data[at0001]/items[at0002]/value/value AS diagnosis FROM EHR e CONTAINS EVALUATION d[openEHR-EHR-EVALUATION.problem_diagnosis.v1] WHERE d/data[at0001]/items[at0002]/value/defining_code/code_string = $icd10`, { icd10: 'I10' }) }),
      req('Vital signs of the patient', 'POST', `${API}/query/aql`, { headers: [J, CT_JSON], test: rowsTest(),
        body: aql(`SELECT bp/data[at0001]/events[at0006]/time/value AS time, bp/data[at0001]/events[at0006]/data[at0003]/items[at0004]/value/magnitude AS systolic, bp/data[at0001]/events[at0006]/data[at0003]/items[at0005]/value/magnitude AS diastolic, p/data[at0002]/events[at0003]/data[at0001]/items[at0004]/value/magnitude AS pulse FROM EHR e[ehr_id/value=$ehr_id] CONTAINS COMPOSITION c CONTAINS (OBSERVATION bp[openEHR-EHR-OBSERVATION.blood_pressure.v2] AND OBSERVATION p[openEHR-EHR-OBSERVATION.pulse.v2])`, { ehr_id: '{{ehr_id}}' }) }),
      req('GET query (URL parameters)', 'GET', `${API}/query/aql?q=SELECT e/ehr_id/value FROM EHR e LIMIT 5`, { headers: [J], test: rowsTest() }),
    ] },
    { name: '6 Delete', item: [
      req('Delete composition (lisinopril)', 'DELETE', `${API}/ehr/{{ehr_id}}/composition/{{lis_uid}}`, {
        test: [status(204)], description: 'Logical delete: takes the current version uid. History is kept; the composition no longer appears in AQL results.' }),
      req('Read deleted composition', 'GET', `${API}/ehr/{{ehr_id}}/composition/{{lis_id}}`, {
        headers: [J], test: [status(204, 404)], description: 'EHRbase answers 204 No Content for a deleted composition.' }),
    ] },
  ],
};

fs.mkdirSync(`${R}/postman`, { recursive: true });
fs.writeFileSync(`${R}/postman/openehr-ehrbase.postman_collection.json`, JSON.stringify(collection, null, 2) + '\n');
fs.writeFileSync(`${R}/postman/local.postman_environment.json`, JSON.stringify({
  name: 'openEHR local',
  values: [
    { key: 'base', value: 'http://localhost:8080/ehrbase', type: 'default', enabled: true },
    { key: 'user', value: 'ehrbase-user', type: 'default', enabled: true },
    { key: 'password', value: 'SuperSecretPassword', type: 'secret', enabled: true },
  ],
  _postman_variable_scope: 'environment',
}, null, 2) + '\n');
console.log('written');
