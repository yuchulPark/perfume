import test from 'node:test';
import assert from 'node:assert/strict';
import { DEFAULT_TOP_K, MAX_QUERY_LENGTH, RECOMMENDATION_TIMEOUT_MS, RecommendationError, recommendPerfumes, validateRecommendationInput } from '../src/api/recommendations.js';

const result = { answer: '저장된 정보를 기준으로 정리했어요. [1]', sources: [{ perfumeId: 7, name: '검증용 향수', brandName: '검증용 브랜드', similarity: .85 }], returnedCount: 1 };

test('recommendation POST sends the exact contract and preserves source order', async () => {
  let calls = 0;
  const response = { ...result, sources: [result.sources[0], { ...result.sources[0], perfumeId: 3, similarity: .95 }], returnedCount: 2 };
  assert.deepEqual(await recommendPerfumes({ query: '시트러스 향', topK: 8 }, { fetcher: async (path, options) => {
    calls++;
    assert.equal(path, '/api/ai/perfumes/recommend');
    assert.equal(options.method, 'POST');
    assert.equal(options.headers['Content-Type'], 'application/json');
    assert.deepEqual(JSON.parse(options.body), { query: '시트러스 향', topK: 8 });
    assert.ok(options.signal instanceof AbortSignal);
    return Response.json(response);
  } }), response);
  assert.equal(calls, 1);
  await recommendPerfumes({ query: '우디' }, { fetcher: async (_, options) => {
    assert.equal(JSON.parse(options.body).topK, DEFAULT_TOP_K); return Response.json(result);
  } });
});

test('input validation matches Java nonblank, UTF-16 length, and integer topK boundaries without a request', async () => {
  for (const query of ['', ' \t\n', '\u00a0\u3000', '\u001c\u001f', null, '가'.repeat(501), '🌿'.repeat(251)]) {
    assert.ok(validateRecommendationInput(query));
    await assert.rejects(recommendPerfumes({ query }, { fetcher: () => assert.fail('invalid input must not fetch') }),
      (error) => error.kind === 'invalid');
  }
  for (const topK of [0, 21, 1.5, '5', null, NaN]) assert.ok(validateRecommendationInput('우디', topK));
  assert.equal(validateRecommendationInput('가'.repeat(MAX_QUERY_LENGTH), 1), '');
  assert.equal(validateRecommendationInput('🌿'.repeat(250), 20), '');
  assert.equal(validateRecommendationInput('\u0085'), ''); // Java does not classify NEL as whitespace.
});

test('all backend fixed codes map safely; HTTP fallbacks handle HTML, unknown codes and mismatched status', async (t) => {
  const groups = [
    [400, 'invalid', ['INVALID_REQUEST']], [429, 'busy', ['RECOMMEND_BUSY']],
    [503, 'disabled', ['RECOMMEND_DISABLED']], [503, 'evidence', ['EVIDENCE_UNAVAILABLE']],
    [503, 'unavailable', ['RECOMMEND_INTERRUPTED', 'MODEL_MISMATCH', 'LLM_UNAVAILABLE', 'DATABASE_UNAVAILABLE']],
    [504, 'timeout', ['RECOMMEND_TIMEOUT', 'SEARCH_TIMEOUT', 'LLM_TIMEOUT', 'DATABASE_TIMEOUT']],
    [502, 'server', ['SEARCH_FAILED', 'LLM_HTTP_ERROR', 'LLM_INVALID_RESPONSE', 'GENERATION_FAILED']],
  ];
  for (const [status, kind, codes] of groups) for (const code of codes) await t.test(code, async () => {
    let calls = 0;
    await assert.rejects(recommendPerfumes({ query: '우디' }, { fetcher: async () => {
      calls++; return Response.json({ code, detail: 'private SQL/provider diagnostic', title: 'private title' }, { status });
    } }), (error) => error instanceof RecommendationError && error.status === status && error.kind === kind && error.code === code
      && !error.message.includes('private') && !error.title.includes('private'));
    assert.equal(calls, 1);
  });
  for (const [status, kind] of [[400, 'invalid'], [429, 'busy'], [502, 'server'], [503, 'unavailable'], [504, 'timeout'], [500, 'server']]) {
    await assert.rejects(recommendPerfumes({ query: '우디' }, { fetcher: async () => new Response('<html>private</html>', { status }) }),
      (error) => error.kind === kind && error.code === null);
  }
  await assert.rejects(recommendPerfumes({ query: '우디' }, { fetcher: async () => Response.json({ code: 'RECOMMEND_DISABLED' }, { status: 502 }) }),
    (error) => error.kind === 'server' && error.code === null);
  await assert.rejects(recommendPerfumes({ query: '우디' }, { fetcher: async () => Response.json({ code: 'private-code' }, { status: 503 }) }),
    (error) => error.kind === 'unavailable' && error.code === null);
});

