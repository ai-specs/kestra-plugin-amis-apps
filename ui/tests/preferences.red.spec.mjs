import {test, expect} from '@playwright/test';
import {readFile} from 'node:fs/promises';

const prefix = '/company._team/example.app';
test.beforeEach(async ({page}) => {
  await page.addInitScript(() => Object.defineProperty(navigator, 'language', {value: 'en'}));
  await page.emulateMedia({colorScheme: 'light'});
  const html = await readFile(new URL('../dist/index.html', import.meta.url), 'utf8');
  const config = {version: 1, namespace: 'company._team', flowId: 'example.app', pageId: 'home', schemaUrl: `${prefix}/schemas/home`, apiBase: `${prefix}/apis/`, assetBase: `${prefix}/assets/`, limits: {requestTimeoutMs: 1000, pollIntervalMs: 100, pollTimeoutMs: 1500}};
  await page.route('**/*', async route => {
    const url = new URL(route.request().url());
    if (url.origin !== 'http://127.0.0.1:4177') return route.abort();
    if (url.pathname === `${prefix}/pages/home`) return route.fulfill({contentType: 'text/html', body: html.replace('__AMIS_BASE__', `${prefix}/`).replace('__AMIS_CSRF__', 'test-only-csrf').replace('__AMIS_BOOTSTRAP__', JSON.stringify(config))});
    if (url.pathname === config.schemaUrl) return route.fulfill({json: {type: 'page', title: 'TEST TRANSPORT — FORMAL PREFERENCES CONTRACT', body: {type: 'tpl', tpl: 'Real AMIS preference regression'}}});
    if (url.pathname.startsWith(`${prefix}/assets/`)) return route.fulfill({response: await route.fetch({url: `http://127.0.0.1:4177${url.pathname.slice(prefix.length)}`})});
    return route.continue();
  });
  await page.goto(`${prefix}/pages/home`);
  await expect(page.getByText('Real AMIS preference regression', {exact: true})).toBeVisible();
});

test('FORMAL preference regression: selected locale survives refresh', async ({page}) => {
  await page.getByRole('combobox', {name: 'Language', exact: true}).selectOption('zh_CN');
  await expect(page.getByRole('combobox', {name: '语言', exact: true})).toHaveValue('zh_CN');
  await page.reload();
  await expect(page.locator('html')).toHaveAttribute('lang', 'zh-CN');
});

test('FORMAL preference regression: selected theme survives refresh', async ({page}) => {
  await page.getByRole('combobox', {name: 'Theme', exact: true}).selectOption('dark');
  await expect(page.locator('.dark-Page')).toBeVisible();
  await page.reload();
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
});
