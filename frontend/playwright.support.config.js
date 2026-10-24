import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './tests/support-browser', fullyParallel: false, workers: 1, timeout: 90000, reporter: 'list',
  outputDir: '../target/support-local/playwright-results',
  use: { baseURL: 'http://127.0.0.1:18082', channel: 'chrome', headless: true, trace: 'off' },
  projects: [
    { name: 'desktop', use: { viewport: { width: 1440, height: 1000 } } },
    { name: 'mobile', use: { viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true } },
  ],
});
