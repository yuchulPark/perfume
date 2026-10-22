import { test, expect } from '@playwright/test';

test('real auth OFF catalog and recommendation button stay public without login or CSRF', async ({ page, context, request }, info) => {
  test.skip(process.env.PERFUME_AUTH_LOCAL_CHECK !== 'true', 'Explicit isolated local verification only.');
  expect(await (await request.get('/api/auth/me')).json()).toEqual({ enabled: false, user: null });
  const catalog = await (await request.get('/api/perfumes')).json();
  expect(catalog.content).toHaveLength(2);
  expect(catalog.content.every((item) => item.publicId.startsWith('local-auth-check-'))).toBe(true);
  await context.route('**/*', (route) => new URL(route.request().url()).origin === info.project.use.baseURL
    ? route.continue() : route.abort('blockedbyclient'));
  await page.goto('/');
  await page.locator('.perfume-card').filter({ hasText: '로컬 검증용 시트러스' }).click();
  await expect(page.getByRole('heading', { name: '로컬 검증용 시트러스', level: 1 })).toBeVisible();
  await page.getByRole('link', { name: 'AI 향수 추천', exact: true }).click();
  await page.getByLabel('어떤 향을 찾고 계세요?').fill('인증 OFF 검사, 추론 실행 안 함');
  const response = page.waitForResponse((res) => res.request().method() === 'POST'
    && new URL(res.url()).pathname === '/api/ai/perfumes/recommend/jobs');
  await page.getByRole('button', { name: '추천받기', exact: true }).click();
  const actual = await response;
  expect(actual.status()).toBe(503);
  expect((await actual.json()).code).toBe('RECOMMEND_DISABLED');
  expect(actual.request().headers()['x-csrf-token']).toBeUndefined();
  await expect(page).toHaveURL('/recommend');
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(page.getByRole('alert')).toContainText('AI 추천이 비활성화되어 있어요');
});
