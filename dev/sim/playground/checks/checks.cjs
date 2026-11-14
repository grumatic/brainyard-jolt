// The mini-playground's acceptance checks (RFC-041): run in the project dir,
// `node <plan>/checks/checks.cjs <check>`, exit 0 when it holds. Each starts
// the project's own server (`npm start`, PORT) on a free port and stops it.
// Node 22's fetch and WebSocket: nothing is needed from the project.
const { spawn } = require('node:child_process');
const net = require('node:net');
const fs = require('node:fs');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
// the servers a check started, stopped however it ends: a failing check
// exits at once, and its server was left running (sim run 1054)
const started = new Set();
const stopAll = () => { for (const pid of started) { try { process.kill(-pid, 'SIGTERM'); } catch {} } started.clear(); };
process.on('exit', stopAll);
const fail = (msg) => { console.error('FAIL ' + msg); process.exit(1); };

function freePort() {
  return new Promise((res) => { const s = net.createServer(); s.listen(0, () => { const p = s.address().port; s.close(() => res(p)); }); });
}

async function withServer(fn) {
  const port = await freePort();
  const srv = spawn('npm', ['start'], { env: { ...process.env, PORT: String(port) }, stdio: 'ignore', detached: true });
  started.add(srv.pid);
  const base = `127.0.0.1:${port}`;
  try {
    let up = false;
    for (let i = 0; i < 75 && !up; i++) {
      try { up = (await fetch(`http://${base}/api/health`)).ok; } catch { await sleep(200); }
    }
    if (!up) fail(`the server did not answer /api/health on PORT=${port} within 15 s (npm start)`);
    await fn(base);
  } finally { try { process.kill(-srv.pid, 'SIGTERM'); } catch {} started.delete(srv.pid); }
}

// a WebSocket client that collects output frames {t:'o', d}
function term(url) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(url);
    const c = { ws, out: '', send: (m) => ws.send(JSON.stringify(m)) };
    ws.onmessage = (e) => { try { const m = JSON.parse(e.data); if (m.t === 'o') c.out += m.d; } catch { c.out += String(e.data); } };
    ws.onopen = () => resolve(c);
    ws.onerror = () => reject(new Error('cannot connect to ' + url));
  });
}
async function until(c, text, ms) {
  for (let t = 0; t < ms; t += 100) { if (c.out.includes(text)) return true; await sleep(100); }
  return false;
}

