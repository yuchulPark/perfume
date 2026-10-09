import { test, expect } from '@playwright/test';

// Synthetic DTO fixtures exist only in tests. They are never bundled into the application.
const brands = [{ id: 1, name: 'Creed', brandSlug: 'creed' }, { id: 2, name: 'Kierin NYC', brandSlug: 'kierin' }];
const summaries = Array.from({ length: 20 }, (_, i) => ({ id: i + 1, publicId: `test-${i + 1}`, fragranceSlug: `test-${i + 1}`,
  name: `검증용 향수 ${i + 1}`, releaseYear: i === 0 ? null : 2010, imageUrl: null, brand: brands[0] }));
const pageDto = (content = summaries, page = 0, total = 41) => ({ content, page, size: 20, totalElements: total,
  totalPages: Math.ceil(total / 20), first: page === 0, last: page >= Math.ceil(total / 20) - 1 });
const detailDto = {
  id: 1, publicId: 'test-1', fragranceSlug: 'test-1', name: '검증용 향수 1', brand: brands[0],
  description: null, releaseYear: null, imageUrl: null, reviewsCount: 0,
  notes: { top: [{ id: 1, name: '검증용 노트', position: 0 }], middle: [], base: [], unlayered: [{ id: 2, name: '기타 검증 노트', position: 0 }] },
  accords: [{ id: 1, name: 'woody', percentage: 50, score: .5, position: 0 }],
  perfumers: [{ id: 1, publicId: 'test-perfumer', name: '검증용 조향사', company: null, biography: null, perfumesCount: null }],
  metrics: {
    'identity.rating': { score: .91, category: 'liked', nRecords: 0 },
    'identity.gender': { score: .8, category: 'unisex' },
    'wear_summary.longevity': { score: 0, nRecords: 0 },
    'performance.sillage': { score: .6, category: 'strong' },
    'performance.projection': { score: .7, category: 'noticeable' },
    'performance.season.primary': { label: 'spring', score: null, category: null, nRecords: null },
    'wear_summary.season.by_season.spring': { score: .83, category: 'suitable' },
    'wear_summary.time_of_day': { score: .65, category: 'versatile' },
    'appreciation': { score: .82, nRecords: 24 },
    'price_value': { score: .51, category: 'fair' },
  },
  pros: [{ text: '검증용 장점', position: 0 }], cons: [],
  similarFragrances: [{ publicId: null, fragranceSlug: 'test-related', name: '유사 향수 검증', likeRatio: .8, nRecords: null, position: 0 }],
};

async function mockApi(page, handler) {
  const calls = [];
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url()); calls.push(url.pathname + url.search);
    if (handler && await handler(route, url)) return;
    const body = url.pathname === '/api/brands' ? brands : /^\/api\/perfumes\/\d+$/.test(url.pathname) ? detailDto
      : pageDto(summaries, Number(url.searchParams.get('page') || 0));
    await route.fulfill({ json: body });
  });
  return calls;
}

test('20 cards, placeholders, paging and detail navigation retain cached list filters', async ({ page }, testInfo) => {
  const errors = []; page.on('pageerror', (error) => errors.push(error.message));
  const calls = await mockApi(page);
  await page.goto('/');
  await expect(page.locator('.perfume-card')).toHaveCount(20);
  await expect(page.locator('.image-placeholder')).toHaveCount(20);
  await expect(page.getByText('총 41개의 향수')).toBeVisible();
  expect(calls.filter((url) => url.startsWith('/api/perfumes?'))).toHaveLength(1);
  expect(calls.filter((url) => url === '/api/brands')).toHaveLength(1);
  await page.screenshot({ path: testInfo.outputPath('catalog.png'), fullPage: true });
  await page.getByRole('button', { name: '다음 페이지' }).click();
  await expect(page).toHaveURL(/page=1/);
  await expect.poll(() => calls.filter((url) => url.includes('page=1')).length).toBe(1);
  await page.locator('.perfume-card').first().click();
  await expect(page.getByRole('heading', { level: 1, name: '검증용 향수 1' })).toBeVisible();
  await page.getByRole('link', { name: '향수 목록으로' }).first().click();
  await expect(page).toHaveURL(/page=1/);
  await expect(page.locator('.perfume-card')).toHaveCount(20);
  expect(calls.filter((url) => url.includes('page=1'))).toHaveLength(1);
  expect(errors).toEqual([]);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
});

test('debounced search makes one request and brand changes reset pagination', async ({ page }) => {
  const calls = await mockApi(page); await page.goto('/?page=2');
  await expect(page.locator('.perfume-card')).toHaveCount(20);
  const input = page.getByRole('searchbox');
  await input.pressSequentially('Creed', { delay: 35 });
  await expect.poll(() => calls.filter((url) => url.startsWith('/api/perfumes/search')).length).toBe(1);
  await expect(page).not.toHaveURL(/page=2/);
  await page.waitForTimeout(450);
  expect(calls.filter((url) => url.startsWith('/api/perfumes/search'))).toHaveLength(1);
  await page.getByLabel('브랜드', { exact: true }).selectOption('kierin');
  await expect.poll(() => calls.filter((url) => url.includes('brandSlug=kierin')).length).toBe(1);
  await expect(page).toHaveURL(/brandSlug=kierin/);
  await page.getByRole('button', { name: '검색 초기화' }).click();
  await expect(input).toHaveValue(''); await expect(page).toHaveURL('http://127.0.0.1:4173/');
});

