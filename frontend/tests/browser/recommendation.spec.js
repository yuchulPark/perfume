import { test, expect } from '@playwright/test';

const endpoint = '/api/ai/perfumes/recommend/jobs';
const result = {
  answer: '저장된 정보를 기준으로 정리했어요.\n검증용 첫 향수는 시트러스 노트가 있어요. [1]\n검증용 둘째 향수도 검색된 후보예요. [2]\n임베딩 생성 당시의 정보로 최신 정보와 다를 수 있어요.',
  sources: [{ perfumeId: 9, name: '검증용 첫 향수', brandName: '테스트 브랜드 A', similarity: .7 },
    { perfumeId: 2, name: '검증용 둘째 향수', brandName: '테스트 브랜드 B', similarity: .9 }], returnedCount: 2,
};
const jobId = (index = 1) => `11111111-1111-4111-8111-${String(index).padStart(12, '0')}`;
const view = (state, index = 1, extra = {}) => ({ jobId: jobId(index), state, result: state === 'SUCCEEDED' ? result : null, errorCode: null, errorStatus: null, ...extra });
const success = (route, state = 'SUCCEEDED', index = 1, extra = {}) => route.fulfill({ status: route.request().method() === 'POST' ? 202 : 200, json: view(state, index, extra) });

async function mockApi(page, handlers = {}) {
  const calls = { creates: [], gets: [], cancels: [] };
  await page.route('**/api/**', async (route) => {
    const request = route.request(), path = new URL(request.url()).pathname;
    if (path === '/api/auth/me') return route.fulfill({ json: { enabled: false, user: null } });
    if (path.startsWith(endpoint)) {
      const token = request.headers()['x-recommendation-token'];
      expect(token).toMatch(/^[0-9a-f]{64}$/);
      if (path.endsWith('/cancel')) { calls.cancels.push({ token, path }); await (handlers.cancel?.(route, calls) ?? success(route, 'CANCELLED')); }
      else if (request.method() === 'POST') { calls.creates.push({ token, body: request.postDataJSON() }); await (handlers.create?.(route, calls) ?? success(route, 'RUNNING', calls.creates.length)); }
      else { calls.gets.push({ token, path }); await (handlers.get?.(route, calls) ?? success(route, 'SUCCEEDED', calls.creates.length || 1)); }
    } else if (path === '/api/brands') await route.fulfill({ json: [] });
    else if (path === '/api/perfumes/9') await route.fulfill({ json: { id: 9, name: '검증용 첫 향수', brand: { name: '테스트 브랜드 A' } } });
    else if (path === '/api/perfumes') await route.fulfill({ json: { content: [], page: 0, totalPages: 0, totalElements: 0 } });
    else await route.fulfill({ status: 404, json: {} }); // Never forward an unexpected request to a real backend.
  });
  return calls;
}
async function submit(page, query = '시트러스 향수를 찾아요.') {
  await page.getByLabel('어떤 향을 찾고 계세요?').fill(query);
  await page.getByRole('button', { name: '추천받기', exact: true }).click();
}

test('job recommendation keeps menu, inputs, paragraphs, citation order, source links and detail navigation', async ({ page }, testInfo) => {
  const errors = []; page.on('pageerror', (error) => errors.push(error.message));
  const calls = await mockApi(page); await page.goto('/');
  await page.getByRole('link', { name: 'AI 향수 추천', exact: true }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'AI 향수 추천' })).toBeVisible();
  await expect(page.getByText('전체 카탈로그가 아닌, 임베딩된 일부 향수의 저장된 정보를 기반으로 추천해요.')).toBeVisible();
  await page.getByLabel('검색 개수', { exact: true }).selectOption('8'); await submit(page);
  await expect(page).toHaveURL('/recommend');
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(page.getByRole('heading', { name: '취향을 위한 추천 기록' })).toBeFocused();
  expect(calls.creates).toHaveLength(1); expect(calls.creates[0].body).toEqual({ query: '시트러스 향수를 찾아요.', topK: 8 });
  expect(calls.gets[0].token).toBe(calls.creates[0].token);
  await expect(page.locator('.recommend-answer p')).toHaveCount(4);
  const cards = page.locator('.recommend-source-card'); await expect(cards).toHaveCount(2);
  await expect(cards.first()).toContainText('[1]'); await expect(cards.first()).toContainText('검색 유사도 0.7');
  await expect(cards.first()).toHaveAttribute('href', '/perfumes/9'); await expect(cards.nth(1)).toHaveAttribute('href', '/perfumes/2');
  await page.getByRole('link', { name: '출처 2 보기' }).click(); await expect(page).toHaveURL(/#recommend-source-2$/);
  await page.evaluate(() => window.scrollTo({ top: 0, behavior: 'instant' }));
  await page.screenshot({ path: testInfo.outputPath('recommendation-jobs.png'), fullPage: true });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await cards.first().click(); await expect(page.getByRole('heading', { name: '검증용 첫 향수', level: 1 })).toBeVisible();
  await page.getByRole('link', { name: '향수 목록으로' }).first().click(); await expect(page.getByRole('heading', { name: '향수 컬렉션' })).toBeVisible();
  expect(errors).toEqual([]);
});

