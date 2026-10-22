import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';

// Explicit local-only verification: never reads .env, remote URLs, or existing credentials.
const viaVite = process.argv.includes('--via-vite');
const viaProxy = viaVite || process.argv.includes('--via-proxy');
const base = viaVite ? 'http://127.0.0.1:5173' : viaProxy ? 'http://127.0.0.1:4173' : 'http://127.0.0.1:8081';
const root = new URL('../', import.meta.url);
const results = [];
async function check(name, operation) {
  try { await operation(); results.push({ name, status: 'passed' }); console.log(`PASS: ${name}`); }
  catch (error) { results.push({ name, status: 'failed', message: error.message }); throw error; }
}
const request = (path, options = {}) => fetch(base + path, { ...options,
  headers: { ...options.headers, ...(viaProxy && options.method === 'POST' ? { Origin: base } : {}) },
  redirect: 'manual', signal: AbortSignal.timeout(15_000) });
function cookieFrom(response) {
  const value = response.headers.getSetCookie().find((item) => item.startsWith('JSESSIONID='));
  assert.ok(value, 'Expected a session cookie'); return value;
}
const cookieValue = (value) => value.split(';')[0];
const recommend = '/api/ai/perfumes/recommend';
const job = '11111111-1111-4111-8111-111111111111';
const routes = [[recommend, 'POST'], [`${recommend}/jobs`, 'POST'], [`${recommend}/jobs`, 'GET'],
  [`${recommend}/jobs/${job}`, 'GET'], [`${recommend}/jobs/${job}/cancel`, 'POST']];
try {
  await check('physical cluster evidence and synthetic catalog precondition', async () => {
    const identity = JSON.parse((await readFile(new URL('target/auth-local-check/isolation.json', root), 'utf8')).replace(/^\uFEFF/, ''));
    assert.ok(identity.data_directory.replaceAll('\\', '/').endsWith('/perfume/target/auth-local-check/data'));
    assert.equal(identity.server_addr, '127.0.0.1'); assert.equal(identity.server_port, 25432);
    const response = await request('/api/perfumes'); assert.equal(response.status, 200);
    const catalog = await response.json(); assert.equal(catalog.content.length, 2);
    assert.ok(catalog.content.every((item) => item.publicId.startsWith('local-auth-check-')));
    assert.deepEqual(await (await request('/api/auth/me')).json(), { enabled: true, user: null });
  });
  await check('anonymous catalog list, detail, brands, search and backend healthcheck path', async () => {
    for (const path of ['/api/perfumes?size=1', '/api/perfumes/1', '/api/brands', '/api/perfumes/search?q=local', '/api/perfumes?size=1']) {
      const response = await request(path); assert.equal(response.status, 200, path); await response.json();
    }
  });
  for (const [path, method] of routes) await check(`anonymous ${method} ${path} returns JSON 401`, async () => {
    const response = await request(path, { method }); assert.equal(response.status, 401);
    assert.equal((await response.json()).code, 'AUTH_REQUIRED'); assert.equal(response.headers.get('location'), null);
  });
  let beforeCookie, beforeCsrf, sessionCookie, currentCsrf;
  await check('CSRF issuance and local HttpOnly/SameSite session cookie', async () => {
    const response = await request('/api/auth/csrf'); assert.equal(response.status, 200);
    const cookie = cookieFrom(response); assert.match(cookie, /HttpOnly/i); assert.match(cookie, /SameSite=Lax/i);
    assert.doesNotMatch(cookie, /;\s*Secure(?:;|$)/i);
    beforeCookie = cookieValue(cookie); beforeCsrf = await response.json();
    assert.equal(beforeCsrf.headerName, 'X-CSRF-TOKEN'); assert.ok(beforeCsrf.token);
  });
  const username = 'admin';
  const loginOptions = (id, password, csrf = beforeCsrf.token) => ({ method: 'POST', headers: { Cookie: beforeCookie,
    ...(csrf ? { 'X-CSRF-TOKEN': csrf } : {}), 'Content-Type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams({ username: id, password }) });
  await check('login without CSRF returns 403', async () => {
    assert.equal((await request('/api/auth/login', loginOptions(username, username, null))).status, 403);
  });
  await check('wrong password and unknown user return the same safe 401 error', async () => {
    for (const id of [username, 'missing-local-user']) {
      const response = await request('/api/auth/login', loginOptions(id, crypto.randomUUID()));
      assert.equal(response.status, 401); assert.deepEqual(await response.json(), { status: 401, code: 'INVALID_CREDENTIALS' });
    }
  });
  await check('development login succeeds and rotates the session ID', async () => {
    const response = await request('/api/auth/login', loginOptions(username, username));
    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), { enabled: true, user: { username, role: 'ADMIN' } });
    sessionCookie = cookieValue(cookieFrom(response)); assert.notEqual(sessionCookie, beforeCookie);
  });
  await check('server session restores identity on repeated me requests', async () => {
    for (let index = 0; index < 2; index++) {
      const response = await request('/api/auth/me', { headers: { Cookie: sessionCookie } });
      assert.deepEqual(await response.json(), { enabled: true, user: { username, role: 'ADMIN' } });
    }
  });
  const mutation = (token) => ({ method: 'POST', headers: { Cookie: sessionCookie,
    ...(token ? { 'X-CSRF-TOKEN': token } : {}), 'Content-Type': 'application/json', 'X-Recommendation-Token': 'a'.repeat(64) },
    body: JSON.stringify({ query: 'local verification without inference', topK: 5 }) });
  await check('authenticated mutation rejects missing and pre-login CSRF tokens', async () => {
    assert.equal((await request(`${recommend}/jobs`, mutation())).status, 403);
    assert.equal((await request(`${recommend}/jobs`, mutation(beforeCsrf.token))).status, 403);
    const response = await request('/api/auth/csrf', { headers: { Cookie: sessionCookie } });
    currentCsrf = await response.json(); assert.ok(currentCsrf.token);
  });
  for (const [path, method] of routes) await check(`authenticated ${method} ${path} passes auth; AI remains disabled`, async () => {
    const options = method === 'POST' ? mutation(currentCsrf.token) : { headers: { Cookie: sessionCookie, 'X-Recommendation-Token': 'a'.repeat(64) } };
    const response = await request(path, options); assert.equal(response.status, 503);
    assert.equal((await response.json()).code, 'RECOMMEND_DISABLED');
  });
  await check('logout requires CSRF and invalidates the cookie and server session', async () => {
    assert.equal((await request('/api/auth/logout', { method: 'POST', headers: { Cookie: sessionCookie } })).status, 403);
    const response = await request('/api/auth/logout', { method: 'POST', headers: { Cookie: sessionCookie, 'X-CSRF-TOKEN': currentCsrf.token } });
    assert.equal(response.status, 200); assert.match(response.headers.getSetCookie().join(';'), /Max-Age=0/);
    assert.equal((await request(`${recommend}/jobs`, { headers: { Cookie: sessionCookie } })).status, 401);
  });
  await check('post-logout csrf refresh and anonymous me remain available', async () => {
    const response = await request('/api/auth/csrf'); assert.equal(response.status, 200); assert.ok((await response.json()).token);
    assert.deepEqual(await (await request('/api/auth/me')).json(), { enabled: true, user: null });
  });
} finally {
  await writeFile(new URL(`target/auth-local-check/http${viaProxy ? '-proxy' : ''}-results.json`, root), JSON.stringify({ base, results }, null, 2));
}
console.log(`${results.length} local HTTP checks passed; no model inference was enabled.`);
