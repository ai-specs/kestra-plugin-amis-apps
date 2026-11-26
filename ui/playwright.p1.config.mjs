import {defineConfig} from '@playwright/test';
export default defineConfig({
  testDir: './tests', testMatch: 'p1.spec.mjs', fullyParallel: false, workers: 1, timeout: 20000,
  outputDir: 'test-results-p1',
  reporter: [['./scripts/p1-report.mjs'], ['list'], ['json', {outputFile: 'reports/p1-chromium.json'}], ['html', {outputFolder: 'reports/p1-html', open: 'never'}]],
  use: {browserName: 'chromium', channel: 'chromium', baseURL: 'http://127.0.0.1:4178', screenshot: 'on', trace: 'retain-on-failure'},
  webServer: {command: 'npm run preview -- --port 4178', url: 'http://127.0.0.1:4178', reuseExistingServer: false}
});