test('blank validation, example fill only, 500-character limit and topK 20 stay intact', async ({ page }) => {
  const calls = await mockApi(page); await page.goto('/recommend');
  await submit(page, ' \u00a0\u3000'); await expect(page.getByRole('alert')).toContainText('500자'); expect(calls.creates).toHaveLength(0);
  await page.getByRole('button', { name: '산뜻한 시트러스 노트가 있는 향수를 찾고 있어요.' }).click();
  expect(calls.creates).toHaveLength(0);
  const input = page.getByLabel('어떤 향을 찾고 계세요?'); await input.fill('향'.repeat(501)); await expect(input).toHaveValue('향'.repeat(500));
  await page.getByLabel('검색 개수', { exact: true }).selectOption('20'); await page.getByRole('button', { name: '추천받기', exact: true }).click();
  await expect(page.locator('.recommend-source-card')).toHaveCount(2); expect(calls.creates[0].body.topK).toBe(20); expect(calls.creates[0].body.query.length).toBe(500);
});

test('server cancellation stays CANCELLING and blocks all resubmission until cleanup confirmation', async ({ page }) => {
  let cancelled = false, cleaned = false;
  const calls = await mockApi(page, { cancel: (route) => { cancelled = true; return success(route, 'CANCELLING'); },
    get: (route, calls) => success(route, calls.creates.length > 1 ? 'SUCCEEDED' : cleaned ? 'CANCELLED' : cancelled ? 'CANCELLING' : 'RUNNING', calls.creates.length) });
  await page.goto('/recommend'); await submit(page); await expect(page.getByRole('button', { name: '취소', exact: true })).toBeVisible();
  await page.getByRole('form', { name: 'AI 향수 추천 요청' }).dispatchEvent('submit'); expect(calls.creates).toHaveLength(1);
  await page.getByRole('button', { name: '취소', exact: true }).click();
  await expect(page.getByRole('status')).toContainText('AI 추천을 취소하는 중입니다');
  await expect(page.getByRole('button', { name: '추천 작업 진행 중', exact: true })).toBeDisabled();
  await expect(page.getByRole('button', { name: '취소 처리 중' })).toBeDisabled();
  await expect(page.getByLabel('어떤 향을 찾고 계세요?')).toBeDisabled();
  await expect.poll(() => calls.gets.length).toBeGreaterThan(0); expect(calls.creates).toHaveLength(1);
  await expect(page.getByRole('status')).not.toContainText('%');
  cleaned = true; await expect(page.getByRole('status')).toContainText('추천 작업 취소가 완료됐어요');
  await submit(page, '새로운 우디 향'); await expect(page.locator('.recommend-source-card')).toHaveCount(2);
  expect(calls.creates).toHaveLength(2); expect(calls.creates[0].token).not.toBe(calls.creates[1].token);
});

test('cancel clicked before creation responds is delivered to the server after the ID arrives', async ({ page }) => {
  let release;
  const calls = await mockApi(page, { create: async (route) => { await new Promise((resolve) => { release = resolve; }); await success(route, 'RUNNING'); } });
  await page.goto('/recommend'); await submit(page); await expect.poll(() => calls.creates.length).toBe(1);
  await page.getByRole('button', { name: '취소', exact: true }).click();
  await expect(page.getByRole('status')).toContainText('AI 추천을 취소하는 중입니다'); expect(calls.cancels).toHaveLength(0);
  release(); await expect(page.getByRole('status')).toContainText('추천 작업 취소가 완료됐어요'); expect(calls.cancels).toHaveLength(1);
});

test('a lost creation response recovers by token without creating another job', async ({ page }) => {
  const calls = await mockApi(page, { create: (route) => route.abort('failed') });
  await page.goto('/recommend'); await submit(page); await expect(page.getByRole('status')).toContainText('작업 상태 확인 불가');
  await expect(page.getByRole('button', { name: '추천 작업 진행 중' })).toBeDisabled();
  await page.getByRole('button', { name: '상태 다시 확인' }).click(); await expect(page.locator('.recommend-source-card')).toHaveCount(2);
  expect(calls.creates).toHaveLength(1); expect(calls.gets[0].path).toBe(endpoint); expect(calls.gets[0].token).toBe(calls.creates[0].token);
});

