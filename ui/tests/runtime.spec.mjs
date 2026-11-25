import {test, expect} from '@playwright/test';
import {readFile} from 'node:fs/promises';
const prefix = '/company._team/example.app';
const api = `${prefix}/apis/save`;
const config = {version: 1, namespace: 'company._team', flowId: 'example.app', pageId: 'home', schemaUrl: `${prefix}/schemas/home`, apiBase: `${prefix}/apis/`, assetBase: `${prefix}/assets/`, limits: {requestTimeoutMs: 1000, pollIntervalMs: 100, pollTimeoutMs: 1500}};
const ready = {status: 0, msg: 'saved', resultReady: true, executionState: 'SUCCESS', executionId: 'exec-1', data: {saved: 'SAVED-VALUE'}};
const pending = {...ready, resultReady: false, executionState: 'RUNNING', executionUrl: `${api}/executions/exec-1`, data: {}};
const observations = new WeakMap();

test.afterEach(async ({page}, testInfo) => {
  const evidence = observations.get(page);
  if (!evidence) return;
  await testInfo.attach('transport-and-browser-observations', {body: JSON.stringify(evidence), contentType: 'application/json'});
  const unexpected = evidence.errors.filter(error => {
    const expected = evidence.expectedHttpError;
    return !(expected && error.url === `http://127.0.0.1:4177${expected.path}` && error.text === `Failed to load resource: the server responded with a status of ${expected.status} (${({401: 'Unauthorized', 403: 'Forbidden', 404: 'Not Found', 503: 'Service Unavailable'})[expected.status]})`);
  });
  expect(unexpected).toEqual([]);
  expect(evidence.blocked).toEqual([]);
});

async function setup(page, options = {}) {
  const writes = []; const reads = []; const errors = []; const blocked = []; const networkWrites = [];
  const requestApi = options.api && /^[A-Za-z0-9_][A-Za-z0-9_-]{0,99}$/.test(options.api) ? `${config.apiBase}${options.api}` : api;
  const evidence = {writes, reads, errors, blocked, networkWrites, expectedHttpError: options.schemaStatus ? {path: config.schemaUrl, status: options.schemaStatus} : options.status ? {path: api, status: options.status} : null};
  observations.set(page, evidence);
  page.on('pageerror', error => errors.push({text: error.message, url: page.url(), source: 'pageerror'}));
  page.on('console', message => {if (message.type() === 'error') errors.push({text: message.text(), url: message.location().url, source: 'console', document: page.url()});});
  const html = await readFile(new URL('../dist/index.html', import.meta.url), 'utf8');
  await page.route('**/*', async route => {
    const url = new URL(route.request().url());
    if (route.request().method() === 'POST') networkWrites.push({url: url.href, body: route.request().postData()});
    if (url.origin !== 'http://127.0.0.1:4177') {blocked.push(url.href); return route.abort();}
    if (url.pathname === `${prefix}/pages/home`) return route.fulfill({contentType: 'text/html', body: html.replace('__AMIS_BASE__', `${prefix}/`).replace('__AMIS_CSRF__', options.csrf ?? 'test-only-csrf').replace('__AMIS_BOOTSTRAP__', JSON.stringify({...config, ...options.config}))});
    if (url.pathname.startsWith(`${prefix}/assets/`)) {
      const response = await route.fetch({url: `http://127.0.0.1:4177${url.pathname.slice(prefix.length)}`});
      return route.fulfill({response});
    }
    if (url.pathname === config.schemaUrl) {
      if (options.delaySchema) await new Promise(resolve => setTimeout(resolve, options.delaySchema));
      if (options.schemaStatus) return route.fulfill({status: options.schemaStatus, json: {}});
      const chinese = options.locale === 'zh-CN';
      return route.fulfill({json: {type: 'page', title: 'TEST TRANSPORT — NOT PRODUCTION', body: {type: 'form', title: chinese ? '测试表单' : 'Test form', api: `post:${options.api || api}`, body: [{type: 'input-text', name: 'name', label: chinese ? '名称' : 'Name', required: true}, {type: 'tpl', tpl: 'Result: ${saved}', id: 'result'}], actions: [{type: 'submit', label: chinese ? '提交' : 'Submit'}]}}});
    }
    if (url.pathname === requestApi && route.request().method() === 'POST') {
      writes.push({url: url.pathname, body: route.request().postDataJSON(), csrf: route.request().headers()['x-csrf-token']});
      if (options.status) return route.fulfill({status: options.status, json: {status: 1}});
      return route.fulfill({status: options.pending ? 202 : 200, json: options.initial || (options.pending ? pending : ready)});
    }
    if (url.pathname === pending.executionUrl) {
      reads.push(url.href);
      if (options.pollResponses) return route.fulfill(options.pollResponses[reads.length - 1]);
      return route.fulfill({status: options.forever || reads.length === 1 ? 202 : 200, json: options.forever || reads.length === 1 ? {...pending, executionState: 'SUCCESS'} : ready});
    }
    if (url.pathname === '/oidc/logout') return route.fulfill({contentType: 'text/html', body: '<h1>TEST TRANSPORT logout target only</h1>'});
    return route.continue();
  });
  await page.goto(`${prefix}/pages/home`);
  return evidence;
}
async function submit(page, locale = 'en') {
  await expect(page.locator('.cxd-Form-label, .dark-Form-label')).toContainText(locale === 'zh-CN' ? '名称' : 'Name');
  await page.getByRole('textbox').fill('SAVED-VALUE');
  await page.getByRole('button', {name: locale === 'zh-CN' ? '提交' : 'Submit', exact: true}).click();
}