const checks = {
  async health() {
    await withServer(async (b) => {
      const h = await (await fetch(`http://${b}/api/health`)).json();
      if (h.ok !== true) fail('/api/health did not answer {ok:true}: ' + JSON.stringify(h));
      const page = await (await fetch(`http://${b}/`)).text();
      for (const s of ['Terminal', 'Files', 'Editor', 'Chat']) if (!page.includes(s)) fail('the page has no ' + s + ' section');
      if ((await fetch(`http://${b}/no-such-thing`)).status !== 404) fail('an unknown path is not 404');
    });
  },
  // the health endpoint as dev/sim/extensions/pressure.edn changes it: the
  // server's uptime and package.json's version beside ok
  async health2() {
    await withServer(async (b) => {
      const h = await (await fetch(`http://${b}/api/health`)).json();
      const version = JSON.parse(fs.readFileSync('package.json', 'utf8')).version;
      if (h.ok !== true) fail('/api/health did not answer ok:true: ' + JSON.stringify(h));
      if (!Number.isInteger(h.uptime) || h.uptime < 0) fail('/api/health has no whole-second uptime: ' + JSON.stringify(h));
      if (h.version !== version) fail(`/api/health's version is not package.json's ${version}: ` + JSON.stringify(h));
    });
  },
  async term() {
    await withServer(async (b) => {
      const c = await term(`ws://${b}/term`);
      const mark = 'MARK' + Math.floor(Math.random() * 1e9);
      c.send({ t: 'i', d: `echo ${mark}\r` });
      if (!(await until(c, mark + '\r\n', 5000)) && !(await until(c, mark + '\n', 100))) fail('no echo of the marker within 5 s; got: ' + c.out.slice(-200));
      c.ws.close();
    });
  },
  async resize() {
    await withServer(async (b) => {
      const id = 'sim' + Math.floor(Math.random() * 1e9);
      const c = await term(`ws://${b}/term?id=${id}`);
      c.send({ t: 'r', cols: 100, rows: 30 });
      await sleep(300);
      c.send({ t: 'i', d: 'stty size\r' });
      if (!(await until(c, '30 100', 5000))) fail('stty size is not "30 100" after a resize; got: ' + c.out.slice(-200));
      const mark = 'REPLAY' + Math.floor(Math.random() * 1e9);
      c.send({ t: 'i', d: `echo ${mark}\r` });
      await until(c, mark + '\r\n', 3000);
      c.ws.close();
      await sleep(300);
      const again = await term(`ws://${b}/term?id=${id}`);
      if (!(await until(again, mark, 3000))) fail('reconnecting with the same id did not replay the earlier output');
      again.ws.close();
    });
  },
  async ui() {
    await withServer(async (b) => {
      const page = await (await fetch(`http://${b}/`)).text();
      if (!/xterm/i.test(page)) fail('the page does not load xterm.js');
      // the page and every local script and stylesheet it loads, all served;
      // the badge may be in the page or made by a script (sim run 0958: term.js
      // sets data-status, and a check of the HTML alone called that missing)
      const srcs = [...page.matchAll(/(?:src|href)=["']([^"']+\.(?:js|css))["']/g)].map((m) => m[1]).filter((s) => !/^https?:/.test(s));
      let text = page;
      for (const s of srcs) {
        const r = await fetch(`http://${b}${s.startsWith('/') ? '' : '/'}${s}`);
        if (!r.ok) fail(`the page's ${s} is not served (${r.status})`);
        text += await r.text();
      }
      if (!/data-status|dataset\.status\b/.test(text)) fail('the Terminal section has no status badge (data-status), in the page or a script it loads');
    });
  },
  async files() {
    await withServer(async (b) => {
      const name = 'sim-note-' + Math.floor(Math.random() * 1e6) + '.txt';
      try {
        // the body as the plan's backlog says: the file's text, raw; a JSON
        // {"content": text} is taken too — run 1054's agent read the spec so
        let put = await fetch(`http://${b}/api/file?path=${name}`, { method: 'PUT', body: 'hello from the sim' });
        if (put.status === 400) {
          put = await fetch(`http://${b}/api/file?path=${name}`, { method: 'PUT', headers: { 'content-type': 'application/json' },
                                                                   body: JSON.stringify({ content: 'hello from the sim' }) });
        }
        if (!put.ok) fail('PUT /api/file failed: ' + put.status);
        const got = await (await fetch(`http://${b}/api/file?path=${name}`)).text();
        if (!got.includes('hello from the sim')) fail('GET /api/file did not return what was PUT: ' + got.slice(0, 100));
        const list = await (await fetch(`http://${b}/api/files?path=.`)).text();
        if (!list.includes(name)) fail('GET /api/files does not list the new file');
        for (const bad of ['../..', '/etc']) {
          const st = (await fetch(`http://${b}/api/files?path=${encodeURIComponent(bad)}`)).status;
          if (st !== 400) fail(`GET /api/files?path=${bad} is ${st}, not 400`);
          const st2 = (await fetch(`http://${b}/api/file?path=${encodeURIComponent(bad + '/passwd')}`)).status;
          if (st2 !== 400) fail(`GET /api/file outside the project is ${st2}, not 400`);
        }
      } finally { try { fs.unlinkSync(name); } catch {} }
    });
  },
};

