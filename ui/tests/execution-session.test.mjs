import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createExecutionSession} from '../src/execution-session.mjs';
const origin = 'https://test.invalid';
const prefix = '/company.team/app';
const config = {namespace: 'company.team', flowId: 'app', pageId: 'home', apiBase: `${prefix}/apis/`, limits: {requestTimeoutMs: 1000, pollIntervalMs: 1, pollTimeoutMs: 1000}};
const ready = {status: 0, resultReady: true, executionId: 'id-1', executionState: 'SUCCESS', data: {message: 'saved'}};
const pending = {...ready, resultReady: false, executionState: 'PAUSED', data: {}, executionUrl: `${prefix}/apis/save/executions/id-1`};
const request = {url: 'save', method: 'post', data: {name: 'changed'}};
const response = (body, status = 200) => new Response(JSON.stringify(body), {status});
function storage() {
  const map = new Map();
  return {getItem: key => map.get(key) ?? null, setItem: (key, value) => map.set(key, value), removeItem: key => map.delete(key), map};
}
function options(fetchImpl, extra = {}) {return {origin, csrf: 'test-csrf', signal: new AbortController().signal, fetchImpl, ...extra};}

test('P1 session simultaneous same submit has one POST and shared result', async () => {
  let calls = 0;
  const session = createExecutionSession(config, options(async () => {calls++; return response(ready);}), storage());
  const one = session.fetcher(request); const two = session.fetcher(request);
  assert.equal(one, two); assert.deepEqual((await one).data, ready); await two; assert.equal(calls, 1);
});
test('P1 session rejects a concurrent different input without a second POST', async () => {
  let release;
  const session = createExecutionSession(config, options(() => new Promise(resolve => {release = resolve;})), storage());
  const first = session.fetcher(request); await new Promise(setImmediate);
  await assert.rejects(session.fetcher({...request, data: {name: 'different'}}), /already active/);
  release(response(ready)); await first;
});
test('P1 confirmed pending survives cancellation and refresh; resume sends GET only', async () => {
  const store = storage(); const controller = new AbortController();
  const first = createExecutionSession(config, options(async () => {controller.abort(); return response(pending, 202);}, {signal: controller.signal}), store);
  await assert.rejects(first.fetcher(request));
  const methods = [];
  const next = createExecutionSession(config, options(async (url, init) => {methods.push(init.method); assert.equal(url, origin + pending.executionUrl); return response(ready);}), store);
  await assert.rejects(next.fetcher(request), /Resume the existing/);
  assert.deepEqual((await next.resume()).data, ready); assert.deepEqual(methods, ['GET']); assert.equal(store.map.size, 0);
});
test('P1 lost POST acknowledgement records uncertainty and never replays on refresh', async () => {
  const store = storage(); let calls = 0;
  const first = createExecutionSession(config, options(async () => {calls++; throw new TypeError('Network failure');}), store);
  await assert.rejects(first.fetcher(request), /Network failure/);
  const next = createExecutionSession(config, options(async () => {calls++; return response(ready);}), store);
  await assert.rejects(next.fetcher(request), /outcome is unknown/); await assert.rejects(next.resume(), /No confirmed/);
  assert.equal(calls, 1); assert.ok(!JSON.stringify([...store.map.values()]).includes('changed'));
});
test('P1 pause remains active, rejects parallel input and emits paused before success', async () => {
  const states = []; let calls = 0; let release; let paused;
  const observed = new Promise(resolve => {paused = resolve;});
  const session = createExecutionSession(config, options(async () => {
    if (++calls === 1) return response(pending, 202);
    return new Promise(resolve => {release = () => resolve(response(ready));});
  }, {onState: value => {states.push(value); if (value === 'paused') paused();}}), storage());
  const first = session.fetcher(request); await observed;
  await assert.rejects(session.fetcher({...request, data: {name: 'other'}}), /already active/);
  while (!release) await new Promise(setImmediate);
  release(); await first; assert.deepEqual(states, ['waiting', 'paused', 'success']); assert.equal(calls, 2);
});

for (const record of [{version: 2, phase: 'submitting', apiUrl: `${prefix}/apis/save`}, {version: 1, phase: 'pending', apiUrl: `${prefix}/apis/save`, executionId: 'id-1', executionUrl: '/api/v1/main/executions/id-1'}, {version: 1, phase: 'submitting', apiUrl: '/company.other/app/apis/save'}, {version: 1, phase: 'submitting', apiUrl: `${prefix}/apis/save`, token: 'forbidden'}]) {
  test(`P1 recovery rejects untrusted record ${JSON.stringify(record)}`, () => {
    const store = storage(); store.setItem(`amis.execution.v1:company.team/app/home`, JSON.stringify(record));
    assert.throws(() => createExecutionSession(config, options(async () => response(ready)), store));
  });
}
test('P1 recovery first GET must keep stored execution identity', async () => {
  const store = storage(); store.setItem(`amis.execution.v1:company.team/app/home`, JSON.stringify({version: 1, phase: 'pending', apiUrl: `${prefix}/apis/save`, executionId: 'id-1', executionUrl: pending.executionUrl}));
  const session = createExecutionSession(config, options(async () => response({...ready, executionId: 'other'})), store);
  await assert.rejects(session.resume(), /identity changed/); assert.equal(store.map.size, 1);
});
for (const status of [401, 403]) {
  test(`P1 confirmed execution survives GET ${status} and reauthorization queries original ID without POST`, async () => {
    const store = storage(); const methods = [];
    const session = createExecutionSession(config, options(async (_url, init) => {
      methods.push(init.method);
      return init.method === 'POST' ? response(pending, 202) : response({}, status);
    }), store);
    await assert.rejects(session.fetcher(request), new RegExp(`HTTP ${status}`));
    assert.equal(JSON.parse([...store.map.values()][0]).phase, 'pending');
    await assert.rejects(session.fetcher(request), /Resume the existing/);
    const authorized = createExecutionSession(config, options(async (url, init) => {
      assert.equal(url, origin + pending.executionUrl); methods.push(init.method); return response(ready);
    }), store);
    assert.deepEqual((await authorized.resume()).data, ready);
    assert.deepEqual(methods, ['POST', 'GET', 'GET']);
  });
}
