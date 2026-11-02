import test from 'node:test';
import assert from 'node:assert/strict';
import { catalogPath, getPerfumes } from '../src/api/client.js';

test('advanced keywords retain the existing route, Korean text, brand filter and paging contract', async () => {
  const filters = { q: ' 장미 woody ', brandSlug: 'creed', page: 2, size: 20 };
  const expected = { content: [{ id: 7, name: 'Aventus', brand: { id: 1, name: 'Creed', brandSlug: 'creed' } }],
    page: 2, size: 20, totalElements: 41, totalPages: 3, first: false, last: true };
  const result = await getPerfumes(filters, { fetcher: async (path) => {
    const url = new URL(path, 'http://local.test');
    assert.equal(url.pathname, '/api/perfumes/search');
    assert.equal(url.searchParams.get('q'), '장미 woody');
    assert.equal(url.searchParams.get('brandSlug'), 'creed');
    assert.equal(url.searchParams.get('page'), '2');
    return { ok: true, json: async () => expected };
  } });
  assert.deepEqual(result, expected);
});

test('the same client accepts PostgreSQL fallback without engine-specific response fields', async () => {
  const expected = { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0, first: true, last: true };
  assert.deepEqual(await getPerfumes({ q: 'avntus' }, { fetcher: async () => ({ ok: true, json: async () => expected }) }), expected);
  assert.equal(catalogPath({ brandSlug: 'creed' }), '/api/perfumes?page=0&size=20&brandSlug=creed');
});