test('direct detail shows every supported section, wear fallbacks, zero values and missing data', async ({ page }, testInfo) => {
  const calls = await mockApi(page); await page.goto('/perfumes/1');
  await expect(page.getByRole('heading', { level: 1, name: '검증용 향수 1' })).toBeVisible();
  await expect(page.getByText('등록된 설명이 없습니다.')).toBeVisible();
  await expect(page.getByText('남녀 공용', { exact: true })).toBeVisible();
  await expect(page.getByText('0개', { exact: true })).toBeVisible();
  await expect(page.locator('#performance .metric-card').first()).toContainText('0');
  await expect(page.locator('#performance .metric-card').first()).toContainText('평가 0건');
  for (const id of ['notes', 'accords', 'perfumers', 'performance', 'wear', 'appreciation', 'opinions', 'similarities']) await expect(page.locator(`#${id}`)).toBeVisible();
  await expect(page.locator('#wear')).toContainText('0.83'); await expect(page.locator('#appreciation')).toContainText('0.82');
  await expect(page.locator('#wear .soft-badge')).toHaveText('주요 계절 · 봄');
  await expect(page.locator('#opinions')).toContainText('검증용 장점');
  await expect(page.getByRole('link', { name: '유사 향수 검증 이름으로 찾아보기' })).toHaveAttribute('href', '/?q=%EC%9C%A0%EC%82%AC%20%ED%96%A5%EC%88%98%20%EA%B2%80%EC%A6%9D');
  expect(calls).toEqual(['/api/perfumes/1']);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ path: testInfo.outputPath('detail.png'), fullPage: true });
});

test('errors make no automatic retries and manual retry recovers', async ({ page }) => {
  let fail = true;
  const calls = await mockApi(page, async (route, url) => {
    if (url.pathname === '/api/perfumes' && fail) { await route.fulfill({ status: 503, json: { detail: 'private internal error' } }); return true; }
    return false;
  });
  await page.goto('/'); await expect(page.getByRole('alert')).toBeVisible();
  await expect(page.getByRole('alert')).not.toContainText('private internal error');
  await page.waitForTimeout(500); expect(calls.filter((url) => url.startsWith('/api/perfumes?'))).toHaveLength(1);
  fail = false; await page.getByRole('button', { name: '다시 시도', exact: true }).click();
  await expect(page.locator('.perfume-card')).toHaveCount(20);
  expect(calls.filter((url) => url.startsWith('/api/perfumes?'))).toHaveLength(2);
});

test('empty search, missing detail and malformed detail IDs are handled naturally', async ({ page }) => {
  const calls = await mockApi(page, async (route, url) => {
    if (url.pathname === '/api/perfumes/search') { await route.fulfill({ json: pageDto([], 0, 0) }); return true; }
    if (url.pathname === '/api/perfumes/999') { await route.fulfill({ status: 404, json: { status: 404 } }); return true; }
    return false;
  });
  await page.goto('/?q=empty'); await expect(page.getByRole('heading', { name: '검색 결과가 없어요' })).toBeVisible();
  await page.goto('/perfumes/999'); await expect(page.getByRole('heading', { name: '이 향수를 찾을 수 없어요' })).toBeVisible();
  await page.goto('/perfumes/not-an-id'); await expect(page.getByRole('heading', { name: '이 향수를 찾을 수 없어요' })).toBeVisible();
  expect(calls.filter((url) => url.includes('not-an-id'))).toHaveLength(0);
});

test('a delayed older search never replaces a newer response', async ({ page }) => {
  await mockApi(page, async (route, url) => {
    if (url.pathname !== '/api/perfumes/search') return false;
    const keyword = url.searchParams.get('q');
    if (keyword === 'first') await new Promise((resolve) => setTimeout(resolve, 900));
    await route.fulfill({ json: pageDto([{ ...summaries[0], name: keyword === 'first' ? '이전 결과 검증' : '최신 결과 검증' }], 0, 1) }).catch(() => {});
    return true;
  });
  await page.goto('/'); await expect(page.locator('.perfume-card')).toHaveCount(20);
  await page.getByRole('searchbox').fill('first'); await page.getByRole('button', { name: '검색', exact: true }).click();
  await expect(page).toHaveURL(/q=first/);
  await page.getByRole('searchbox').fill('second'); await page.getByRole('button', { name: '검색', exact: true }).click();
  await expect(page.getByRole('heading', { name: '최신 결과 검증' })).toBeVisible();
  await page.waitForTimeout(1100); await expect(page.getByRole('heading', { name: '이전 결과 검증' })).toHaveCount(0);
});

test('a failed image falls back without blocking cards and detail survives entirely null optional data', async ({ page }) => {
  await page.route('**/broken-test-image.png', (route) => route.fulfill({ status: 404, body: '' }));
  await mockApi(page, async (route, url) => {
    if (url.pathname === '/api/perfumes') { await route.fulfill({ json: pageDto([{ ...summaries[0], imageUrl: '/broken-test-image.png' }], 0, 1) }); return true; }
    if (url.pathname === '/api/perfumes/1') {
      await route.fulfill({ json: { ...detailDto, brand: null, notes: null, accords: null, perfumers: null, metrics: null, pros: null, cons: null, similarFragrances: null } }); return true;
    }
    return false;
  });
  await page.goto('/'); await expect(page.getByRole('img', { name: '검증용 향수 1 이미지 없음', exact: true })).toBeVisible();
  await page.locator('.perfume-card').click();
  await expect(page.getByText('브랜드 정보 없음', { exact: true })).toBeVisible();
  await expect(page.locator('#notes')).toContainText('등록된 노트가 없습니다.');
  await expect(page.locator('#similarities')).toContainText('등록된 유사 향수 정보가 없습니다.');
});