test('status network failure freezes polling and never assumes that server work has ended', async ({ page }) => {
  let fail = true;
  const calls = await mockApi(page, { get: (route) => fail ? route.abort('failed') : success(route) });
  await page.goto('/recommend'); await submit(page); await expect(page.getByRole('status')).toContainText('작업 상태 확인 불가');
  const count = calls.gets.length; await page.waitForTimeout(2200); expect(calls.gets).toHaveLength(count);
  await expect(page.getByRole('button', { name: '추천 작업 진행 중' })).toBeDisabled();
  fail = false; await page.getByRole('button', { name: '상태 다시 확인' }).click(); await expect(page.locator('.recommend-source-card')).toHaveCount(2);
});

test('failed cancel response remains unknown until a manual status check confirms cancellation', async ({ page }) => {
  let cancelled = false;
  await mockApi(page, { cancel: (route) => { cancelled = true; return route.abort('failed'); }, get: (route) => success(route, cancelled ? 'CANCELLED' : 'RUNNING') });
  await page.goto('/recommend'); await submit(page); await page.getByRole('button', { name: '취소', exact: true }).click();
  await expect(page.getByRole('status')).toContainText('작업 상태 확인 불가');
  await page.getByRole('button', { name: '상태 다시 확인' }).click(); await expect(page.getByRole('status')).toContainText('추천 작업 취소가 완료됐어요');
});

test('same-tab reload resumes only the handle and questions or answers never enter storage or console logs', async ({ page }) => {
  let done = false; const logs = []; page.on('console', (message) => logs.push(message.text()));
  const calls = await mockApi(page, { get: (route) => success(route, done ? 'SUCCEEDED' : 'RUNNING') });
  await page.goto('/recommend'); await submit(page, 'private-question'); await expect.poll(() => calls.creates.length).toBe(1);
  await expect.poll(() => page.evaluate(() => JSON.parse(sessionStorage.getItem('scent-archive.recommendation-job'))?.id)).toBe(jobId());
  const stored = await page.evaluate(() => ({ local: { ...localStorage }, session: { ...sessionStorage } }));
  expect(stored.local).toEqual({}); expect(JSON.stringify(stored)).not.toContain('private-question'); expect(JSON.stringify(stored)).not.toContain(result.answer);
  expect(Object.keys(JSON.parse(stored.session['scent-archive.recommendation-job'])).sort()).toEqual(['id', 'token']);
  await page.reload(); await expect(page.getByLabel('어떤 향을 찾고 계세요?')).toHaveValue(''); await expect(page.getByLabel('어떤 향을 찾고 계세요?')).toBeDisabled();
  done = true; await expect(page.locator('.recommend-source-card')).toHaveCount(2); expect(calls.creates).toHaveLength(1);
  expect(await page.evaluate(() => sessionStorage.getItem('scent-archive.recommendation-job'))).toBeNull();
  expect(logs.some((line) => line.includes('private-question') || line.includes(result.answer))).toBe(false);
});

test('page navigation preserves the job without falsely cancelling it and resumes when returning', async ({ page }) => {
  let done = false;
  const calls = await mockApi(page, { get: (route) => success(route, done ? 'SUCCEEDED' : 'RUNNING') });
  await page.goto('/recommend'); await submit(page); await expect.poll(() => calls.creates.length).toBe(1);
  await page.getByRole('navigation', { name: '메인 메뉴' }).getByRole('link', { name: '향수 둘러보기' }).click();
  expect(calls.cancels).toHaveLength(0); done = true;
  await page.getByRole('link', { name: 'AI 향수 추천', exact: true }).click(); await expect(page.locator('.recommend-source-card')).toHaveCount(2);
  expect(calls.creates).toHaveLength(1);
});

test('server restart or expired job is explained and allows a fresh request checked by the server', async ({ page }) => {
  const calls = await mockApi(page, { get: (route, calls) => calls.creates.length === 1
    ? route.fulfill({ status: 404, json: { code: 'RECOMMEND_JOB_NOT_FOUND' } }) : success(route, 'SUCCEEDED', calls.creates.length) });
  await page.goto('/recommend'); await submit(page); await expect(page.getByRole('status')).toContainText('작업 정보를 찾을 수 없어요');
  await submit(page, '다시 질문'); await expect(page.locator('.recommend-source-card')).toHaveCount(2); expect(calls.creates).toHaveLength(2);
});

