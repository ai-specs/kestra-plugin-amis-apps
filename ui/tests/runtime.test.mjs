import {test} from 'node:test';
import assert from 'node:assert/strict';
import {parseBootstrap, safeUrl, validateExecutionUrl, createTransport} from '../src/runtime.mjs';
const origin = 'https://example.test';
const config = {version: 1, namespace: 'company._team', flowId: 'example.app', pageId: 'home', schemaUrl: '/company._team/example.app/schemas/home', apiBase: '/company._team/example.app/apis/', assetBase: '/company._team/example.app/assets/', limits: {requestTimeoutMs: 200, pollIntervalMs: 1, pollTimeoutMs: 50}};
const api = new URL(`${config.apiBase}save`, origin);
const ready = {status: 0, resultReady: true, executionId: 'exec-1', executionState: 'SUCCESS', data: {saved: 'hello'}};
const pending = {...ready, resultReady: false, executionState: 'RUNNING', executionUrl: `${api.pathname}/executions/exec-1`, data: {}};
const response = (body, status = 200) => new Response(JSON.stringify(body), {status, headers: {'Content-Type': 'application/json'}});

test('bootstrap V1 and safe namespace accepted', () => assert.equal(parseBootstrap(config, origin), config));
test('custom page identifier permits leading underscore', () => assert.equal(parseBootstrap({...config, pageId: '_home', schemaUrl: '/company._team/example.app/schemas/_home'}, origin).pageId, '_home'));
for (const change of [{version: 2}, {namespace: 'Company'}, {flowId: '../a'}, {pageId: '%61'}, {pageId: 'home.json'}, {pageId: 1}, {schemaUrl: '/other/schema'}, {apiBase: '/api/v1/main/'}, {assetBase: 'https://evil.test/assets/'}, {limits: {...config.limits, pollTimeoutMs: 9999999}}]) {
  test(`reject bootstrap ${JSON.stringify(change)}`, () => assert.throws(() => parseBootstrap({...config, ...change}, origin)));
}
for (const raw of ['https://evil.test/path', '//evil.test/path', '/%2e/path', '/a/../path', '/a\\b', '/path?x=1', '/path#x', 'https://user:pass@example.test/path']) {
  test(`reject unsafe URL ${raw}`, () => assert.throws(() => safeUrl(raw, origin)));
}
for (const raw of ['https://evil.test/poll', '/api/v1/main/executions/exec-1', `${api.pathname}/executions/other`, '/company._team/other/apis/save/executions/exec-1']) {
  test(`reject polling identity ${raw}`, () => assert.throws(() => validateExecutionUrl(raw, api, 'exec-1', origin)));
}
test('poll uses initial URL and sends exact JSON with CSRF', async () => {
  const calls = [];
  const transport = createTransport(config, {origin, csrf: 'test-csrf', signal: new AbortController().signal, fetchImpl: async (url, options) => {calls.push({url, ...options}); return calls.length === 1 ? response(pending, 202) : response(ready);}});
  assert.deepEqual((await transport.fetcher({url: api.pathname, method: 'post', data: {name: 'hello'}})).data, ready);
  assert.equal(calls.length, 2); assert.equal(calls[0].body, '{"name":"hello"}'); assert.equal(calls[0].headers['X-CSRF-TOKEN'], 'test-csrf');
  assert.equal(calls[1].url, `${origin}${pending.executionUrl}`); assert.equal(calls[1].method, 'GET'); assert.equal(calls[0].redirect, 'error');
});
for (const status of [401, 403, 404, 409, 413, 429, 503]) {
  test(`HTTP ${status} rejects visibly`, async () => {
    let visible;
    const transport = createTransport(config, {origin, csrf: 'csrf', signal: new AbortController().signal, fetchImpl: async () => response({}, status), onError: value => {visible = value;}});
    await assert.rejects(transport.fetcher({url: api.pathname, method: 'post', data: {}}), new RegExp(`HTTP ${status}`)); assert.equal(visible, `HTTP ${status}`);
  });
}
for (const [name, body, status] of [['not ready', {...pending, executionUrl: undefined}, 202], ['missing data', {...ready, data: undefined}, 200], ['failed execution', {...ready, status: 1, executionState: 'FAILED'}, 200], ['cross origin', {...pending, executionUrl: 'https://evil.test/poll'}, 202]]) {
  test(`reject ${name}`, async () => {
    const transport = createTransport(config, {origin, csrf: 'csrf', signal: new AbortController().signal, fetchImpl: async () => response(body, status)});
    await assert.rejects(transport.fetcher({url: api.pathname, method: 'post', data: {}}));
  });
}
test('native API and missing CSRF make zero network writes', async () => {
  let calls = 0;
  const transport = createTransport(config, {origin, csrf: '', signal: new AbortController().signal, fetchImpl: async () => {calls++; return response(ready);}});
  await assert.rejects(transport.fetcher({url: '/api/v1/main/flows', method: 'post', data: {}}));
  await assert.rejects(transport.fetcher({url: api.pathname, method: 'post', data: {}})); assert.equal(calls, 0);
});
test('AbortSignal cancels wait without second request', async () => {
  const controller = new AbortController(); let calls = 0;
  const transport = createTransport({...config, limits: {...config.limits, pollIntervalMs: 100}}, {origin, csrf: 'csrf', signal: controller.signal, fetchImpl: async () => {calls++; controller.abort(); return response(pending, 202);}});
  await assert.rejects(transport.fetcher({url: api.pathname, method: 'post', data: {}})); assert.equal(calls, 1);
});
test('poll total wait is bounded', async () => {
  const transport = createTransport(config, {origin, csrf: 'csrf', signal: new AbortController().signal, fetchImpl: async () => response(pending, 202)});
  await assert.rejects(transport.fetcher({url: api.pathname, method: 'post', data: {}}), {name: 'TimeoutError'});
});
test('AMIS config.cancelExecutor cancels polling', async () => {
  let cancel; let calls = 0;
  const transport = createTransport(config, {origin, csrf: 'csrf', signal: new AbortController().signal, fetchImpl: async () => {calls++; cancel(); return response(pending, 202);}});
  await assert.rejects(transport.fetcher({url: api.pathname, method: 'post', data: {}, config: {cancelExecutor: executor => {cancel = executor;}}}), {name: 'AbortError'});
  assert.equal(calls, 1);
});
test('public SDK locale keys and nonempty parameter-compatible fallback messages', async () => {
  const {supportedLocales, resolveLocale} = await import('../.host-sdk/locales.mjs');
  const {localeMessages} = await import('../src/messages.mjs');
  assert.equal(supportedLocales.length, 13); assert.equal(resolveLocale('zh-CN'), 'zh_CN'); assert.equal(resolveLocale('unknown'), 'en');
  assert.deepEqual(Object.keys(localeMessages), supportedLocales);
  const english = localeMessages.en;
  for (const locale of supportedLocales) {
    const values = localeMessages[locale];
    assert.deepEqual(Object.keys(values).sort(), Object.keys(english).sort());
    for (const [key, value] of Object.entries(values)) {assert.ok(value.trim(), `${locale}.${key}`); assert.deepEqual(value.match(/\{\w+\}/g), english[key].match(/\{\w+\}/g));}
  }
});
