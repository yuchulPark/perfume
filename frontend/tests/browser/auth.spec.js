import { test, expect } from '@playwright/test';

const endpoint = '/api/ai/perfumes/recommend/jobs';
const id = '11111111-1111-4111-8111-111111111111';
const view = (state = 'RUNNING') => ({ jobId: id, state, result: state === 'SUCCEEDED'
  ? { answer: '저장된 향수에서 추천 근거를 찾지 못했어요.', sources: [], returnedCount: 0 } : null, errorCode: null, errorStatus: null });
async function mockAuth(page, options = {}) {
  const data = { user: options.loggedIn ? { username: 'admin', role: 'ADMIN' } : null, jobs: [], csrfCount: 0, logins: 0, token: 'masked-csrf-0' };
  await page.route('**/api/**', async (route) => {
    const request = route.request(), path = new URL(request.url()).pathname;
    if (path === '/api/auth/me') {
      if (options.meWait) await options.meWait;
      return route.fulfill({ json: { enabled: true, user: data.user } });
    }
    if (path === '/api/auth/csrf') { data.csrfCount++; return route.fulfill({ json: { enabled: true, headerName: 'X-CSRF-TOKEN', token: data.token } }); }
    if (path === '/api/auth/login') {
      data.logins++;
      expect(request.headers()['x-csrf-token']).toBe(data.token);
      if (options.loginWait) await options.loginWait;
      const form = new URLSearchParams(request.postData());
      if (form.get('username') !== 'admin') return route.fulfill({ status: 401, json: { code: 'INVALID_CREDENTIALS' } });
      data.user = { username: 'admin', role: 'ADMIN' }; data.token = `masked-csrf-${data.logins}`;
      return route.fulfill({ json: { enabled: true, user: data.user } });
    }
    if (path === '/api/auth/logout') {
      expect(request.headers()['x-csrf-token']).toBe(data.token);
      data.user = null; data.token = 'masked-after-logout';
      return route.fulfill({ json: { success: true } });
    }
    if (path.startsWith(endpoint)) {
      data.jobs.push({ path, method: request.method() });
      expect(request.headers()['x-recommendation-token']).toMatch(/^[a-f0-9]{64}$/);
      if (request.method() === 'POST') expect(request.headers()['x-csrf-token']).toBe(data.token);
      if (options.expire === 'create' || (options.expire === 'poll' && request.method() === 'GET') || (options.expire === 'cancel' && path.endsWith('/cancel'))) {
        data.user = null;
        return route.fulfill({ status: 401, json: { code: 'AUTH_REQUIRED', detail: 'private-server-detail' } });
      }
      return route.fulfill({ status: request.method() === 'POST' ? 202 : 200, json: view(options.expire === 'cancel' || request.method() === 'POST' ? 'RUNNING' : 'SUCCEEDED') });
    }
    if (path === '/api/brands') return route.fulfill({ json: [] });
    if (path === '/api/perfumes') return route.fulfill({ json: { content: [], page: 0, totalPages: 0, totalElements: 0 } });
    return route.fulfill({ status: 404, json: {} });
  });
  return data;
}
async function enterLogin(page, username = 'admin') {
  await page.getByLabel('아이디', { exact: true }).fill(username);
  await page.getByLabel('비밀번호', { exact: true }).fill(crypto.randomUUID());
  await page.getByRole('button', { name: '로그인', exact: true }).click();
}

