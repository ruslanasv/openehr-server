(function () {
  const J = id => JSON.parse(document.getElementById(id).textContent);
  // Data embedded by scripts/template-atlas.sh: atlas-data (counts, config), wt-<key> (web template), ex-<key> (FLAT example)
  const D = J('atlas-data');
  const T = D.templates.map(t => ({ ...t, wt: J('wt-' + t.key), ex: J('ex-' + t.key) }));
  document.getElementById('ehr-count').textContent = D.ehrs + (D.ehrs === 1 ? ' EHR' : ' EHRs');
  document.getElementById('asof').textContent =
    `Counts from AQL on the local server, ${D.generated}. One EHR can hold compositions of any template.`;
  const TYPES = { DV_QUANTITY: 'Quantity', DV_CODED_TEXT: 'Coded text', DV_TEXT: 'Text', DV_DATE_TIME: 'Date/time', DV_DATE: 'Date',
    DV_COUNT: 'Count', DV_DURATION: 'Duration', DV_PARSABLE: 'Parsable text', DV_IDENTIFIER: 'Identifier', DV_BOOLEAN: 'Yes/no',
    CLUSTER: 'Group', EVENT: 'Event', POINT_EVENT: 'Event', OBSERVATION: 'Observation', EVALUATION: 'Evaluation', INSTRUCTION: 'Instruction',
    ACTION: 'Action', ACTIVITY: 'Activity', COMPOSITION: 'Composition', EVENT_CONTEXT: 'Context', PARTY_PROXY: 'Party',
    CODE_PHRASE: 'Code', STRING: 'String', SECTION: 'Section', ADMIN_ENTRY: 'Admin entry' };
  const ENTRY = ['OBSERVATION', 'EVALUATION', 'INSTRUCTION', 'ACTION', 'ADMIN_ENTRY'];
  const TECH = new Set(['category', 'context', 'start_time', 'setting', 'subject', 'language', 'encoding', 'composer', 'territory', 'expiry_time', 'action_archetype_id']);
  const esc = s => String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

  // Index example FLAT values by index-free path
  function indexExample(ex) {
    const m = {};
    for (const [k, v] of Object.entries(ex)) {
      if (k.startsWith('ctx/')) continue;
      const [p, suffix] = k.split('|');
      const inst = (p.match(/:(\d+)/g) || []).join('');
      const key = p.replace(/:\d+/g, '');
      ((m[key] ||= {})[inst] ||= {})[suffix || ''] = v;
    }
    return m;
  }
  function exampleText(node, rec) {
    if (!rec) return null;
    return Object.values(rec).map(r => {
      if ('magnitude' in r) return r.magnitude + (r.unit ? ' ' + r.unit : '');
      if ('value' in r && 'code' in r) return r.value + ' · ' + r.code;
      if ('code' in r) {
        const lbl = (node.inputs || []).flatMap(i => i.list || []).find(o => o.value === r.code);
        return lbl ? lbl.label : r.code;
      }
      return r[''] !== undefined ? r[''] : Object.values(r)[0];
    }).join('  |  ');
  }

  // Prepare nodes: paths, technical flag, usage
  function prep(t) {
    const exIdx = indexExample(t.ex);
    const stats = { fields: 0, required: 0, used: 0 };
    (function walk(n, path, flat, depth, techParent) {
      n._path = path; n._flat = flat;
      n._ex = exampleText(n, exIdx[path]);
      n._tech = !n._ex && (techParent || !!n.inContext || (depth > 0 && TECH.has(n.id) && !ENTRY.includes(n.rmType)));
      n._leaf = !n.children || !n.children.length;
      let used = !!n._ex;
      (n.children || []).forEach(c => {
        const rep = c.max !== 1 ? ':0' : '';
        walk(c, path + '/' + c.id, flat + '/' + c.id + rep, depth + 1, n._tech);
        if (c._used) used = true;
      });
      n._used = used;
      if (n._leaf && !n._tech) { stats.fields++; if (n.min >= 1) stats.required++; if (n._ex) stats.used++; }
    })(t.wt.tree, t.wt.tree.id, t.wt.tree.id, 0, false);
    t.stats = stats;
  }
  T.forEach(prep);

  function card(n) {
    if (n.min >= 1 && n.max === 1) return ['required', 'req'];
    if (n.min === 0 && n.max === 1) return ['optional', ''];
    if (n.max === -1) return [n.min === 0 ? '0 or more' : n.min + ' or more', 'many'];
    return [n.min + '..' + n.max, 'many'];
  }
  function entries(t) {
    const out = [];
    (function f(n) { (n.children || []).forEach(c => { if (ENTRY.includes(c.rmType)) out.push(c); else if (c.rmType === 'SECTION') f(c); }); })(t.wt.tree);
    return out;
  }

  // Overview cards
  const br = document.getElementById('branches');
  T.forEach((t, i) => {
    const b = document.createElement('button');
    b.className = 'tcard';
    b.innerHTML = `<span class="eyebrow">Template</span><h3>${esc(t.wt.tree.name)}</h3>
      <span class="tid">${esc(t.wt.templateId)}</span>
      <span class="count">${t.count}<small>compositions</small></span>
      <div class="chips">${entries(t).map(e => `<span class="chip k-${e.rmType}"><i>${TYPES[e.rmType]}</i>${esc(e.name)}${e.max !== 1 ? ' ×n' : ''}</span>`).join('')}</div>
      <span class="meta">${t.stats.fields} data fields · ${t.stats.required} required</span>`;
    b.addEventListener('click', () => { select(i); document.getElementById('explorer').scrollIntoView({ behavior: 'smooth' }); });
    br.appendChild(b);
  });

  // Tabs
  const tabs = document.getElementById('tabs');
  T.forEach((t, i) => {
    const b = document.createElement('button');
    b.className = 'tab'; b.role = 'tab'; b.id = 'tab-' + t.key;
    b.textContent = t.wt.tree.name;
    b.addEventListener('click', () => select(i));
    tabs.appendChild(b);
  });

  const optTech = document.getElementById('opt-tech'), optUsed = document.getElementById('opt-used');
  let cur = 0;
  optTech.addEventListener('change', render); optUsed.addEventListener('change', render);

  function inputsHtml(n) {
    const rows = [];
    for (const inp of n.inputs || []) {
      const what = inp.suffix ? `<code>|${esc(inp.suffix)}</code> ` : '';
      let detail = esc((inp.type || '').toLowerCase());
      if (inp.list && inp.list.length) {
        detail = `<div class="vals">${inp.list.slice(0, 30).map(o => `<span>${esc(o.label)}${o.label !== o.value ? ` <code>${esc(o.value)}</code>` : ''}</span>`).join('')}</div>`;
      }
      if (inp.terminology && !(inp.list && inp.list.length)) detail += ` · codes from <b>${esc(inp.terminology)}</b>`;
      const r = inp.validation && inp.validation.range;
      if (r) detail += ` · range ${r.min ?? '…'} – ${r.max ?? '…'}`;
      rows.push(`<dt>${what || 'value'}</dt><dd>${detail}</dd>`);
    }
    return rows.join('');
  }
  function cardHtml(n) {
    const desc = n.localizedDescriptions && n.localizedDescriptions.en;
    const sufs = (n.inputs || []).map(i => i.suffix).filter(Boolean);
    const flat = n._flat + (n._leaf && sufs.length ? '|' + sufs[0] : '');
    return `<div class="card">
      ${desc ? `<p>${esc(desc)}</p>` : ''}
      <dl>
        <dt>Data type</dt><dd>${esc(TYPES[n.rmType] || n.rmType)} <code>${esc(n.rmType)}</code></dd>
        ${n.nodeId && n.nodeId.startsWith('openEHR') ? `<dt>Archetype</dt><dd><code>${esc(n.nodeId)}</code></dd>` : ''}
        ${inputsHtml(n)}
        ${n._ex ? `<dt>Example</dt><dd><span class="ex" style="white-space:normal">${esc(n._ex)}</span></dd>` : ''}
        <dt>FLAT path</dt><dd class="path"><code>${esc(flat)}</code><button class="copy" data-copy="${esc(flat)}">Copy</button></dd>
        ${n.aqlPath ? `<dt>AQL path</dt><dd class="path"><code>${esc(n.aqlPath)}</code><button class="copy" data-copy="${esc(n.aqlPath)}">Copy</button></dd>` : ''}
      </dl></div>`;
  }

  function nodeEl(n, depth) {
    if (n._tech && !optTech.checked) return null;
    if (optUsed.checked && !n._used) return null;
    const el = document.createElement('div');
    const container = !n._leaf;
    el.className = 'node' + (container ? ' container' : '') + (n._tech ? ' tech' : '');
    const [ctext, ccls] = card(n);
    const kind = ENTRY.includes(n.rmType) || ['CLUSTER', 'EVENT', 'ACTIVITY', 'COMPOSITION'].includes(n.rmType) ? `k-${n.rmType}` : '';
    const row = document.createElement('div');
    row.className = 'row'; row.setAttribute('role', 'treeitem');
    row.innerHTML = `${container ? '<button class="caret" aria-label="Collapse or expand">▾</button>' : '<span class="caret" aria-hidden="true">·</span>'}
      <button class="label">${kind ? `<span class="dot ${kind}"></span>` : ''}<span class="nm">${esc(n.name)}</span>
        <span class="type">${esc(TYPES[n.rmType] || n.rmType)}</span>
        ${n._ex && n._leaf ? `<span class="ex" title="${esc(n._ex)}">${esc(n._ex)}</span>` : ''}</button>
      ${depth ? `<span class="pill ${ccls}">${ctext}</span>` : ''}`;
    el.appendChild(row);
    let cardEl = null;
    row.querySelector('.label').addEventListener('click', showCard);
    if (container) {
      const kids = document.createElement('div');
      kids.className = 'kids'; kids.setAttribute('role', 'group');
      n.children.forEach(c => { const k = nodeEl(c, depth + 1); if (k) kids.appendChild(k); });
      el.appendChild(kids);
      row.setAttribute('aria-expanded', 'true');
      row.querySelector('.caret').addEventListener('click', () => {
        el.classList.toggle('closed');
        row.setAttribute('aria-expanded', String(!el.classList.contains('closed')));
      });
    }
    function showCard() {
      if (cardEl) { cardEl.remove(); cardEl = null; return; }
      const tmp = document.createElement('div'); tmp.innerHTML = cardHtml(n); cardEl = tmp.firstElementChild;
      row.after(cardEl);
    }
    return el;
  }

  function render() {
    const t = T[cur];
    document.getElementById('tdesc').textContent = t.blurb;
    const s = t.stats;
    document.getElementById('summary').innerHTML =
      `<span><b>${s.fields}</b> data fields</span><span><b>${s.required}</b> required</span><span><b>${s.used}</b> filled in example</span>`;
    const tree = document.getElementById('tree'); tree.innerHTML = '';
    const root = nodeEl(t.wt.tree, 0); if (root) tree.appendChild(root);
    document.getElementById('ctx-sum').textContent = `Example composition as sent: requests/compositions/${t.file}`;
    document.getElementById('ctx-pre').innerHTML = Object.entries(t.ex).map(([k, v]) =>
      `<span class="k">${esc(JSON.stringify(k))}</span>: <span class="v">${esc(JSON.stringify(v))}</span>`).join('\n');
  }
  function select(i) {
    cur = i;
    [...tabs.children].forEach((b, j) => b.setAttribute('aria-selected', String(j === i)));
    render();
  }

  document.addEventListener('click', e => {
    const b = e.target.closest('.copy'); if (!b) return;
    e.stopPropagation();
    const txt = b.dataset.copy;
    const done = () => { b.textContent = 'Copied'; setTimeout(() => (b.textContent = 'Copy'), 1200); };
    try {
      navigator.clipboard.writeText(txt).then(done, () => selectText(b));
    } catch (_) { selectText(b); }
  });
  function selectText(b) {
    const code = b.previousElementSibling; const r = document.createRange(); r.selectNodeContents(code);
    const s = getSelection(); s.removeAllRanges(); s.addRange(r);
  }

  select(0);
})();
