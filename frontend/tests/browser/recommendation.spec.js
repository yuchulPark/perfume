import { test, expect } from '@playwright/test';

const endpoint = '/api/ai/perfumes/recommend';
const result = {
  answer: '저장된 정보를 기준으로 정리했어요.\n검증용 첫 향수는 시트러스 노트가 있어요. [1]\n검증용 둘째 향수도 검색된 후보예요. [2]\n임베딩 생성 당시의 정보로 최신 정보와 다를 수 있어요.',
  sources: [
    { perfumeId: 9, name: '검증용 첫 향수', brandName: '테스트 브랜드 A', similarity: .7 },
    { perfumeId: 2, name: '검증용 둘째 향수', brandName: '테스트 브랜드 B', similarity: .9 },
  ], returnedCount: 2,
};

async function mockApi(page, recommend = (route) => route.fulfill({ json: result })) {
  const calls = [];
  // All API traffic is intercepted, including unexpected routes; never fall through to a real backend.
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path === endpoint) {
      calls.push({ method: route.request().method(), body: route.request().postDataJSON() });
      await recommend(route, calls.length);
    } else if (path === '/api/brands') await route.fulfill({ json: [] });
    else if (path === '/api/perfumes/9') await route.fulfill({ json: { id: 9, name: '검증용 첫 향수', brand: { name: '테스트 브랜드 A' } } });
    else if (path === '/api/perfumes') await route.fulfill({ json: { content: [], page: 0, size: 20, totalPages: 0, totalElements: 0 } });
    else await route.fulfill({ status: 404, json: {} });
  });
  return calls;
}

async function submit(page, query = '시트러스 향수를 찾아요.') {
  await page.getByLabel('어떤 향을 찾고 계세요?').fill(query);
  await page.getByRole('button', { name: '추천받기', exact: true }).click();
}

