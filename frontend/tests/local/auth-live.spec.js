import { test, expect } from '@playwright/test';

test.beforeEach(async ({ context, page, request }, info) => {
  test.skip(process.env.PERFUME_AUTH_LOCAL_CHECK !== 'true', 'Requires the explicit isolated PostgreSQL verification fixture.');
  const session = await request.get('/api/auth/me', { maxRetries: 2 });
  expect(session.status()).toBe(200);
  expect(await session.json()).toEqual({ enabled: true, user: null });
  const perfumes = await (await request.get('/api/perfumes')).json();
  expect(perfumes.content).toHaveLength(2);
  expect(perfumes.content.every((item) => item.publicId.startsWith('local-auth-check-'))).toBe(true);
  // The local AdGuard installation can inject its own script. Block nonlocal traffic;
  // every application/API response still comes from the actual Spring server.
  await context.route('**/*', (route) => {
    const address = new URL(route.request().url());
    return address.origin === info.project.use.baseURL ? route.continue() : route.abort('blockedbyclient');
  });
  const statuses = [];
  let lastCsrf = null;
  page.on('response', async (response) => {
    const path = new URL(response.url()).pathname;
    if (path.startsWith('/api/auth/')) statuses.push({ path, status: response.status() });
    if (path === '/api/auth/csrf' && response.ok()) {
      try { lastCsrf = (await response.json()).token; } catch {}
    }
    if (path === '/api/auth/logout' && !response.ok()) {
      try { const error = await response.json(); statuses.push({ path, errorCode: error.code }); } catch { statuses.push({ path, errorType: response.headers()['content-type'] }); }
    }
  });
  page.on('request', (request) => {
    if (new URL(request.url()).pathname === '/api/auth/logout') {
      const headers = request.headers();
      statuses.push({ path: '/api/auth/logout', hasCsrfHeader: !!headers['x-csrf-token'], csrfMatchesLastIssued: headers['x-csrf-token'] === lastCsrf, hasSessionCookie: !!headers.cookie?.includes('JSESSIONID=') });
    }
  });
  page.on('requestfailed', (request) => {
    const path = new URL(request.url()).pathname;
    if (path.startsWith('/api/')) statuses.push({ path, error: request.failure()?.errorText });
  });
  info.authStatuses = statuses;
});
test.afterEach(async ({}, info) => {
  await info.attach('auth-http-statuses', { body: JSON.stringify(info.authStatuses || [], null, 2), contentType: 'application/json' });
  if (info.status !== info.expectedStatus) console.error(JSON.stringify(info.authStatuses || []));
});

test('repeated real auth responses survive proxy connection turnover without losing JSON', async ({ request }) => {
  for (let attempt = 0; attempt < 150; attempt++) {
    const response = await request.get(`/api/auth/me?attempt=${attempt}`, { maxRetries: 0 });
    expect(response.status()).toBe(200);
    expect(await response.json()).toEqual({ enabled: true, user: null });
    expect(response.headers()['cache-control']).toBe('no-store');
  }
});

test('real guest catalog/detail, login return, draft, disabled AI boundary, refresh and logout', async ({ page }, info) => {
  const recommendationPosts = [];
  page.on('request', (request) => {
    if (request.method() === 'POST' && new URL(request.url()).pathname.startsWith('/api/ai/perfumes/recommend')) recommendationPosts.push(request);
  });
  await page.goto('/');
  await expect(page.getByRole('heading', { name: '향수 컬렉션', exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: '로컬 검증용 시트러스', exact: true })).toBeVisible();
  await page.locator('.perfume-card').filter({ hasText: '로컬 검증용 시트러스' }).click();
  await expect(page).toHaveURL('/perfumes/1');
  await expect(page.getByRole('heading', { name: '로컬 검증용 시트러스', level: 1 })).toBeVisible();
  await expect(page.getByRole('link', { name: '로그인', exact: true })).toBeVisible();
  await page.getByRole('link', { name: 'AI 향수 추천', exact: true }).click();
  await page.getByLabel('어떤 향을 찾고 계세요?').fill('로컬 인증 검증용 질문, 추론 실행 안 함');
  await page.getByLabel('검색 개수', { exact: true }).selectOption('8');
  const recommend = page.getByRole('button', { name: '추천받기', exact: true });
  await expect(recommend).toBeEnabled(); await recommend.click();
  const notice = page.getByRole('dialog');
  await expect(notice).toContainText('AI 향수 추천 기능을 이용하려면 로그인이 필요합니다.');
  await expect(notice.getByRole('button', { name: '확인', exact: true })).toBeFocused();
  expect(recommendationPosts).toHaveLength(0);
  await page.screenshot({ path: info.outputPath('guest-notice.png'), fullPage: true });
  await notice.getByRole('button', { name: '확인', exact: true }).click();
  await expect(page).toHaveURL('/login');
  const username = 'admin';
  await page.getByLabel('아이디', { exact: true }).fill(username);
  await page.getByLabel('비밀번호', { exact: true }).fill(username);
  await page.getByRole('button', { name: '로그인', exact: true }).click();
  await expect(page).toHaveURL('/recommend');
  await expect(page.locator('.auth-username')).toHaveText(username);
  await expect(page.getByLabel('어떤 향을 찾고 계세요?')).toHaveValue('로컬 인증 검증용 질문, 추론 실행 안 함');
  await expect(page.getByLabel('검색 개수', { exact: true })).toHaveValue('8');
  const response = page.waitForResponse((res) => res.request().method() === 'POST' && new URL(res.url()).pathname === '/api/ai/perfumes/recommend/jobs');
  await recommend.click();
  const actual = await response;
  expect(actual.status()).toBe(503); expect((await actual.json()).code).toBe('RECOMMEND_DISABLED');
  await expect(page.getByRole('alert')).toContainText('AI 추천이 비활성화되어 있어요');
  expect(recommendationPosts).toHaveLength(1);
  await page.reload(); await expect(page.getByRole('button', { name: '로그아웃', exact: true })).toBeVisible();
  const me = await page.evaluate(async () => (await fetch('/api/auth/me', { cache: 'no-store' })).json());
  expect(me.user).toEqual({ username, role: 'ADMIN' });
  await page.screenshot({ path: info.outputPath('logged-in.png'), fullPage: true });
  const logoutResponse = page.waitForResponse((response) => new URL(response.url()).pathname === '/api/auth/logout');
  await page.getByRole('button', { name: '로그아웃', exact: true }).click();
  expect((await logoutResponse).status()).toBe(200);
  await expect(page.getByRole('link', { name: '로그인', exact: true })).toBeVisible();
  const status = await page.evaluate(async () => (await fetch('/api/ai/perfumes/recommend/jobs', { cache: 'no-store' })).status);
  expect(status).toBe(401);
  await recommend.click(); await expect(notice).toBeVisible(); expect(recommendationPosts).toHaveLength(1);
  const storage = await page.evaluate(() => ({ local: JSON.stringify(localStorage), session: JSON.stringify(sessionStorage) }));
  expect(storage.local).toBe('{}'); expect(storage.session).not.toContain('로컬 인증 검증용 질문');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});