for (const locale of ['en', 'zh-CN']) for (const theme of ['light', 'dark', 'dark-2', 'syncWithSystem', 'narrow-focus']) {
  test(`TEST TRANSPORT matrix ${locale} ${theme}: real AMIS input submit result`, async ({page}, testInfo) => {
    await page.context().setExtraHTTPHeaders({'X-Test-Transport': 'AMIS-UI-20261125'});
    await page.addInitScript(language => Object.defineProperty(navigator, 'language', {value: language}), locale);
    if (theme === 'narrow-focus') await page.setViewportSize({width: 390, height: 844});
    await page.emulateMedia({colorScheme: 'dark'});
    const evidence = await setup(page, {locale});
    if (locale === 'zh-CN') {
      await page.getByRole('combobox', {name: 'Language', exact: true}).selectOption('zh_CN');
      await expect(page.getByRole('combobox', {name: '语言', exact: true})).toHaveValue('zh_CN');
    }
    const selector = page.getByRole('combobox', {name: locale === 'zh-CN' ? '主题' : 'Theme', exact: true});
    await selector.selectOption(theme === 'narrow-focus' ? 'light' : theme);
    await expect(page.locator('html')).toHaveAttribute('data-theme', theme === 'syncWithSystem' ? 'dark' : theme === 'narrow-focus' ? 'light' : theme);
    if (theme === 'syncWithSystem') {
      await expect(page.locator('.dark-Page')).toBeVisible();
      await expect(page.getByRole('textbox')).toHaveCSS('color', 'rgb(247, 248, 250)');
      await page.emulateMedia({colorScheme: 'light'});
      await expect(page.locator('html')).toHaveAttribute('data-theme', 'light');
    }
    const input = page.getByRole('textbox');
    const dark = theme === 'dark' || theme === 'dark-2';
    const skin = dark ? 'dark' : 'cxd';
    await expect(page.locator(`.${skin}-Page`)).toBeVisible();
    await expect(page.locator(`style[data-amis-skin="${skin}"]`)).toHaveCount(1);
    await expect(page.locator(`.${skin}-TextControl-input`)).toHaveCSS('background-color', dark ? 'rgb(7, 13, 20)' : 'rgb(255, 255, 255)');
    await expect(input).toHaveCSS('color', dark ? 'rgb(247, 248, 250)' : 'rgb(21, 27, 38)');
    await input.focus(); await expect(input).toBeFocused();
    expect(await input.evaluate(element => getComputedStyle(element).outlineStyle)).not.toBe('none');
    for (const locator of [input, page.locator(`.${skin}-Page-title`), page.locator(`.${skin}-Panel-title`), page.locator(`.${skin}-Form-label`)]) {
      const contrast = await locator.evaluate(element => {
        const parse = color => color.match(/[\d.]+/g).slice(0, 3).map(Number);
        let ancestor = element; let background;
        while (ancestor) {const color = getComputedStyle(ancestor).backgroundColor; if (color !== 'rgba(0, 0, 0, 0)' && color !== 'transparent') {background = parse(color); break;} ancestor = ancestor.parentElement;}
        const luminance = values => values.map(value => {const channel = value / 255; return channel <= .04045 ? channel / 12.92 : ((channel + .055) / 1.055) ** 2.4;}).reduce((sum, value, index) => sum + value * [.2126, .7152, .0722][index], 0);
        const foreground = luminance(parse(getComputedStyle(element).color)); const back = luminance(background || [255, 255, 255]);
        return {ratio: (Math.max(foreground, back) + .05) / (Math.min(foreground, back) + .05), color: getComputedStyle(element).color, background, ancestor: ancestor?.className, element: element.outerHTML};
      });
      expect(contrast.ratio, JSON.stringify(contrast)).toBeGreaterThanOrEqual(4.5);
    }
    if (theme === 'narrow-focus') {await page.keyboard.press('Tab'); await page.keyboard.press('Shift+Tab'); await expect(input).toBeFocused(); expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);}
    await submit(page, locale);
    await expect(page.getByText('Result: SAVED-VALUE', {exact: true})).toBeVisible();
    await expect(page.getByRole('button', {name: locale === 'zh-CN' ? '提交' : 'Submit', exact: true})).toBeEnabled();
    expect(evidence.writes).toHaveLength(1); expect(evidence.writes[0].body).toEqual({name: 'SAVED-VALUE'}); expect(evidence.writes[0].csrf).toBe('test-only-csrf');
    expect(evidence.errors).toEqual([]); expect(evidence.blocked).toEqual([]);
    await testInfo.attach('test-transport-body', {body: JSON.stringify(evidence), contentType: 'application/json'});
  });
}
test('TEST TRANSPORT schema loading then real renderer', async ({page}) => {
  await setup(page, {delaySchema: 500}); await expect(page.getByRole('status')).toHaveText('Loading schema…');
  await expect(page.getByRole('textbox')).toBeVisible();
});
for (const status of [401, 403, 404, 503]) {
  test(`TEST TRANSPORT schema HTTP ${status}: visible error and exit`, async ({page}) => {
    await setup(page, {schemaStatus: status}); await expect(page.getByRole('alert')).toContainText(`HTTP ${status}`);
    await page.getByRole('alert').getByRole('link').click(); await expect(page).toHaveURL(/\/oidc\/logout$/);
  });
  test(`TEST TRANSPORT API HTTP ${status}: no empty success`, async ({page}) => {
    await setup(page, {status}); await submit(page); await expect(page.getByRole('alert')).toContainText(`HTTP ${status}`);
    await expect(page.getByText('Result: SAVED-VALUE', {exact: true})).toHaveCount(0);
  });
}
test('TEST TRANSPORT 202 polling waits through terminal state with unreadable outputs', async ({page}) => {
  const evidence = await setup(page, {pending: true}); await submit(page);
  await expect(page.getByRole('status')).toHaveText('Waiting for execution result…');
  await expect(page.getByText('Result: SAVED-VALUE', {exact: true})).toBeVisible(); expect(evidence.reads).toHaveLength(2); expect(evidence.writes).toHaveLength(1);
});
test('TEST TRANSPORT failed execution is visible', async ({page}) => {
  await setup(page, {initial: {...ready, status: 1, executionState: 'FAILED'}}); await submit(page); await expect(page.getByRole('alert')).toContainText('Execution failed (FAILED)');
});
test('TEST TRANSPORT external polling rejected before any external request', async ({page}) => {
  const evidence = await setup(page, {pending: true, initial: {...pending, executionUrl: 'https://evil.test/poll'}}); await submit(page);
  await expect(page.getByRole('alert')).toContainText('Unsafe URL'); expect(evidence.reads).toHaveLength(0); expect(evidence.blocked).toHaveLength(0);
});
test('TEST TRANSPORT explicit cancellation stops polling', async ({page}) => {
  const evidence = await setup(page, {pending: true, forever: true}); await submit(page);
  await page.getByRole('button', {name: 'Cancel request', exact: true}).click(); await expect(page.getByRole('alert')).toContainText('Request cancelled');
  const count = evidence.reads.length; await page.waitForTimeout(350); expect(evidence.reads).toHaveLength(count); expect(evidence.writes).toHaveLength(1);
});
test('TEST TRANSPORT unload cancels polling', async ({page}) => {
  const evidence = await setup(page, {pending: true, forever: true}); await submit(page); await expect(page.getByRole('status')).toHaveText('Waiting for execution result…');
  await page.getByRole('link', {name: 'Sign out / switch account', exact: true}).click(); const count = evidence.reads.length;
  await page.waitForTimeout(350); expect(evidence.reads).toHaveLength(count);
});
test('TEST TRANSPORT polling timeout has visible error', async ({page}) => {
  await setup(page, {pending: true, forever: true}); await submit(page); await expect(page.getByRole('alert')).toContainText('Request timed out');
});
test('TEST TRANSPORT missing CSRF prevents API write', async ({page}) => {
  const evidence = await setup(page, {csrf: ''}); await submit(page); await expect(page.getByRole('alert')).toContainText('Missing CSRF token'); expect(evidence.writes).toHaveLength(0);
});

