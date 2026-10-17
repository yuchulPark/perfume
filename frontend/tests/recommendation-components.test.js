import { before, after, test } from 'node:test';
import assert from 'node:assert/strict';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createServer } from 'vite';
import { RecommendationError } from '../src/api/recommendations.js';

let server, RecommendationStatus, RecommendationResults, App;
before(async () => {
  // Vite transforms the actual JSX modules; no extra test runtime or production test fixtures.
  server = await createServer({ server: { middlewareMode: true, hmr: false }, appType: 'custom' });
  ({ RecommendationStatus, RecommendationResults } = await server.ssrLoadModule('/src/components/RecommendationResults.jsx'));
  ({ default: App } = await server.ssrLoadModule('/src/App.jsx'));
});
after(async () => { await server?.close(); });
const render = (component, props) => renderToStaticMarkup(createElement(MemoryRouter, null, createElement(component, props)));

test('React status rendering distinguishes loading, cancellation and safe error messages without fake progress', () => {
  const loading = render(RecommendationStatus, { status: 'loading' });
  assert.match(loading, /role="status"/); assert.match(loading, /추천 응답을 기다리고 있어요/);
  assert.doesNotMatch(loading, /%|progressbar/);
  const cancelled = render(RecommendationStatus, { status: 'cancelled' });
  assert.match(cancelled, /추천 작업 취소가 완료됐어요/); assert.match(cancelled, /백엔드 작업 종료와 정리를 확인했어요/);
  assert.match(render(RecommendationStatus, { status: 'cancelling', confirmed: true }), /AI 추천을 취소하는 중입니다/);
  assert.match(render(RecommendationStatus, { status: 'unknown' }), /작업 상태 확인 불가/);
  assert.match(render(RecommendationStatus, { status: 'lost' }), /서버 재시작이나 보관 기간 만료/);
  for (const [status, kind] of [[400, 'invalid'], [429, 'busy'], [503, 'disabled'], [502, 'server'], [504, 'timeout']]) {
    const error = new RecommendationError(status, kind);
    const html = render(RecommendationStatus, { status: 'error', error });
    assert.match(html, /role="alert"/); assert.ok(html.includes(error.title)); assert.ok(html.includes(error.message));
  }
});

test('React answer paragraphs, citations and source links preserve API order and escape untrusted text', () => {
  const html = render(RecommendationResults, { data: {
    answer: '첫 번째 문단 [2]\n\n두 번째 문단 [1]\n<script>private()</script> [99]', returnedCount: 2,
    sources: [{ perfumeId: 9, name: '첫 번째 향수', brandName: '브랜드 A', similarity: .7 }, { perfumeId: 2, name: '두 번째 향수', brandName: '브랜드 B', similarity: .9 }],
  } });
  assert.match(html, /href="#recommend-source-2"/); assert.match(html, /href="#recommend-source-1"/);
  assert.match(html, /id="recommend-source-1"/); assert.match(html, /href="\/perfumes\/9"/);
  assert.ok(html.indexOf('첫 번째 향수') < html.indexOf('두 번째 향수'));
  assert.match(html, /검색 유사도 0.7/); assert.match(html, /&lt;script&gt;private\(\)&lt;\/script&gt;/);
  assert.doesNotMatch(html, /href="#recommend-source-99"|<script>/);
  assert.match(html, /<p>두 번째 문단/);
});

test('React empty recommendation renders the server answer with no invented sources', () => {
  const html = render(RecommendationResults, { data: { answer: '근거가 없어요.', sources: [], returnedCount: 0 } });
  assert.match(html, /추천 결과가 없어요/); assert.match(html, /근거가 없어요/);
  assert.doesNotMatch(html, /recommend-source-card|href="\/perfumes\//);
});

test('React App routes retain catalog/detail and add an accessible recommendation form and navigation', () => {
  for (const path of ['/', '/perfumes/1', '/recommend']) {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
    const html = renderToStaticMarkup(createElement(QueryClientProvider, { client },
      createElement(MemoryRouter, { initialEntries: [path] }, createElement(App))));
    assert.match(html, /향의 기록/); assert.match(html, /href="\/recommend"/);
    if (path === '/') assert.match(html, /향수 컬렉션/);
    if (path === '/perfumes/1') assert.match(html, /skeleton/);
    if (path === '/recommend') {
      assert.match(html, /<h1>AI 향수 추천<\/h1>/); assert.match(html, /maxLength="500"/i);
      assert.match(html, /임베딩된 일부 향수/); assert.match(html, /aria-current="page"/);
      assert.doesNotMatch(html, /40개/);
    }
    client.clear();
  }
});
