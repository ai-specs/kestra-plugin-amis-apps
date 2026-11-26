import {test} from 'node:test';
import assert from 'node:assert/strict';
import {resolve} from 'node:path';
import {checkedOptions, composeConfiguration} from '../../scripts/run-p1-production.mjs';
const directory = resolve('../.work/p1-unit-owned');
const valid = {schemaVersion: 1, startIsolatedStack: true, directory, jar: resolve('../build/libs/candidate.jar'), jarSha256: 'a'.repeat(64), port: 18671};
test('P1 production runner requires explicit exclusive Docker allocation', () => {
  assert.equal(checkedOptions(valid), valid);
  for (const change of [{startIsolatedStack: false}, {startIsolatedStack: undefined}, {directory: '/tmp/not-owned'}, {port: 80}, {jarSha256: 'tag'}]) assert.throws(() => checkedOptions({...valid, ...change}));
});
test('P1 production runner has exactly one PG and one formal host with isolated state', () => {
  const compose = composeConfiguration({...valid, hostImage: 'formal-digest'}, {database:'test-db',client:'test-client',password:'test-password'});
  assert.deepEqual(Object.keys(compose.services).sort(), ['kestra','postgres']);
  assert.equal(compose.services.kestra.image, 'formal-digest');
  assert.equal(compose.services.postgres.ports, undefined);
  assert.equal(compose.services.kestra.volumes.length, 2);
  assert.deepEqual(compose.services.kestra.ports, ['127.0.0.1:18671:18671']);
  assert.equal(compose.services.kestra.labels.owner, 'amis-p1-test');
  assert.equal(compose.services.postgres.labels.owner, 'amis-p1-test');
  assert.equal(compose.networks.default.labels.owner, 'amis-p1-test');
  assert.equal(compose.volumes.storage.labels.owner, 'amis-p1-test');
  const config = JSON.parse(compose.services.kestra.environment.KESTRA_CONFIGURATION);
  assert.equal(config.micronaut.server.port, valid.port);
  assert.equal(config.kestra.rbac.enabled, false); assert.equal(config.kestra['amis-apps'].enabled, true);
  assert.equal(config.micronaut.security['intercept-url-map'].at(-1).access[0], 'isAuthenticated()');
  assert.ok(!config.micronaut.security['intercept-url-map'].some(rule => rule.pattern === '/api/**'));
  assert.equal(config.micronaut.security.oauth2.clients.kestra.openid.issuer, `http://localhost:${valid.port}`);
});
test('P1 production runner rejects path traversal inside a nominal work prefix', () => {
  assert.throws(() => checkedOptions({...valid, directory: directory + '/../../../../outside'}));
});
