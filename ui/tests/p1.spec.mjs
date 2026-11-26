import {test, expect} from '@playwright/test';
import {readFile} from 'node:fs/promises';
import {simpleForm, controlsForm, pageSchema, queryTable} from './p1-schemas.mjs';
const origin = 'http://127.0.0.1:4178';
const prefix = '/company.p1/app';
const api = `${prefix}/apis/run`;
const config = {version: 1, namespace: 'company.p1', flowId: 'app', pageId: 'home', schemaUrl: `${prefix}/schemas/home`, apiBase: `${prefix}/apis/`, assetBase: `${prefix}/assets/`, limits: {requestTimeoutMs: 2000, pollIntervalMs: 100, pollTimeoutMs: 4000}};
const ready = {status: 0, msg: 'saved', resultReady: true, executionState: 'SUCCESS', executionId: 'p1-execution', data: {message: 'CHANGED'}};
const pending = {...ready, resultReady: false, executionState: 'RUNNING', executionUrl: `${api}/executions/p1-execution`, data: {}};
const observations = new WeakMap();
async function setup(page, schema = pageSchema(simpleForm), options = {}) {
  const evidence = {scope: 'test-transport', writes: [], reads: [], errors: []}; observations.set(page, evidence);
  page.on('pageerror', error => evidence.errors.push({source: page.url(), message: error.message}));
  const html = await readFile(new URL('../dist/index.html', import.meta.url), 'utf8');
  await page.route('**/*', async route => {
    const request = route.request(); const url = new URL(request.url());
    if (url.origin !== origin) return route.abort();
    if (url.pathname === `${prefix}/pages/home`) return route.fulfill({contentType: 'text/html', body: html.replace('__AMIS_BASE__', `${prefix}/`).replace('__AMIS_CSRF__', 'p1-test-transport-csrf').replace('__AMIS_BOOTSTRAP__', JSON.stringify(config))});
    if (url.pathname.startsWith(`${prefix}/assets/`)) return route.fulfill({response: await route.fetch({url: origin + url.pathname.slice(prefix.length)})});
    if (url.pathname === config.schemaUrl) return route.fulfill({json: schema});
    if (url.pathname === api && request.method() === 'POST') {
      evidence.writes.push(request.postDataJSON());
      if (options.lost) return route.abort('failed');
      if (options.status) return route.fulfill({status: options.status, json: {status: 1}});
      if (options.reply) return route.fulfill({json: {...ready, data: options.reply(request.postDataJSON())}});
      return route.fulfill({status: options.pending ? 202 : 200, json: options.pending ? pending : options.failed ? {...ready, status: 1, executionState: 'FAILED'} : ready});
    }
    if (url.pathname === pending.executionUrl) {
      evidence.reads.push({method: request.method(), path: url.pathname});
      return route.fulfill({status: options.hold ? 202 : 200, json: options.hold ? {...pending, executionState: options.paused ? 'PAUSED' : 'RUNNING'} : ready});
    }
    return route.continue();
  });
  await page.goto(`${prefix}/pages/home`);
  return evidence;
}
async function submit(page) {await page.locator('input[name="name"]').fill('CHANGED'); await page.getByRole('button', {name: 'Save changed data', exact: true}).click();}
test.afterEach(async ({page}, info) => {
  const evidence = observations.get(page);
  if (evidence) {await info.attach('p1-transport-observations', {body: JSON.stringify(evidence), contentType: 'application/json'}); expect(evidence.errors).toEqual([]);}
});
for (const locale of ['en', 'zh-CN']) for (const theme of ['light', 'dark']) for (const width of ['desktop', 'narrow']) {
  test(`P1 TRANSPORT controls ${locale} ${theme} ${width}`, async ({page}) => {
    await page.setViewportSize({width: width === 'desktop' ? 1280 : 390, height: 900});
    await page.addInitScript(({locale, theme}) => {localStorage.setItem('lang', locale); localStorage.setItem('theme', theme);}, {locale, theme});
    const evidence = await setup(page, pageSchema(controlsForm));
    await page.locator('input[name="name"]').fill('CHANGED');
    await page.locator('input[name="quantity"]').fill('7');
    await page.locator('.cxd-Select, .dark-Select').first().click();
    await page.getByText('Beta', {exact: true}).click();
    if (width === 'narrow') await page.getByRole('button', {name: /^(Confirm|确认|确定)$/}).click();
    if (width === 'narrow') {
      await page.locator('.cxd-DatePicker input, .dark-DatePicker input').click();
      await page.getByRole('button', {name: locale === 'zh-CN' ? '28日' : '28', exact: true}).click();
      await page.getByRole('button', {name: /^(Confirm|确认|确定)$/}).click();
    } else {
      await page.locator('.cxd-DatePicker input, .dark-DatePicker input').fill('2026-12-01');
      await page.locator('.cxd-DatePicker input, .dark-DatePicker input').press('Tab');
    }
    await page.getByRole('checkbox').locator('..').click();
    await page.locator('input[name="details"]').fill('conditional');
    await page.getByRole('textbox').last().fill('updated');
    await page.getByRole('button', {name: 'Save changed data', exact: true}).click();
    await expect(page.getByText('Saved: CHANGED', {exact: true})).toBeVisible();
    expect(evidence.writes).toHaveLength(1);
    expect(evidence.writes[0]).toMatchObject({name: 'CHANGED', quantity: 7, category: 'beta', date: width === 'narrow' ? '2026-11-28' : '2026-12-01', enabled: true, details: 'conditional', tags: ['updated']});
    await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    await page.locator('input[name="name"]').focus(); await expect(page.locator('input[name="name"]')).toBeFocused();
  });
}
test('P1 TRANSPORT required validation prevents POST', async ({page}) => {
  const e = await setup(page); await page.getByRole('button', {name: 'Save changed data'}).click();
  await expect(page.locator('.cxd-Form-feedback')).toBeVisible(); expect(e.writes).toHaveLength(0);
});
test('P1 TRANSPORT conditional visibility and conditional POST', async ({page}) => {
  const schema = pageSchema({type: 'form', body: [{type: 'switch', name: 'load', label: 'Load records'}, {type: 'service', visibleOn: '${load}', initFetchOn: '${load}', api: {method: 'post', url: 'run', data: {action: 'conditional'}}, body: {type: 'tpl', tpl: 'Conditional: ${message}'}}], actions: []});
  const e = await setup(page, schema); expect(e.writes).toHaveLength(0); await page.getByRole('checkbox').locator('..').click();
  await expect(page.getByText('Conditional: CHANGED', {exact: true})).toBeVisible(); expect(e.writes).toEqual([{action: 'conditional'}]);
});
test('P1 TRANSPORT page init POST loads business data', async ({page}) => {
  const e = await setup(page, {type: 'page', title: 'P1 TEST TRANSPORT', initApi: {method: 'post', url: 'run', data: {action: 'init'}}, body: {type: 'tpl', tpl: 'Initial: ${message}'}});
  await expect(page.getByText('Initial: CHANGED', {exact: true})).toBeVisible(); expect(e.writes).toEqual([{action: 'init'}]);
});
test('P1 TRANSPORT CRUD POST pagination and empty result', async ({page}) => {
  const e = await setup(page, pageSchema(queryTable), {reply: data => ({items: Number(data.page) === 1 ? [{id: 1, name: 'ROW-ONE'}, {id: 2, name: 'ROW-TWO'}] : [], total: 3})});
  await expect(page.getByText('ROW-ONE', {exact: true})).toBeVisible();
  await page.getByText('2', {exact: true}).click(); await expect(page.getByText('No business records', {exact: true})).toBeVisible();
  expect(e.writes.map(value => Number(value.page))).toEqual([1, 2]); expect(e.writes.every(value => value.action === 'query')).toBe(true);
});
test('P1 TRANSPORT dialog form saves changed data', async ({page}) => {
  const e = await setup(page, pageSchema({type: 'button', label: 'Open form', actionType: 'dialog', dialog: {title: 'Business dialog', closeOnConfirm: false, body: simpleForm}}));
  await page.getByRole('button', {name: 'Open form'}).click(); await page.locator('input[name="name"]').fill('CHANGED');
  await page.getByRole('dialog').getByRole('button', {name: 'Confirm', exact: true}).click();
  await expect(page.getByText('Saved: CHANGED', {exact: true})).toBeVisible(); expect(e.writes).toEqual([{name: 'CHANGED'}]);
});
test('P1 TRANSPORT paused keeps busy cancel and duplicate merge', async ({page}) => {
  const e = await setup(page, undefined, {pending: true, hold: true, paused: true}); await submit(page);
  await expect(page.getByRole('status')).toHaveText('Execution paused; waiting for resume…'); await expect(page.locator('main')).toHaveAttribute('aria-busy', 'true');
  const disabledState=await page.locator('fieldset.amis-content').evaluate(node=>({disabled:node.disabled,childDisabled:[...node.querySelectorAll('button')].every(button=>button.matches(':disabled'))}));
  expect(disabledState).toEqual({disabled:true,childDisabled:true});
  const submitButton = page.getByRole('button', {name: 'Save changed data'});
  await page.keyboard.press('Enter'); expect(e.writes).toHaveLength(1);
  for (let step = 0; step < 12 && !await page.getByRole('button', {name: 'Cancel request', exact: true}).evaluate(node => node === document.activeElement); step++) await page.keyboard.press('Tab');
  await page.keyboard.press('Tab'); await page.keyboard.press('Shift+Tab');
  await expect(page.getByRole('button', {name: 'Cancel request', exact: true})).toBeFocused();
  await page.keyboard.press('Enter'); await expect(page.getByRole('alert')).toContainText('Request cancelled');
  await expect(page.getByRole('button', {name: 'Resume result query'})).toBeVisible();
});
test('P1 TRANSPORT double submission creates one POST', async ({page}) => {
  const e = await setup(page, undefined, {pending: true, hold: true}); await page.locator('input[name="name"]').fill('CHANGED');
  await page.getByRole('button', {name: 'Save changed data'}).dblclick(); await expect(page.getByRole('status')).toContainText('Waiting'); expect(e.writes).toHaveLength(1);
});
for (const [id, leave] of [['refresh', false], ['leave and reenter', true]]) {
  test(`P1 TRANSPORT ${id === 'refresh' ? 'refresh' : 'leave and reenter'} resumes only original GET`, async ({page}) => {
    const e = await setup(page, undefined, {pending: true, hold: true}); await submit(page); await expect(page.getByRole('button', {name: 'Cancel request', exact: true})).toBeVisible();
    await expect.poll(() => e.reads.length).toBeGreaterThan(0);
    if (leave) {await page.goto('about:blank'); await page.goto(`${prefix}/pages/home`);} else await page.reload();
    await expect(page.getByRole('button', {name: 'Resume result query'})).toBeVisible(); expect(e.writes).toHaveLength(1);
    const before = e.reads.length; await page.getByRole('button', {name: 'Resume result query'}).click(); await expect.poll(() => e.reads.length).toBeGreaterThan(before); expect(e.writes).toHaveLength(1);
  });
}
test('P1 TRANSPORT lost submission response prevents implicit retry', async ({page}) => {
  const e = await setup(page, undefined, {lost: true}); await submit(page); await expect(page.getByText('Submission outcome is unknown. Automatic retry is disabled.')).toBeVisible();
  await page.reload(); await submit(page); await expect(page.getByRole('alert')).toContainText('outcome is unknown'); expect(e.writes).toHaveLength(1);
});
test('P1 TRANSPORT HTTP error is visible without empty success', async ({page}) => {
  await setup(page, undefined, {status: 503}); await submit(page); await expect(page.getByRole('alert')).toContainText('HTTP 503'); await expect(page.getByText('Saved: CHANGED', {exact: true})).toHaveCount(0);
});
test('P1 TRANSPORT failed execution is visible', async ({page}) => {
  await setup(page, undefined, {failed: true}); await submit(page); await expect(page.getByRole('alert')).toContainText('Execution failed (FAILED)');
});
