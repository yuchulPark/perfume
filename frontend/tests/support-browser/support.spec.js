import { test, expect } from '@playwright/test';

test('real PostgreSQL and STOMP isolate two visitors, deliver administrator replies, recover disconnects and revoke logout', async ({ browser }, info) => {
  if (!process.env.SUPPORT_TEST_PASSWORD || process.env.SUPPORT_LOCAL_TEST !== 'true') throw new Error('Run only with the isolated support test launcher');
  const options = { baseURL: 'http://127.0.0.1:18082', viewport: info.project.use.viewport, isMobile: !!info.project.use.isMobile, hasTouch: !!info.project.use.hasTouch };
  const a = await browser.newContext(options), b = await browser.newContext(options), admin = await browser.newContext(options);
  try {
    const ap = await a.newPage(), bp = await b.newPage(), ad = await admin.newPage();
    const sockets = [];
    await ap.routeWebSocket('**/ws/support', ws => { ws.connectToServer(); sockets.push(ws); });
    await ad.goto('/login'); await ad.getByLabel('아이디',{exact:true}).fill('admin');
    await ad.getByLabel('비밀번호',{exact:true}).fill(process.env.SUPPORT_TEST_PASSWORD);
    await ad.getByRole('button',{name:'로그인',exact:true}).click(); await expect(ad.locator('.auth-username')).toHaveText('admin');
    await ad.goto('/admin/support'); await expect(ad.getByRole('status').filter({hasText:'실시간 연결됨'})).toBeVisible();
    async function open(page) {
      await page.goto('/recommend'); await page.getByRole('button',{name:'고객센터 채팅',exact:true}).click();
      await expect(page.getByRole('status').filter({hasText:'실시간 연결됨'})).toBeVisible();
      const response = page.waitForResponse(r=>r.url().endsWith('/api/support/conversations') && r.request().method()==='POST');
      await page.getByRole('button',{name:'상담 시작하기'}).click(); return (await (await response).json()).id;
    }
    const aid = await open(ap), bid = await open(bp);
    const aRoom = ad.getByRole('button').filter({hasText:`방문자 ${aid.slice(0,8)}`}), bRoom = ad.getByRole('button').filter({hasText:`방문자 ${bid.slice(0,8)}`});
    await expect(aRoom).toBeVisible(); await expect(bRoom).toBeVisible();
    const marker = `${info.project.name}-${Date.now()}`;
    const question = `방문자 A ${marker} <img src=x onerror="window.supportXss=true">`;
    await ap.getByLabel('상담 메시지 입력').fill(question); await ap.getByLabel('상담 메시지 입력').press('Enter');
    await expect(ap.getByRole('log').getByText(question,{exact:true})).toHaveCount(1);
    await aRoom.click(); await expect(ad.getByRole('log').getByText(question,{exact:true})).toBeVisible();
    expect(await ap.evaluate(()=>window.supportXss)).toBeUndefined(); expect(await ap.getByRole('log').locator('img').count()).toBe(0);
    const reply = `관리자 A 답변 ${marker}`;
    await ad.getByLabel('상담 메시지 입력').fill(reply); await ad.getByRole('button',{name:'전송',exact:true}).click();
    await expect(ap.getByRole('log').getByText(reply,{exact:true})).toBeVisible(); await expect(bp.getByRole('log')).not.toContainText(reply);
    await bp.getByLabel('상담 메시지 입력').fill(`방문자 B ${marker}`); await bp.getByRole('button',{name:'전송',exact:true}).click();
    await expect(bRoom.getByLabel('읽지 않은 메시지 1개')).toBeVisible();
    await bRoom.click(); await expect(ad.getByRole('log')).toContainText(`방문자 B ${marker}`); await expect(ad.getByRole('log')).not.toContainText(question);
    await aRoom.click(); await expect(ad.getByRole('log')).toContainText(question);

    // Drop only A's transport. No mock broker/API is used; the offline message is persisted by the real server.
    await a.setOffline(true); await sockets.at(-1).close({code:1012,reason:'local test disconnect'});
    await expect(ap.getByRole('status').filter({hasText:'연결 끊김'})).toBeVisible(); await expect(ap.getByLabel('상담 메시지 입력')).toBeDisabled();
    const missed = `오프라인 중 관리자 답변 ${marker}`;
    await ad.getByLabel('상담 메시지 입력').fill(missed); await ad.getByRole('button',{name:'전송',exact:true}).click();
    await expect(ad.getByRole('log').getByText(missed,{exact:true})).toBeVisible();
    await a.setOffline(false); await expect(ap.getByRole('log').getByText(missed,{exact:true})).toBeVisible({timeout:20000});
    await expect(ap.getByRole('log').getByText(question,{exact:true})).toHaveCount(1);
    await ap.getByRole('button',{name:'고객센터 닫기'}).click(); await ap.getByRole('button',{name:'고객센터 채팅',exact:true}).click();
    await expect(ap.getByRole('log')).toContainText(missed);
    await ap.reload(); await ap.getByRole('button',{name:'고객센터 채팅',exact:true}).click();
    await expect(ap.getByRole('log')).toContainText(question); await expect(ap.getByRole('log')).toContainText(missed);
    const isolation = await bp.evaluate(async id => (await fetch(`/api/support/conversations/${id}/messages`)).status,aid); expect(isolation).toBe(404);
    await ad.getByRole('button',{name:'상담 종료',exact:true}).click();
    await expect(ap.getByText('상담이 종료되었습니다. 대화는 이 세션에서 확인할 수 있어요.')).toBeVisible(); await expect(ap.getByLabel('상담 메시지 입력')).toBeDisabled();
    expect(await ap.evaluate(()=>document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    expect(await ad.evaluate(()=>document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    expect(await ap.evaluate(()=>JSON.stringify(localStorage))).toBe('{}');
    await ap.screenshot({path:info.outputPath('support-visitor.png'),fullPage:true});
    await ad.screenshot({path:info.outputPath('support-admin.png'),fullPage:true});
    await ad.evaluate(async()=>{const token=await(await fetch('/api/auth/csrf')).json();await fetch('/api/auth/logout',{method:'POST',headers:{[token.headerName]:token.token}});});
    await expect(ad).toHaveURL('/login',{timeout:18000});
    expect(await ad.evaluate(async()=>(await fetch('/api/admin/support/conversations')).status)).toBe(401);
    expect(await ad.evaluate(async()=>(await fetch('/api/ai/perfumes/recommend/jobs')).status)).toBe(401);
    expect(await bp.evaluate(()=>window.supportXss)).toBeUndefined();
  } finally { await Promise.all([a.close(),b.close(),admin.close()]); }
});