test('recommendation menu, contract, paragraphs, citation order and detail navigation', async ({ page }, testInfo) => {
  const errors = []; page.on('pageerror', (error) => errors.push(error.message));
  const calls = await mockApi(page);
  await page.goto('/');
  await page.getByRole('navigation', { name: '메인 메뉴' }).getByRole('link', { name: 'AI 향수 추천' }).click();
  await expect(page).toHaveURL(/\/recommend$/);
  await expect(page.getByRole('heading', { level: 1, name: 'AI 향수 추천' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'AI 향수 추천', exact: true })).toHaveAttribute('aria-current', 'page');
  await expect(page.getByText('전체 카탈로그가 아닌, 임베딩된 일부 향수의 저장된 정보를 기반으로 추천해요.')).toBeVisible();
  await page.getByLabel('검색 개수', { exact: true }).selectOption('8');
  await submit(page);
  await expect(page.getByRole('heading', { name: '취향을 위한 추천 기록' })).toBeFocused();
  expect(calls).toEqual([{ method: 'POST', body: { query: '시트러스 향수를 찾아요.', topK: 8 } }]);
  await expect(page.locator('.recommend-answer p')).toHaveCount(4);
  const cards = page.locator('.recommend-source-card');
  await expect(cards).toHaveCount(2);
  await expect(cards.nth(0)).toContainText('[1]'); await expect(cards.nth(0)).toContainText('테스트 브랜드 A');
  await expect(cards.nth(0)).toContainText('검색 유사도 0.7');
  await expect(cards.nth(0)).toHaveAttribute('href', '/perfumes/9');
  await expect(cards.nth(1)).toHaveAttribute('href', '/perfumes/2');
  await page.getByRole('link', { name: '출처 2 보기' }).click();
  await expect(page).toHaveURL(/#recommend-source-2$/);
  // Capture from the top so sticky/fixed elements keep their normal position in full-page images.
  await page.evaluate(() => window.scrollTo({ top: 0, behavior: 'instant' }));
  await page.screenshot({ path: testInfo.outputPath('recommendation.png'), fullPage: true });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await cards.first().click();
  await expect(page).toHaveURL(/\/perfumes\/9$/);
  await expect(page.getByRole('heading', { level: 1, name: '검증용 첫 향수' })).toBeVisible();
  await page.getByRole('link', { name: '향수 목록으로' }).first().click();
  await expect(page.getByRole('heading', { name: '향수 컬렉션' })).toBeVisible();
  expect(errors).toEqual([]);
});

test('blank input stays local, examples only fill input, and topK and UTF-16 character limits apply', async ({ page }) => {
  const calls = await mockApi(page); await page.goto('/recommend');
  const input = page.getByLabel('어떤 향을 찾고 계세요?');
  await submit(page, ' \u00a0\u3000');
  await expect(page.getByRole('alert')).toContainText('500자 이내');
  expect(calls).toHaveLength(0); await expect(input).toBeFocused();
  await page.getByRole('button', { name: '산뜻한 시트러스 노트가 있는 향수를 찾고 있어요.' }).click();
  await expect(input).toHaveValue('산뜻한 시트러스 노트가 있는 향수를 찾고 있어요.');
  expect(calls).toHaveLength(0);
  await expect(input).toHaveAttribute('maxlength', '500');
  await input.fill('향'.repeat(501)); await expect(input).toHaveValue('향'.repeat(500));
  await expect(page.getByText('500 / 500자', { exact: true })).toBeVisible();
  await page.getByLabel('검색 개수', { exact: true }).selectOption('20');
  await page.getByRole('button', { name: '추천받기', exact: true }).click();
  await expect(page.locator('.recommend-source-card')).toHaveCount(2);
  expect(calls[0].body.topK).toBe(20); expect(calls[0].body.query.length).toBe(500);
});

test('pending state prevents duplicate submissions; cancel aborts fetch and late responses cannot overwrite a new request', async ({ page }) => {
  let releaseFirst;
  const calls = await mockApi(page, async (route, count) => {
    if (count === 1) await new Promise((resolve) => { releaseFirst = resolve; });
    await route.fulfill({ json: count === 1 ? { ...result, answer: '취소한 오래된 답변' } : { ...result, answer: '새 요청 결과 [1]' } }).catch(() => {});
  });
  await page.addInitScript(() => {
    window.__aiAborts = 0;
    const originalFetch = window.fetch;
    window.fetch = (url, options) => {
      if (String(url).includes('/api/ai/perfumes/recommend')) options?.signal?.addEventListener('abort', () => { window.__aiAborts++; }, { once: true });
      return originalFetch(url, options);
    };
  });
  await page.goto('/recommend'); await submit(page);
  await expect.poll(() => calls.length).toBe(1);
  await expect(page.getByRole('status')).toContainText('추천 응답을 기다리고 있어요');
  await expect(page.getByRole('button', { name: '추천 기다리는 중' })).toBeDisabled();
  await expect(page.getByLabel('어떤 향을 찾고 계세요?')).toBeDisabled();
  await expect(page.getByLabel('검색 개수', { exact: true })).toBeDisabled();
  await page.getByRole('form', { name: 'AI 향수 추천 요청' }).dispatchEvent('submit');
  expect(calls).toHaveLength(1);
  await expect(page.getByRole('status')).not.toContainText('%');
  await page.getByRole('button', { name: '취소', exact: true }).click();
  await expect(page.getByRole('status')).toContainText('추천 요청 대기를 취소했어요');
  await expect.poll(() => page.evaluate(() => window.__aiAborts)).toBe(1);
  await submit(page, '새로운 우디 향 질문');
  await expect(page.getByRole('article', { name: 'AI 추천 답변' })).toContainText('새 요청 결과');
  releaseFirst();
  await expect(page.getByText('취소한 오래된 답변', { exact: true })).toHaveCount(0);
  expect(calls).toHaveLength(2);
});

test('leaving the page cancels pending fetch and returning does not automatically submit again', async ({ page }) => {
  let release;
  const calls = await mockApi(page, async (route) => {
    await new Promise((resolve) => { release = resolve; });
    await route.fulfill({ json: result }).catch(() => {});
  });
  await page.addInitScript(() => {
    window.__aiAborted = false;
    const originalFetch = window.fetch;
    window.fetch = (url, options) => {
      if (String(url).includes('/api/ai/perfumes/recommend')) options.signal.addEventListener('abort', () => { window.__aiAborted = true; }, { once: true });
      return originalFetch(url, options);
    };
  });
  await page.goto('/recommend'); await submit(page);
  await expect.poll(() => calls.length).toBe(1);
  await page.getByRole('navigation', { name: '메인 메뉴' }).getByRole('link', { name: '향수 둘러보기' }).click();
  await expect.poll(() => page.evaluate(() => window.__aiAborted)).toBe(true);
  release();
  await page.getByRole('link', { name: 'AI 향수 추천', exact: true }).click();
  await expect(page.getByLabel('어떤 향을 찾고 계세요?')).toHaveValue('');
  await expect(page.getByRole('button', { name: '추천받기', exact: true })).toBeEnabled();
  expect(calls).toHaveLength(1);
});

test('empty results show the server answer without source cards', async ({ page }) => {
  await mockApi(page, (route) => route.fulfill({ json: { answer: '저장된 후보에 일치하는 근거가 없어요.', sources: [], returnedCount: 0 } }));
  await page.goto('/recommend'); await submit(page);
  await expect(page.getByRole('heading', { name: '추천 결과가 없어요' })).toBeVisible();
  await expect(page.getByRole('article', { name: 'AI 추천 답변' })).toContainText('저장된 후보에 일치하는 근거가 없어요.');
  await expect(page.locator('.recommend-source-card')).toHaveCount(0);
});

for (const [status, code, title] of [
  [400, 'INVALID_REQUEST', '질문과 검색 개수를 확인해 주세요'],
  [429, 'RECOMMEND_BUSY', '다른 추천 요청을 처리하고 있어요'],
  [503, 'RECOMMEND_DISABLED', 'AI 추천이 비활성화되어 있어요'],
  [503, 'EVIDENCE_UNAVAILABLE', '사용할 수 있는 추천 근거가 없어요'],
  [503, 'LLM_UNAVAILABLE', '추천 서비스를 잠시 이용할 수 없어요'],
  [502, 'GENERATION_FAILED', '추천을 완료하지 못했어요'],
  [504, 'RECOMMEND_TIMEOUT', '추천 응답 시간이 초과됐어요'],
]) test(`HTTP ${status}/${code} uses a safe message, no automatic retry, and permits manual recovery`, async ({ page }) => {
  const calls = await mockApi(page, (route, count) => count === 1
    ? route.fulfill({ status, contentType: 'application/problem+json', body: JSON.stringify({ code, detail: 'private backend diagnostic', title: 'private backend title' }) })
    : route.fulfill({ json: result }));
  await page.goto('/recommend'); await submit(page);
  await expect(page.getByRole('alert')).toContainText(title);
  await expect(page.getByRole('alert')).not.toContainText('private backend');
  await page.waitForTimeout(600); expect(calls).toHaveLength(1);
  await page.getByRole('button', { name: '추천받기', exact: true }).click();
  await expect(page.locator('.recommend-source-card')).toHaveCount(2); expect(calls).toHaveLength(2);
});

test('client deadline uses the long AI limit, shows timeout and aborts without retrying', async ({ page }) => {
  let release;
  const calls = await mockApi(page, async (route) => {
    await new Promise((resolve) => { release = resolve; });
    await route.fulfill({ json: result }).catch(() => {});
  });
  await page.clock.install();
  await page.goto('/recommend'); await submit(page);
  await expect.poll(() => calls.length).toBe(1);
  await page.clock.fastForward(420_000);
  await expect(page.getByRole('status')).toContainText('추천 응답을 기다리고 있어요');
  await page.clock.fastForward(60_000);
  await expect(page.getByRole('alert')).toContainText('추천 응답 시간이 초과됐어요');
  expect(calls).toHaveLength(1); release();
});

test('network and malformed responses show separate errors without raw response bodies', async ({ page }) => {
  await mockApi(page, (route, count) => count === 1 ? route.abort('failed') : route.fulfill({ body: '<html>private response body</html>' }));
  await page.goto('/recommend'); await submit(page);
  await expect(page.getByRole('alert')).toContainText('서버에 연결할 수 없어요');
  await page.getByRole('button', { name: '추천받기', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('추천 응답을 확인할 수 없어요');
  await expect(page.getByRole('alert')).not.toContainText('private response body');
});

test('questions and answers stay out of browser storage, URLs and logs; response text cannot execute HTML', async ({ page }) => {
  const logs = []; page.on('console', (message) => logs.push(message.text()));
  await mockApi(page, (route) => route.fulfill({ json: { ...result, answer: '비공개 검증 답변 <img src=x onerror="window.__executed=true"> [1]' } }));
  await page.goto('/recommend');
  const before = await page.evaluate(() => ({ local: { ...localStorage }, session: { ...sessionStorage } }));
  await submit(page, '비공개 검증 질문');
  await expect(page.getByRole('article', { name: 'AI 추천 답변' })).toContainText('<img src=x');
  await expect(page.locator('.recommend-answer img')).toHaveCount(0);
  expect(await page.evaluate(() => window.__executed)).toBeUndefined();
  expect(await page.evaluate(() => ({ local: { ...localStorage }, session: { ...sessionStorage } }))).toEqual(before);
  expect(page.url()).not.toContain('비공개');
  expect(logs.some((line) => line.includes('비공개'))).toBe(false);
  await page.reload();
  await expect(page.getByLabel('어떤 향을 찾고 계세요?')).toHaveValue('');
  await expect(page.locator('.recommend-answer')).toHaveCount(0);
});

test('recommendation layout and header fit the narrowest supported mobile width', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 740 });
  await mockApi(page); await page.goto('/recommend'); await submit(page);
  await expect(page.locator('.recommend-source-card')).toHaveCount(2);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await expect(page.getByRole('navigation', { name: '메인 메뉴' }).getByRole('link', { name: 'AI 향수 추천' })).toBeVisible();
});