test('TEST TRANSPORT review bare greet uses bootstrap API base', async ({page}) => {
  const evidence = await setup(page, {api: 'greet'}); await submit(page);
  await expect(page.getByText('Result: SAVED-VALUE', {exact: true})).toBeVisible();
  expect(evidence.writes).toEqual([{url: `${config.apiBase}greet`, body: {name: 'SAVED-VALUE'}, csrf: 'test-only-csrf'}]);
});
for (const [name, change] of [['missing execution ID', {executionId: undefined}], ['unknown execution state', {executionState: 'FUTURE_SUCCESS'}], ['running result claimed ready', {executionState: 'RUNNING'}]]) {
  test(`TEST TRANSPORT review SYNC rejects ${name}`, async ({page}) => {
    await setup(page, {initial: {...ready, ...change}}); await submit(page);
    await expect(page.getByRole('alert')).toBeVisible();
    await expect(page.getByText('Result: SAVED-VALUE', {exact: true})).toHaveCount(0);
  });
}
test('TEST TRANSPORT review polling rejects unknown state', async ({page}) => {
  await setup(page, {pending: true, pollResponses: [{status: 200, json: {...ready, executionState: 'FUTURE_SUCCESS'}}]}); await submit(page);
  await expect(page.getByRole('alert')).toBeVisible();
  await expect(page.getByText('Result: SAVED-VALUE', {exact: true})).toHaveCount(0);
});
test('TEST TRANSPORT review polling ignores later external URL', async ({page}) => {
  const evidence = await setup(page, {pending: true, pollResponses: [{status: 202, json: {...pending, executionState: 'SUCCESS', executionUrl: 'https://evil.test/poll'}}, {status: 200, json: {...ready, executionUrl: '/api/v1/main/executions/other'}}]}); await submit(page);
  await expect(page.getByText('Result: SAVED-VALUE', {exact: true})).toBeVisible();
  expect(evidence.reads).toEqual([`http://127.0.0.1:4177${pending.executionUrl}`, `http://127.0.0.1:4177${pending.executionUrl}`]);
  expect(evidence.writes).toHaveLength(1);
});
for (const raw of ['../greet', '/api/v1/main/flows', 'https://evil.test/greet']) {
  test(`TEST TRANSPORT review API rejects ${raw} before network write`, async ({page}) => {
    const evidence = await setup(page, {api: raw}); await submit(page);
    await expect(page.getByRole('alert')).toBeVisible(); expect(evidence.networkWrites).toHaveLength(0);
  });
}
