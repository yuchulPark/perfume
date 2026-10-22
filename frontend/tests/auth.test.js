import test from 'node:test';
import assert from 'node:assert/strict';
import { safeReturnPath } from '../src/api/auth.js';
import { recommendationFailure, getRecommendationJob } from '../src/api/recommendations.js';
import { RecommendationJobSession } from '../src/lib/recommendationJobs.js';

test('login return targets remain local and never loop back into login', () => {
  for (const value of ['https://other.example', '//other.example', '/\\other', '/\t/other.example', '/login', '/login?x=1', '/signup', undefined])
    assert.equal(safeReturnPath(value), '/recommend');
  assert.equal(safeReturnPath('/perfumes/12'), '/perfumes/12');
});
test('401 and 403 have distinct safe errors and never expose server text', async () => {
  assert.equal(recommendationFailure(401).kind, 'auth');
  assert.equal(recommendationFailure(403).kind, 'forbidden');
  await assert.rejects(getRecommendationJob(null, 'a'.repeat(64), { fetcher: async () => Response.json({ detail: 'private' }, { status: 401 }) }),
    (error) => error.kind === 'auth' && !error.message.includes('private'));
});
test('expired session stops polling and preserves an existing capability for explicit login recovery', async () => {
  const values = new Map();
  const session = new RecommendationJobSession({ storage: { getItem: (key) => values.get(key), setItem: (key, value) => values.set(key, value), removeItem: (key) => values.delete(key) },
    token: () => 'a'.repeat(64), create: async () => ({ jobId: '11111111-1111-4111-8111-111111111111', state: 'RUNNING' }),
    get: async () => { throw recommendationFailure(401); } });
  session.mount();
  try {
    await session.start('private-question', 5); await session.poll();
    assert.equal(session.state.status, 'unauthenticated'); assert.ok(session.handle.id);
    assert.equal(session.state.error.kind, 'auth'); assert.ok(!JSON.stringify([...values]).includes('private-question'));
  } finally { session.unmount(); }
});