test('guest confirmation, login return, memory draft, session restoration and logout preserve recommendation authorization', async ({ page }, info) => {
  const calls = await mockAuth(page);
  await page.goto('/recommend'); await expect(page.getByRole('link', { name: '로그인', exact: true })).toBeVisible();
  await page.getByLabel('어떤 향을 찾고 계세요?').fill('URL에 넣지 않는 나만의 질문');
  await page.getByLabel('검색 개수', { exact: true }).selectOption('8');
  const submit = page.getByRole('button', { name: '추천받기', exact: true });
  await expect(submit).toBeEnabled(); await submit.click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toContainText('AI 향수 추천 기능을 이용하려면 로그인이 필요합니다.');
  await expect(dialog.getByRole('button', { name: '확인', exact: true })).toBeFocused();
  expect(calls.jobs).toHaveLength(0);
  await dialog.getByRole('button', { name: '확인', exact: true }).click(); await expect(page).toHaveURL('/login');
  await expect(page.getByLabel('비밀번호', { exact: true })).toHaveAttribute('type', 'password');
  await page.getByRole('button', { name: '비밀번호 표시' }).click();
  await expect(page.getByLabel('비밀번호', { exact: true })).toHaveAttribute('type', 'text');
  await page.getByRole('button', { name: '비밀번호 숨기기' }).click();
  await page.screenshot({ path: info.outputPath('login.png'), fullPage: true });
  await enterLogin(page); await expect(page).toHaveURL('/recommend');
  await expect(page.getByLabel('어떤 향을 찾고 계세요?')).toHaveValue('URL에 넣지 않는 나만의 질문');
  await expect(page.getByLabel('검색 개수', { exact: true })).toHaveValue('8');
  await expect(page.locator('.auth-username')).toHaveText('admin');
  await submit.click(); await expect(page.getByRole('article', { name: 'AI 추천 답변' })).toBeVisible();
  expect(calls.jobs.filter((call) => call.method === 'POST')).toHaveLength(1);
  await page.reload(); await expect(page.getByRole('button', { name: '로그아웃' })).toBeVisible();
  await page.goto('/login'); await expect(page).toHaveURL('/recommend');
  await page.getByRole('button', { name: '로그아웃' }).click();
  await expect(page.getByRole('link', { name: '로그인', exact: true })).toBeVisible();
  const count = calls.jobs.length; await submit.click(); await expect(dialog).toBeVisible(); expect(calls.jobs).toHaveLength(count);
  expect(await page.evaluate(() => JSON.stringify(localStorage))).toBe('{}');
  expect(await page.evaluate(() => JSON.stringify(sessionStorage))).not.toContain('나만의 질문');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  expect(calls.csrfCount).toBeGreaterThanOrEqual(5);
});

test('unresolved authentication never sends a recommendation or falsely opens the login notice', async ({ page }) => {
  let release; const meWait = new Promise((resolve) => { release = resolve; });
  const calls = await mockAuth(page, { loggedIn: true, meWait });
  await page.goto('/recommend');
  await page.getByLabel('어떤 향을 찾고 계세요?').fill('우디 향');
  await page.getByRole('button', { name: '추천받기', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('로그인 상태를 확인한 후');
  await expect(page.getByRole('dialog')).toHaveCount(0); expect(calls.jobs).toHaveLength(0);
  release(); await expect(page.locator('.auth-username')).toHaveText('admin');
});

for (const expire of ['create', 'poll', 'cancel']) test(`expired session during ${expire} stops work and guides to login`, async ({ page }) => {
  const calls = await mockAuth(page, { loggedIn: true, expire });
  await page.goto('/recommend'); await expect(page.locator('.auth-username')).toBeVisible();
  await page.getByLabel('어떤 향을 찾고 계세요?').fill('세션 만료 후에도 질문 유지');
  await page.getByRole('button', { name: '추천받기', exact: true }).click();
  if (expire === 'cancel') await page.getByRole('button', { name: '취소', exact: true }).click();
  await expect(page.getByRole('dialog')).toBeVisible();
  const count = calls.jobs.length; await page.waitForTimeout(2200); expect(calls.jobs).toHaveLength(count);
  await page.getByRole('button', { name: '확인', exact: true }).click(); await expect(page).toHaveURL('/login');
  expect(await page.locator('body').innerText()).not.toContain('private-server-detail');
});

test('login validates fields, clears failed passwords and blocks repeat submits', async ({ page }) => {
  let release; const loginWait = new Promise((resolve) => { release = resolve; });
  const calls = await mockAuth(page, { loginWait });
  await page.goto('/login'); const submit = page.getByRole('button', { name: '로그인', exact: true });
  await expect(submit).toBeEnabled(); await submit.click(); await expect(page.getByRole('alert')).toContainText('아이디와 비밀번호');
  await enterLogin(page, 'missing-user'); await expect(page.getByRole('button', { name: '로그인 중…' })).toBeDisabled();
  await page.getByLabel('로그인', { exact: true }).evaluate((form) => form.requestSubmit());
  release(); await expect(page.getByRole('alert')).toContainText('아이디 또는 비밀번호');
  expect(calls.logins).toBe(1); await expect(page.getByLabel('비밀번호', { exact: true })).toHaveValue('');
});
