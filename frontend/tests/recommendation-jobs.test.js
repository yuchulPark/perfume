import test from 'node:test';
import assert from 'node:assert/strict';
import { createRecommendationJob, getRecommendationJob, cancelRecommendationJob, RecommendationError, recommendationFailure, JOB_HTTP_TIMEOUT_MS } from '../src/api/recommendations.js';
import { RecommendationJobSession, JOB_HANDLE_KEY, JOB_POLL_INTERVAL_MS, MAX_JOB_POLLS } from '../src/lib/recommendationJobs.js';

const id = '11111111-1111-4111-8111-111111111111', otherId = '22222222-2222-4222-8222-222222222222';
const token = 'a'.repeat(64);
const result = { answer: 'private-answer [1]', sources: [{ perfumeId: 7, name: '검증 향수', brandName: '검증 브랜드', similarity: .8 }], returnedCount: 1 };
const view = (state, extra = {}) => ({ jobId: id, state, result: state === 'SUCCEEDED' ? result : null, errorCode: null, errorStatus: null, ...extra });
const storage = () => { const items = new Map(); return { getItem: (key) => items.get(key) || null, setItem: (key, value) => items.set(key, value), removeItem: (key) => items.delete(key), items }; };
const flush = async () => { for (let i = 0; i < 12; i++) await Promise.resolve(); };
function session(t, options = {}) {
  const store = options.storage || storage();
  const instance = new RecommendationJobSession({ storage: store, token: () => token, ...options });
  instance.mount(); t.after(() => instance.unmount()); return { instance, store };
}

test('job API sends a capability header, exact DTO, no-store, and separate start/status/cancel methods', async () => {
  const calls = [];
  const fetcher = async (path, options) => { calls.push([path, options]); return Response.json(view('RUNNING')); };
  await createRecommendationJob({ query: '시트러스', topK: 4 }, token, { fetcher });
  await getRecommendationJob(id, token, { fetcher });
  await getRecommendationJob(null, token, { fetcher });
  await cancelRecommendationJob(id, token, { fetcher });
  assert.deepEqual(calls.map(([path, options]) => [path, options.method]), [
    ['/api/ai/perfumes/recommend/jobs', 'POST'], [`/api/ai/perfumes/recommend/jobs/${id}`, 'GET'],
    ['/api/ai/perfumes/recommend/jobs', 'GET'], [`/api/ai/perfumes/recommend/jobs/${id}/cancel`, 'POST'],
  ]);
  assert.deepEqual(JSON.parse(calls[0][1].body), { query: '시트러스', topK: 4 });
  for (const [, options] of calls) { assert.equal(options.headers['X-Recommendation-Token'], token); assert.equal(options.cache, 'no-store'); }
  assert.equal(calls[3][1].body, undefined);
});

test('job client rejects malformed states/results, a different job ID, and never displays untrusted failure text', async () => {
  for (const data of [{}, view('FAKE'), view('RUNNING', { result }), view('SUCCEEDED', { result: null }), view('FAILED')]) {
    await assert.rejects(getRecommendationJob(id, token, { fetcher: async () => Response.json(data) }), (error) => error.kind === 'response');
  }
  await assert.rejects(getRecommendationJob(id, token, { fetcher: async () => Response.json(view('RUNNING', { jobId: otherId })) }), (error) => error.kind === 'response');
  await assert.rejects(cancelRecommendationJob(id, token, { fetcher: async () => Response.json(view('RUNNING', { jobId: otherId })) }), (error) => error.kind === 'response');
  await assert.rejects(getRecommendationJob(id, token, { fetcher: async () => Response.json({ code: 'RECOMMEND_JOB_NOT_FOUND', detail: 'private-internal' }, { status: 404 }) }),
    (error) => error.kind === 'lost' && !error.message.includes('private-internal'));
  assert.equal(recommendationFailure(502, 'private-error-code').code, null);
});

test('short job HTTP timeout aborts a stalled exchange and does not become a long-running polling retry', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  let signal;
  const request = getRecommendationJob(id, token, { fetcher: (_, options) => new Promise((_, reject) => {
    signal = options.signal; signal.addEventListener('abort', () => reject(new Error('private-network')), { once: true });
  }) });
  const assertion = assert.rejects(request, (error) => error.kind === 'timeout');
  t.mock.timers.tick(JOB_HTTP_TIMEOUT_MS); await assertion; assert.equal(signal.aborted, true);
});

test('2s polling renders one success and persists only the opaque handle, never questions or results', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  let gets = 0;
  const { instance, store } = session(t, { create: async () => view('RUNNING'), get: async () => { gets++; return view('SUCCEEDED'); } });
  await instance.start('private-question', 5);
  const saved = JSON.parse(store.getItem(JOB_HANDLE_KEY));
  assert.deepEqual(saved, { token, id }); assert.ok(!store.getItem(JOB_HANDLE_KEY).includes('private-'));
  await instance.start('duplicate', 5); assert.equal(gets, 0);
  t.mock.timers.tick(JOB_POLL_INTERVAL_MS - 1); await flush(); assert.equal(gets, 0);
  t.mock.timers.tick(1); await flush(); assert.equal(gets, 1);
  assert.equal(instance.state.status, 'success'); assert.equal(instance.state.data, result); assert.equal(store.getItem(JOB_HANDLE_KEY), null);
});

test('cancellation stays pending until a terminal server confirmation and blocks resubmission', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  let creates = 0, state = 'CANCELLING';
  const { instance } = session(t, { create: async () => { creates++; return view('RUNNING'); }, cancel: async () => view('CANCELLING'), get: async () => view(state) });
  await instance.start('question', 5); await instance.cancel();
  assert.equal(instance.state.status, 'cancelling'); assert.equal(instance.state.confirmed, true);
  await instance.start('duplicate', 5); assert.equal(creates, 1);
  t.mock.timers.tick(2000); await flush(); assert.equal(instance.state.status, 'cancelling');
  state = 'CANCELLED'; t.mock.timers.tick(2000); await flush();
  assert.equal(instance.state.status, 'cancelled'); assert.equal(instance.handle, null);
  await instance.start('new', 5); assert.equal(creates, 2);
});

