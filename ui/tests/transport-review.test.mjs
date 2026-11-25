import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createTransport, parseBootstrap} from '../src/runtime.mjs';

const origin = 'https://transport.test';
const prefix = '/company._team/example.app';
const config = {version: 1, namespace: 'company._team', flowId: 'example.app', pageId: '_home', schemaUrl: `${prefix}/schemas/_home`, apiBase: `${prefix}/apis/`, assetBase: `${prefix}/assets/`, limits: {requestTimeoutMs: 200, pollIntervalMs: 1, pollTimeoutMs: 500}};
const ready = {status: 0, executionId: 'exec-1', executionState: 'SUCCESS', resultReady: true, data: {saved: 'transport-result'}};
const pending = {...ready, executionState: 'RUNNING', resultReady: false, executionUrl: `${config.apiBase}greet/executions/exec-1`, data: {}};
const jsonResponse = (body, status) => new Response(JSON.stringify(body), {status});

function harness(responses) {
  const calls = []; const errors = []; const states = [];
  const transport = createTransport(config, {origin, csrf: 'test-transport-csrf', signal: new AbortController().signal,
    onError: value => errors.push(value), onState: value => states.push(value),
    fetchImpl: async (url, options) => {calls.push({url, ...options}); const next = responses[calls.length - 1]; assert.ok(next, 'unexpected transport request'); return jsonResponse(next.body, next.status);}
  });
  return {transport, calls, errors, states};
}

for (const flowId of ['Flow.v1', `A${'b'.repeat(99)}`]) test(`TRANSPORT identifiers accept Flow ${flowId}`, () => {
  const base = `/${config.namespace}/${flowId}`;
  assert.equal(parseBootstrap({...config, flowId, schemaUrl: `${base}/schemas/_home`, apiBase: `${base}/apis/`, assetBase: `${base}/assets/`}, origin).flowId, flowId);
});
for (const flowId of ['_flow', 'A'.repeat(101)]) test(`TRANSPORT identifiers reject Flow ${flowId}`, () => {
  const base = `/${config.namespace}/${flowId}`;
  assert.throws(() => parseBootstrap({...config, flowId, schemaUrl: `${base}/schemas/_home`, apiBase: `${base}/apis/`, assetBase: `${base}/assets/`}, origin));
});
for (const pageId of ['_home', `_${'a'.repeat(99)}`]) test(`TRANSPORT identifiers accept page ${pageId}`, () => assert.equal(parseBootstrap({...config, pageId, schemaUrl: `${prefix}/schemas/${pageId}`}, origin).pageId, pageId));
for (const pageId of ['home.json', '_'.repeat(101)]) test(`TRANSPORT identifiers reject page ${pageId}`, () => assert.throws(() => parseBootstrap({...config, pageId, schemaUrl: `${prefix}/schemas/${pageId}`}, origin)));

for (const apiId of ['greet', '_greet', `_${'a'.repeat(99)}`]) test(`TRANSPORT bare API resolves ${apiId}`, async () => {
  const evidence = harness([{status: 200, body: ready}]);
  assert.deepEqual((await evidence.transport.fetcher({url: apiId, method: 'post', data: {name: 'shared-schema'}})).data, ready);
  assert.equal(evidence.calls[0].url, `${origin}${config.apiBase}${apiId}`);
  assert.equal(evidence.calls[0].body, '{"name":"shared-schema"}');
});
for (const raw of ['greet.json', `${config.apiBase}greet.json`, '_'.repeat(101), `${config.apiBase}${'_'.repeat(101)}`, '../greet', './greet', 'apis/greet', '%67reet', '/api/v1/main/flows', 'https://evil.test/greet', '//evil.test/greet', '/company._team/other/apis/greet']) test(`TRANSPORT rejects API ${raw} without network writes`, async () => {
  const evidence = harness([]);
  await assert.rejects(evidence.transport.fetcher({url: raw, method: 'post', data: {}}));
  assert.equal(evidence.calls.length, 0);
});