test('a delayed old status response cannot overwrite cancellation or the next job result', async ({ page }) => {
  let releaseOld;
  const calls = await mockApi(page, { get: async (route, calls) => {
    if (calls.creates.length === 1) { await new Promise((resolve) => { releaseOld = resolve; }); await success(route, 'SUCCEEDED', 1, { result: { ...result, answer: '취소한 이전 답변' } }).catch(() => {}); }
    else await success(route, 'SUCCEEDED', 2, { result: { ...result, answer: '새 작업의 답변 [1]' } });
  } });
  await page.goto('/recommend'); await submit(page); await expect.poll(() => calls.gets.length).toBe(1);
  await page.getByRole('button', { name: '취소', exact: true }).click(); await expect(page.getByRole('status')).toContainText('추천 작업 취소가 완료됐어요');
  await submit(page, '새로운 질문'); await expect(page.getByRole('article', { name: 'AI 추천 답변' })).toContainText('새 작업의 답변');
  releaseOld(); await expect(page.getByText('취소한 이전 답변', { exact: true })).toHaveCount(0);
});

test('empty and untrusted HTML-like answer text preserve safe rendering', async ({ page }) => {
  await mockApi(page, { get: (route) => success(route, 'SUCCEEDED', 1, { result: { answer: '근거가 없어요. <img src=x onerror="window.__executed=true">', sources: [], returnedCount: 0 } }) });
  await page.goto('/recommend'); await submit(page); await expect(page.getByRole('heading', { name: '추천 결과가 없어요' })).toBeVisible();
  await expect(page.getByRole('article', { name: 'AI 추천 답변' })).toContainText('<img src=x'); await expect(page.locator('.recommend-answer img')).toHaveCount(0);
  expect(await page.evaluate(() => window.__executed)).toBeUndefined(); await expect(page.locator('.recommend-source-card')).toHaveCount(0);
});

for (const [status, code, title, rejected] of [
  [400, 'INVALID_REQUEST', '질문과 검색 개수를 확인해 주세요', true], [429, 'RECOMMEND_BUSY', '다른 추천 요청을 처리하고 있어요', true],
  [503, 'RECOMMEND_DISABLED', 'AI 추천이 비활성화되어 있어요', true], [503, 'EVIDENCE_UNAVAILABLE', '사용할 수 있는 추천 근거가 없어요', false],
  [502, 'GENERATION_FAILED', '추천을 완료하지 못했어요', false], [504, 'LLM_TIMEOUT', '추천 응답 시간이 초과됐어요', false],
]) test(`job error ${code} is safe, has no automatic retry and allows manual recovery after confirmed failure`, async ({ page }) => {
  const calls = await mockApi(page, {
    create: (route, calls) => rejected && calls.creates.length === 1 ? route.fulfill({ status, json: { code, detail: 'private-internal' } }) : success(route, 'RUNNING', calls.creates.length),
    get: (route, calls) => !rejected && calls.creates.length === 1 ? success(route, status === 504 ? 'TIMED_OUT' : 'FAILED', 1, { errorCode: code, errorStatus: status }) : success(route, 'SUCCEEDED', calls.creates.length),
  });
  await page.goto('/recommend'); await submit(page); await expect(page.getByRole('alert')).toContainText(title); await expect(page.getByRole('alert')).not.toContainText('private-internal');
  const count = calls.gets.length; await page.waitForTimeout(500); expect(calls.creates).toHaveLength(1); expect(calls.gets).toHaveLength(count);
  await page.getByRole('button', { name: '추천받기', exact: true }).click(); await expect(page.locator('.recommend-source-card')).toHaveCount(2); expect(calls.creates).toHaveLength(2);
});

test('layout and menu fit the narrowest supported mobile width', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 740 }); await mockApi(page); await page.goto('/recommend'); await submit(page);
  await expect(page.locator('.recommend-source-card')).toHaveCount(2); expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

test('rejected create after cancel does not cancel the next successful job', async ({ page }) => {
  let release;
  const calls = await mockApi(page, { create: async (route, calls) => {
    if (calls.creates.length === 1) {
      await new Promise((resolve) => { release = resolve; });
      await route.fulfill({ status: 429, json: { code: 'RECOMMEND_BUSY' } });
    } else await success(route, 'RUNNING', 2);
  } });
  await page.goto('/recommend'); await submit(page); await expect.poll(() => calls.creates.length).toBe(1);
  await page.getByRole('button', { name: '취소', exact: true }).click(); release();
  await expect(page.getByRole('alert')).toContainText('다른 추천 요청을 처리하고 있어요');
  await submit(page, '다음 추천'); await expect(page.locator('.recommend-source-card')).toHaveCount(2);
  expect(calls.cancels).toHaveLength(0);
});
