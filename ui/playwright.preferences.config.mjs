import {defineConfig} from '@playwright/test';
import runtime from './playwright.config.mjs';

export default defineConfig({...runtime,
  testMatch: 'preferences.red.spec.mjs',
  outputDir: 'test-results-preferences-red',
  expect: {timeout: 1500},
  reporter: [['list'], ['json', {outputFile: 'reports/preferences-red.json'}], ['html', {outputFolder: 'reports/preferences-red-html', open: 'never'}]]
});