const invalidResponses = [
  ['missing ID', {executionId: undefined}], ['empty ID', {executionId: ''}], ['numeric ID', {executionId: 7}],
  ['unsafe ID', {executionId: '../exec'}], ['oversized ID', {executionId: 'a'.repeat(151)}],
  ['missing state', {executionState: undefined}], ['unknown state', {executionState: 'FUTURE_SUCCESS'}],
  ['nonstring state', {executionState: {name: 'SUCCESS'}}], ['missing readiness', {resultReady: undefined}],
  ['string readiness', {resultReady: 'true'}], ['noninteger status', {status: .5}]
];
for (const [name, change] of invalidResponses) {
  test(`TRANSPORT initial ASYNC 202 rejects ${name}`, async () => {
    const evidence = harness([{status: 202, body: {...pending, ...change}}]);
    await assert.rejects(evidence.transport.fetcher({url: `${config.apiBase}greet`, method: 'post', data: {}}));
    assert.ok(evidence.errors.length); assert.equal(evidence.calls.length, 1); assert.ok(!evidence.states.includes('success'));
  });
  test(`TRANSPORT SYNC 200 rejects ${name}`, async () => {
    const evidence = harness([{status: 200, body: {...ready, ...change}}]);
    await assert.rejects(evidence.transport.fetcher({url: `${config.apiBase}greet`, method: 'post', data: {}}));
    assert.ok(evidence.errors.length); assert.ok(!evidence.states.includes('success'));
  });
  test(`TRANSPORT polling rejects ${name}`, async () => {
    const evidence = harness([{status: 202, body: pending}, {status: 200, body: {...ready, ...change}}]);
    await assert.rejects(evidence.transport.fetcher({url: `${config.apiBase}greet`, method: 'post', data: {}}));
    assert.ok(evidence.errors.length); assert.ok(!evidence.states.includes('success'));
  });
}
for (const executionState of ['CREATED', 'SUBMITTED', 'RUNNING', 'PAUSED', 'RESTARTED', 'KILLING', 'QUEUED', 'RETRYING', 'BREAKPOINT']) test(`TRANSPORT rejects ${executionState} with resultReady true`, async () => {
  const evidence = harness([{status: 200, body: {...ready, executionState}}]);
  await assert.rejects(evidence.transport.fetcher({url: `${config.apiBase}greet`, method: 'post', data: {}}));
  assert.ok(!evidence.states.includes('success'));
});
for (const executionState of ['CREATED', 'SUBMITTED', 'RUNNING', 'PAUSED', 'RESTARTED', 'KILLING', 'QUEUED', 'RETRYING', 'BREAKPOINT']) test(`TRANSPORT polls supported waiting state ${executionState}`, async () => {
  const evidence = harness([{status: 202, body: {...pending, executionState}}, {status: 200, body: ready}]);
  assert.deepEqual((await evidence.transport.fetcher({url: `${config.apiBase}greet`, method: 'post', data: {}})).data, ready);
  assert.equal(evidence.calls.length, 2);
});
for (const executionState of ['SUCCESS', 'WARNING', 'RETRIED', 'SKIPPED', 'RESUBMITTED']) test(`TRANSPORT accepts readable terminal ${executionState}`, async () => {
  const evidence = harness([{status: 200, body: {...ready, executionState}}]);
  assert.equal((await evidence.transport.fetcher({url: `${config.apiBase}greet`, method: 'post', data: {}})).data.executionState, executionState);
});
for (const executionState of ['FAILED', 'KILLED', 'CANCELLED']) test(`TRANSPORT rejects failed terminal ${executionState} even status zero`, async () => {
  const evidence = harness([{status: 200, body: {...ready, executionState}}]);
  await assert.rejects(evidence.transport.fetcher({url: `${config.apiBase}greet`, method: 'post', data: {}}), /Execution failed/);
});
test('TRANSPORT polling ignores later URL changes and waits for readable terminal outputs', async () => {
  const evidence = harness([{status: 202, body: pending}, {status: 202, body: {...pending, executionState: 'SUCCESS', executionUrl: 'https://evil.test/poll'}}, {status: 200, body: {...ready, executionUrl: '/api/v1/main/executions/other'}}]);
  assert.deepEqual((await evidence.transport.fetcher({url: `${config.apiBase}greet`, method: 'post', data: {}})).data.data, ready.data);
  assert.deepEqual(evidence.calls.slice(1).map(call => call.url), [origin + pending.executionUrl, origin + pending.executionUrl]);
});
test('TRANSPORT polling rejects a different syntactically valid execution ID', async () => {
  const evidence = harness([{status: 202, body: pending}, {status: 200, body: {...ready, executionId: 'other-exec'}}]);
  await assert.rejects(evidence.transport.fetcher({url: `${config.apiBase}greet`, method: 'post', data: {}}), /Execution identity changed/);
  assert.ok(!evidence.states.includes('success'));
});
for (const [name, status, body] of [['ready with 202', 202, {...ready, executionUrl: pending.executionUrl}], ['not ready with 200', 200, pending]]) test(`TRANSPORT rejects readiness HTTP combination ${name}`, async () => {
  const evidence = harness([{status, body}]);
  await assert.rejects(evidence.transport.fetcher({url: `${config.apiBase}greet`, method: 'post', data: {}}));
  assert.ok(!evidence.states.includes('success'));
});
