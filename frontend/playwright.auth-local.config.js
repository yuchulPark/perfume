import { defineConfig } from '@playwright/test';

const baseURL = process.env.PERFUME_AUTH_LOCAL_ORIGIN || 'http://127.0.0.1:8081';
if (!['http://127.0.0.1:8081', 'http://127.0.0.1:5173', 'http://127.0.0.1:4173', 'http://127.0.0.1:18081'].includes(baseURL))
  throw new Error('Only the explicit loopback verification servers are accepted.');

// Explicit live-local suite. No routes are mocked and no production addresses are accepted.
export default defineConfig({
  testDir: './tests/local',
  testIgnore: baseURL.endsWith(':18081') ? '**/auth-live.spec.js' : '**/auth-disabled-live.spec.js',
  fullyParallel: false,
  workers: 1,
  timeout: 30_000,
  reporter: 'list',
  outputDir: './test-results/auth-live',
  use: { baseURL, channel: 'chrome', headless: true,
    trace: 'off', screenshot: 'only-on-failure' },
  projects: [
    { name: 'desktop', use: { viewport: { width: 1440, height: 1000 } } },
    { name: 'mobile', use: { viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true } },
  ],
});
