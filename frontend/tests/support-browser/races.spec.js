import { test, expect } from '@playwright/test';

test('an older empty history response cannot erase a newly created room; IME-safe multiline input remains text', async ({ page }) => {
  if (process.env.SUPPORT_LOCAL_TEST !== 'true') throw new Error('Isolated test server required');
  let release, snapshotReady;
  const held = new Promise(resolve => { release = resolve; });
  const snapshot = new Promise(resolve => { snapshotReady = resolve; });
  let first = true;
  await page.route('**/api/support/conversations/current', async route => {
    if (!first) { await route.continue(); return; }
    first = false;
    const response = await route.fetch();
    snapshotReady(); await held; await route.fulfill({ response });
  });
  try {
    await page.goto('/recommend'); await page.getByRole('button', { name: '고객센터 채팅', exact: true }).click();
    await snapshot;
    await expect(page.getByRole('status').filter({hasText:'실시간 연결됨'})).toBeVisible();
    await page.getByRole('button', { name: '상담 시작하기' }).click();
    await expect(page.getByLabel('상담 메시지 입력')).toBeVisible();
    release();
    const input = page.getByLabel('상담 메시지 입력');
    await input.fill('조합 중');
    await input.dispatchEvent('keydown', {key:'Enter',code:'Enter',isComposing:true});
    await expect(input).toHaveValue('조합 중');
    await expect(page.getByRole('log')).not.toContainText('조합 중');
    await input.fill('첫 번째 줄'); await input.press('Shift+Enter'); await input.pressSequentially('second line');
    await expect(input).toHaveValue('첫 번째 줄\nsecond line');
    await input.press('Enter');
    await expect(page.getByRole('log').getByText('첫 번째 줄\nsecond line', {exact:true})).toHaveCount(1);
    await expect(page.getByRole('button', { name: '상담 시작하기' })).toHaveCount(0);
    await expect(input).toHaveValue('');
  } finally { release(); }
});