test('empty results stay successful; malformed answer, count and unsafe source IDs are rejected', async () => {
  const empty = { answer: '저장된 후보에서 근거를 찾지 못했어요.', sources: [], returnedCount: 0 };
  assert.deepEqual(await recommendPerfumes({ query: '우디' }, { fetcher: async () => Response.json(empty) }), empty);
  for (const data of [null, {}, { ...result, answer: '' }, { ...result, sources: null }, { ...result, returnedCount: 2 },
    { ...result, sources: [{ ...result.sources[0], perfumeId: '../private' }] },
    { ...result, sources: [{ ...result.sources[0], perfumeId: Number.MAX_SAFE_INTEGER + 1 }] },
    { ...result, sources: [{ ...result.sources[0], similarity: null }] }]) {
    await assert.rejects(recommendPerfumes({ query: '우디' }, { fetcher: async () => Response.json(data) }), (error) => error.kind === 'response');
  }
  await assert.rejects(recommendPerfumes({ query: '우디' }, { fetcher: async () => new Response('bad JSON') }), (error) => error.kind === 'response');
});

test('AI requests outlive the GET 15s limit and backend 420s deadline, then abort at the dedicated 480s limit', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  let signal;
  let settled = false;
  const request = recommendPerfumes({ query: '우디' }, { fetcher: (_, options) => new Promise((_, reject) => {
    signal = options.signal;
    signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')), { once: true });
  }) });
  const assertion = assert.rejects(request, (error) => { settled = true; return error.kind === 'timeout'; });
  t.mock.timers.tick(15_000);
  await Promise.resolve(); assert.equal(signal.aborted, false); assert.equal(settled, false);
  t.mock.timers.tick(420_000 - 15_000);
  await Promise.resolve(); assert.equal(signal.aborted, false);
  t.mock.timers.tick(RECOMMENDATION_TIMEOUT_MS - 420_000);
  await assertion; assert.equal(signal.aborted, true);
});

test('caller cancellation aborts fetch and pre-aborted calls never start; body reads share the deadline', async (t) => {
  const preAborted = new AbortController(); preAborted.abort();
  await assert.rejects(recommendPerfumes({ query: '우디' }, { signal: preAborted.signal, fetcher: () => assert.fail('must not fetch') }),
    (error) => error.name === 'AbortError');
  const controller = new AbortController();
  const pending = recommendPerfumes({ query: '우디' }, { signal: controller.signal, fetcher: (_, { signal }) => new Promise((_, reject) => {
    signal.addEventListener('abort', () => reject(new Error('private cancellation diagnostic')), { once: true });
  }) });
  controller.abort();
  await assert.rejects(pending, (error) => error.name === 'AbortError' && !error.message.includes('private'));

  t.mock.timers.enable({ apis: ['setTimeout'] });
  const bodyPending = recommendPerfumes({ query: '우디' }, { fetcher: async (_, { signal }) => ({
    ok: true, status: 200, json: () => new Promise((_, reject) => signal.addEventListener('abort', () => reject(new Error('private body diagnostic')), { once: true })),
  }) });
  await Promise.resolve(); // Headers arrive before the body stalls.
  const assertion = assert.rejects(bodyPending, (error) => error.kind === 'timeout');
  t.mock.timers.tick(RECOMMENDATION_TIMEOUT_MS);
  await assertion;
});

test('network failures do not retain provider messages or retry', async () => {
  let calls = 0;
  await assert.rejects(recommendPerfumes({ query: '우디' }, { fetcher: async () => { calls++; throw new Error('private connection diagnostic'); } }),
    (error) => error.kind === 'network' && !error.message.includes('private'));
  assert.equal(calls, 1);
});