// the page and every local script and stylesheet it loads, as one text
async function pageText(b) {
  // the page, every local script and stylesheet it names, and every module
  // those import, transitively: a page that loads only app.js, which imports
  // the rest, read as app.js alone once (a mode B run's did)
  const page = await (await fetch(`http://${b}/`)).text();
  const queue = [...page.matchAll(/(?:src|href)=["']([^"']+\.(?:js|css|mjs))["']/g)]
    .map((m) => m[1]).filter((s) => !/^https?:|^\/\//.test(s)).map((s) => new URL(s, `http://${b}/`).pathname);
  const seen = new Set();
  let text = page;
  while (queue.length) {
    const path = queue.shift();
    if (seen.has(path)) continue;
    seen.add(path);
    const r = await fetch(`http://${b}${path}`);
    if (!r.ok) continue;
    const src = await r.text();
    text += '\n' + src;
    if (/\.m?js$/.test(path)) {
      for (const m of src.matchAll(/(?:\bfrom\s*|\bimport\s*\(?\s*)["'](\.{0,2}\/[^"']+)["']/g)) {
        queue.push(new URL(m[1], `http://${b}${path}`).pathname);
      }
    }
  }
  return { page, text };
}

checks.chat = async function chat() {
  await withServer(async (b) => {
    const { page, text } = await pageText(b);
    if (!/data-section=["']chat["']|id=["']chat["']/.test(page)) fail('the page has no Chat section (data-section="chat" or id="chat")');
    const r = await fetch(`http://${b}/api/chat`, { method: 'POST', headers: { 'content-type': 'application/json' },
                                                     body: JSON.stringify({ message: 'ping from the sim' }) });
    if (!r.ok) fail('POST /api/chat failed: ' + r.status);
    if (!/text\/event-stream/.test(r.headers.get('content-type') || '')) fail('POST /api/chat is not text/event-stream: ' + r.headers.get('content-type'));
    const body = await r.text();
    if (!/^data:/m.test(body)) fail('the chat stream has no data: lines');
    if (!/^event:\s*done/m.test(body)) fail('the chat stream does not end with event: done');
    // the reply is what the data: lines carry, joined: a stream may send it a
    // word or a token at a time (a first run's did, and failed here)
    // a payload may be JSON ({"d":"ping "}): its strings are what it says
    const text1 = (p) => { try { const v = JSON.parse(p); return typeof v === 'string' ? v : (v && typeof v === 'object' ? Object.values(v).filter((x) => typeof x === 'string').join('') : p); } catch { return p; } };
    const said = body.split(/\r?\n/).filter((l) => l.startsWith('data:')).map((l) => text1(l.slice(5).replace(/^ /, ''))).join('');
    if (!said.includes('ping from the sim') && !body.includes('ping from the sim')) fail('the echo stub does not answer the message: ' + JSON.stringify(said.slice(0, 80)));
    if (!/EventSource|text\/event-stream|getReader\(/.test(text)) fail('the page does not read the chat stream');
  });
};

checks.shell = async function shell() {
  await withServer(async (b) => {
    const { page, text } = await pageText(b);
    // a hook may be in the markup or set from a script: data-term-id is
    // also tab.dataset.termId (a first run's tabs were, and failed here)
    const need = [
      [/role=["']menubar["']/, 'a top menu (role="menubar")'],
      [/role=["']tablist["']/, 'tabs (role="tablist")'],
      [/role=["']tab["']/, 'tab elements (role="tab")'],
      [/data-term-id|dataset\.termId/, 'terminal tabs carrying data-term-id'],
      [/data-action=["']new-terminal["']|dataset\.action\s*=\s*["']new-terminal/, 'a new-terminal action (data-action="new-terminal")'],
      [/data-file|dataset\.file\b/, 'editor tabs carrying data-file'],
      [/data-action=["']toggle-theme["']|dataset\.action\s*=\s*["']toggle-theme/, 'a theme toggle (data-action="toggle-theme")'],
      [/data-theme|dataset\.theme\b/, 'a data-theme on the document'],
      [/localStorage/, 'the theme remembered in localStorage'],
      [/hashchange|location\.hash/, 'sections switched by the URL hash'],
      [/\.hidden\s*=|setAttribute\(\s*["']hidden|removeAttribute\(\s*["']hidden|toggleAttribute\(\s*["']hidden|\bhidden\b/, 'inactive sections hidden'],
      [/@media/, 'a layout for narrow screens (@media)'],
    ];
    const missing = need.filter(([re]) => !re.test(text)).map(([, what]) => what);
    if (missing.length) fail('the UI shell lacks: ' + missing.join('; '));
    // two terminals with their own ids are two shells
    const t1 = await term(`ws://${b}/term?id=simA${Date.now()}`);
    const t2 = await term(`ws://${b}/term?id=simB${Date.now()}`);
    t1.send({ t: 'i', d: 'echo ONE$((1+1))\r' });
    t2.send({ t: 'i', d: 'echo TWO$((2+2))\r' });
    if (!(await until(t1, 'ONE2', 5000)) || !(await until(t2, 'TWO4', 5000))) fail('two terminals with their own ids do not each answer');
    if (t1.out.includes('TWO4')) fail("one terminal's output reached another");
    t1.ws.close(); t2.ws.close();
  });
};

const which = process.argv[2];
if (!checks[which]) fail('no check ' + which + ' — one of ' + Object.keys(checks).join(', '));
checks[which]().then(() => { console.log('ok ' + which); process.exit(0); }, (e) => fail(e.message));
