import {defineConfig} from '@playwright/test';
export default defineConfig({
  testDir: './tests',
  testMatch: 'runtime.spec.mjs',
  fullyParallel: false,
  workers: 1,
  timeout: 30000,
  reporter: [['list'], ['json', {outputFile: 'reports/chromium.json'}], ['html', {outputFolder: 'reports/chromium-html', open: 'never'}]],
  use: {browserName: 'chromium', channel: 'chromium', baseURL: 'http://127.0.0.1:4177', screenshot: 'on', trace: 'retain-on-failure'},
  webServer: {command: 'npm run preview', url: 'http://127.0.0.1:4177', reuseExistingServer: false}
});
