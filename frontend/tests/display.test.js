import test from 'node:test';
import assert from 'node:assert/strict';
import { catalogPath, ApiError, getBrands, getJson, getPerfumes } from '../src/api/client.js';
import { categoryLabel, findMetric, formatNumber, formatScore, metricFamily, normalizeId, paginationPages, readFilters, safeImageUrl, text } from '../src/lib/display.js';

test('catalog API uses the real DTO parameters and switches only keyword requests to search', () => {
  assert.equal(catalogPath(), '/api/perfumes?page=0&size=20');
  const url = new URL(catalogPath({ q: ' A% B_ ', brandSlug: 'creed', page: 2, size: 20 }), 'http://local');
  assert.equal(url.pathname, '/api/perfumes/search');
  assert.equal(url.searchParams.get('q'), 'A% B_');
  assert.equal(url.searchParams.get('brandSlug'), 'creed');
  assert.equal(url.searchParams.get('page'), '2');
  assert.equal(new URL(catalogPath({ brandSlug: 'creed' }), 'http://local').pathname, '/api/perfumes');
});

test('null values stay missing while supplied zero scores and counts remain zero', () => {
  assert.equal(formatNumber(null), '정보 없음'); assert.equal(formatScore(0), '0');
  assert.equal(formatNumber(4294967297), '4,294,967,297');
  assert.equal(formatScore(4.38), '4.38'); // No guessed normalization or five-star conversion.
  assert.equal(text('nan'), ''); assert.equal(text(null), '');
  assert.equal(categoryLabel('masculine'), '남성적인'); assert.equal(categoryLabel('provider_future_category'), 'provider_future_category');
});

test('metric lookup uses supported field paths and falls back only when the primary metric has no data', () => {
  const metrics = { 'performance.longevity': { score: null }, 'wear_summary.longevity': { score: 0 },
    'appreciation.love': { score: .8 }, 'appreciation.like': { nRecords: 0 }, 'price_value': { category: 'fair' } };
  assert.equal(findMetric(metrics, 'performance.longevity', 'wear_summary.longevity').score, 0);
  assert.equal(findMetric(metrics, 'identity.rating'), null);
  assert.equal(metricFamily(metrics, 'appreciation').length, 2);
  assert.equal(metricFamily(null, 'appreciation').length, 0);
});

test('URL filters preserve queries and reject invalid page offsets', () => {
  assert.deepEqual(readFilters(new URLSearchParams('q=Creed&brandSlug=creed&page=3')), { q: 'Creed', brandSlug: 'creed', page: 3, size: 20 });
  for (const value of ['-1', 'NaN', '1.5', '2147483647']) assert.equal(readFilters(new URLSearchParams(`page=${value}`)).page, 0);
  assert.deepEqual(paginationPages(0, 0), []); assert.deepEqual(paginationPages(9, 10), [5, 6, 7, 8, 9]);
});

test('detail identifiers keep long precision and unsafe image protocols use the placeholder', () => {
  assert.equal(normalizeId('0001'), '1'); assert.equal(normalizeId('9223372036854775807'), '9223372036854775807');
  for (const value of ['-1', '0', 'abc', '9223372036854775808']) assert.equal(normalizeId(value), null);
  assert.equal(safeImageUrl(null), ''); assert.equal(safeImageUrl('javascript:alert(1)'), '');
  assert.equal(safeImageUrl('https://example.invalid/image.jpg'), 'https://example.invalid/image.jpg');
});

test('HTTP errors become concise Korean messages without echoing raw server bodies', async () => {
  await assert.rejects(getJson('/api/perfumes/1', { fetcher: async () => new Response('private-server-diagnostic', { status: 404 }) }),
    (error) => error instanceof ApiError && error.status === 404 && !error.message.includes('private-server-diagnostic'));
  await assert.rejects(getJson('/api/perfumes', { fetcher: async () => { throw new Error('private-network-diagnostic'); } }),
    (error) => error instanceof ApiError && error.status === 0 && !error.message.includes('private-network-diagnostic'));
});

test('client rejects malformed brand and page shapes instead of displaying made-up rows', async () => {
  const fetcher = async () => Response.json({ unexpected: true });
  await assert.rejects(getBrands({ fetcher }), ApiError);
  await assert.rejects(getPerfumes({}, { fetcher }), ApiError);
  await assert.rejects(getJson('/api/brands', { fetcher: async () => new Response('<html>not JSON</html>') }), ApiError);
});

test('caller cancellation is preserved rather than surfaced as a network error', async () => {
  const controller = new AbortController(); controller.abort();
  const aborted = new DOMException('cancelled', 'AbortError');
  await assert.rejects(getJson('/api/perfumes', { signal: controller.signal, fetcher: async (_, options) => {
    assert.equal(options.signal.aborted, true); throw aborted;
  } }), (error) => error === aborted);
});
