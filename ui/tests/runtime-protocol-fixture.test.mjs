import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {createTransport, parseBootstrap} from '../src/runtime.mjs';

const bytes = await readFile(new URL('../../contracts/runtime-v1-cases.json', import.meta.url));
const protocol = JSON.parse(bytes);
assert.equal(protocol.version, 1);
assert.equal(protocol.cases.length, 13);
assert.equal(new Set(protocol.cases.map(entry => entry.id)).size, 13);
assert.match(protocol.scope, /not production acceptance evidence/);
console.log(`TRANSPORT shared protocol SHA-256 ${createHash('sha256').update(bytes).digest('hex')}; ${protocol.scope}`);

const origin = 'https://protocol-transport.test';
const binding = protocol.binding;
const prefix = `/${binding.namespace}/${binding.flowId}`;
const apiUrl = `${origin}${prefix}/apis/${binding.apiId}`;
const executionUrl = `${prefix}/apis/${binding.apiId}/executions/${binding.executionId}`;
const bootstrap = parseBootstrap({
  version: 1, namespace: binding.namespace, flowId: binding.flowId, pageId: binding.apiId,
  schemaUrl: `${prefix}/schemas/${binding.apiId}`, apiBase: `${prefix}/apis/`, assetBase: `${prefix}/assets/`,
  limits: {requestTimeoutMs: 1000, pollIntervalMs: 1, pollTimeoutMs: 2000}
}, origin);

for (const entry of protocol.cases) {
  test(`TRANSPORT shared runtime-v1 ${entry.id}`, async () => {
    const calls = []; const visibleErrors = []; const states = [];
    const controller = new AbortController();
    const pollStarted = Promise.withResolvers();
    const releasePoll = Promise.withResolvers();
    const envelope = {
      status: entry.factory === 'failed' ? 1 : 0,
      msg: entry.factory === 'failed' ? 'Execution failed' : '',
      data: entry.factory === 'pending' ? {} : entry.selectedOutput,
      executionId: binding.executionId, executionState: entry.executionState,
      resultReady: entry.factory !== 'pending', executionUrl
    };
    const completed = {...envelope, status: 0, executionState: 'SUCCESS', resultReady: true, data: {output: binding.resultOutput, value: 'transport-poll-result'}};
    const transport = createTransport(bootstrap, {
      origin, csrf: 'test-transport-only-csrf', signal: controller.signal,
      onError: message => visibleErrors.push(message), onState: state => states.push(state),
      fetchImpl: async (url, options) => {
        calls.push({url, ...options});
        if (calls.length === 1) {
          assert.equal(url, apiUrl); assert.equal(options.method, 'POST');
          assert.equal(options.headers['X-CSRF-TOKEN'], 'test-transport-only-csrf');
          assert.deepEqual(JSON.parse(options.body), {name: 'Ada'});
          return new Response(JSON.stringify(envelope), {status: entry.httpStatus});
        }
        assert.equal(entry.factory, 'pending'); assert.equal(calls.length, 2);
        assert.equal(url, origin + executionUrl); assert.equal(options.method, 'GET');
        assert.equal(options.credentials, 'same-origin'); assert.equal(options.redirect, 'error');
        assert.equal(options.body, undefined);
        pollStarted.resolve();
        await releasePoll.promise;
        return new Response(JSON.stringify(completed), {status: 200});
      }
    });
    let settled = false;
    const result = transport.fetcher({url: binding.apiId, method: 'post', data: {name: 'Ada'}});
    result.then(() => {settled = true;}, () => {settled = true; pollStarted.resolve();});
    try {
      if (entry.reject || entry.factory === 'failed') {
        await assert.rejects(result);
        assert.equal(calls.length, 1); assert.equal(states.includes('success'), false);
        assert.equal(states.at(-1), 'error'); assert.ok(visibleErrors[0]?.trim());
        if (entry.factory === 'failed') {
          for (const [key, expected] of Object.entries(entry.expect)) assert.deepEqual(envelope[key], expected);
          assert.match(visibleErrors[0], new RegExp(entry.executionState));
        }
        return;
      }
      for (const [key, expected] of Object.entries(entry.expect)) assert.deepEqual(envelope[key], expected);
      if (entry.factory === 'pending') {
        await pollStarted.promise;
        assert.equal(settled, false, 'POST 202 and pending poll must not settle as success');
        assert.deepEqual(states, ['waiting']); assert.deepEqual(visibleErrors, []);
        releasePoll.resolve();
        assert.deepEqual((await result).data, completed);
        assert.equal(calls.length, 2);
      } else {
        assert.equal(entry.factory, 'ready');
        assert.deepEqual((await result).data, envelope);
        assert.equal(calls.length, 1);
      }
      assert.deepEqual(states, ['waiting', 'success']); assert.deepEqual(visibleErrors, []);
    } finally {
      releasePoll.resolve(); controller.abort();
    }
  });
}