test('cancel during creation is delivered once the server ID arrives', async (t) => {
  let created, cancels = 0;
  const { instance } = session(t, { create: () => new Promise((resolve) => { created = resolve; }), cancel: async () => { cancels++; return view('CANCELLED'); } });
  const pending = instance.start('question', 5); instance.cancel();
  assert.equal(instance.state.status, 'cancelling'); assert.equal(cancels, 0);
  created(view('RUNNING')); await pending; await flush();
  assert.equal(cancels, 1); assert.equal(instance.state.status, 'cancelled');
});

test('network errors freeze status, keep the handle, and require an explicit resume with no automatic retries', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  let gets = 0, fail = true;
  const { instance, store } = session(t, { create: async () => view('RUNNING'), get: async () => {
    gets++; if (fail) throw new RecommendationError(0, 'network'); return view('SUCCEEDED');
  } });
  await instance.start('question', 5); t.mock.timers.tick(2000); await flush();
  assert.equal(instance.state.status, 'unknown'); assert.ok(store.getItem(JOB_HANDLE_KEY));
  t.mock.timers.tick(60_000); await flush(); assert.equal(gets, 1);
  fail = false; await instance.resume(); assert.equal(instance.state.status, 'success'); assert.equal(gets, 2);
});

test('a lost creation response can be recovered by capability without resubmitting the question', async (t) => {
  const { instance } = session(t, { create: async () => { throw new RecommendationError(0, 'network'); }, get: async (jobId) => { assert.equal(jobId, null); return view('SUCCEEDED'); } });
  await instance.start('private-question', 5); assert.equal(instance.state.status, 'unknown');
  await instance.resume(); assert.equal(instance.state.status, 'success');
});

test('unmount and same-tab reload resume an existing handle, and a stale result cannot overwrite cancellation', async (t) => {
  const store = storage(); let finishOld;
  const original = session(t, { storage: store, create: async () => view('RUNNING'), get: () => new Promise((resolve) => { finishOld = resolve; }), cancel: async () => view('CANCELLED') }).instance;
  await original.start('private-question', 5); const pending = original.poll(); await original.cancel();
  finishOld(view('SUCCEEDED')); await pending; assert.equal(original.state.status, 'cancelled');
  await original.start('another-private-question', 5); original.unmount();
  const resumed = session(t, { storage: store, get: async (jobId) => { assert.equal(jobId, id); return view('SUCCEEDED'); } }).instance;
  await flush(); assert.equal(resumed.state.status, 'success'); assert.equal(store.getItem(JOB_HANDLE_KEY), null);
});

test('missing jobs are reported as lost; timed-out/failed jobs use fixed errors and release the handle', async (t) => {
  for (const [viewOrError, expected] of [
    [recommendationFailure(404, 'RECOMMEND_JOB_NOT_FOUND'), 'lost'],
    [view('TIMED_OUT', { errorCode: 'LLM_TIMEOUT', errorStatus: 504 }), 'error'],
    [view('FAILED', { errorCode: 'private', errorStatus: 502 }), 'error'],
  ]) {
    const { instance } = session(t, { create: async () => view('RUNNING'), get: async () => { if (viewOrError instanceof Error) throw viewOrError; return viewOrError; } });
    await instance.start('question', 5); await instance.poll();
    assert.equal(instance.state.status, expected); assert.equal(instance.handle, null);
    assert.ok(!instance.state.error.message.includes('private'));
  }
});

test('polling has a finite budget and resumes only after explicit action; ambiguous proxy failures keep the handle', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  let gets = 0;
  const { instance } = session(t, { create: async () => view('RUNNING'), get: async () => { gets++; return view('RUNNING'); } });
  await instance.start('question', 5);
  for (let i = 0; i < MAX_JOB_POLLS; i++) { t.mock.timers.tick(2000); await flush(); }
  assert.equal(instance.state.status, 'unknown'); const count = gets;
  t.mock.timers.tick(60_000); await flush(); assert.equal(gets, count);
  await instance.resume(); assert.equal(gets, count + 1);
  const failed = session(t, { create: async () => { throw recommendationFailure(502, 'GENERATION_FAILED'); } }).instance;
  await failed.start('question', 5); assert.equal(failed.state.status, 'unknown'); assert.ok(failed.handle);
});

test('cancel intent from a rejected create cannot cancel a later job; malformed saved IDs are discarded', async (t) => {
  let rejectFirst, creates = 0, cancels = 0;
  const { instance } = session(t, { create: () => ++creates === 1 ? new Promise((_, reject) => { rejectFirst = reject; }) : Promise.resolve(view('RUNNING')),
    cancel: async () => { cancels++; return view('CANCELLED'); } });
  const first = instance.start('first', 5); instance.cancel();
  rejectFirst(recommendationFailure(429, 'RECOMMEND_BUSY')); await first;
  assert.equal(instance.state.status, 'error'); await instance.start('second', 5);
  assert.equal(instance.state.status, 'loading'); assert.equal(cancels, 0);
  const store = storage(); store.setItem(JOB_HANDLE_KEY, JSON.stringify({ token, id: '-'.repeat(36) }));
  const restored = session(t, { storage: store, get: () => assert.fail('malformed handle must not poll') }).instance;
  assert.equal(restored.state.status, 'idle'); assert.equal(store.getItem(JOB_HANDLE_KEY), null);
});
