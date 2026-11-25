import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {readFile, writeFile} from 'node:fs/promises';

const root = new URL('../', import.meta.url);
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const raw = await readFile(new URL('reports/chromium.json', root));
const report = JSON.parse(raw);
const inputs = JSON.parse(await readFile(new URL('reports/inputs.json', root)));
const preferencesRaw = await readFile(new URL('reports/preferences-red.json', root));
const preferences = JSON.parse(preferencesRaw);
const translations = JSON.parse(await readFile(new URL('reports/translations.json', root)));
const manifest = JSON.parse(await readFile(new URL('reports/page-document-manifest.json', root)));
const runtimeManifest = JSON.parse(await readFile(new URL('dist/runtime-manifest.json', root)));
const expected = [];
for (const locale of ['en', 'zh-CN']) for (const theme of ['light', 'dark', 'dark-2', 'syncWithSystem', 'narrow-focus']) expected.push(`TEST TRANSPORT matrix ${locale} ${theme}: real AMIS input submit result`);
expected.push('TEST TRANSPORT schema loading then real renderer');
for (const status of [401, 403, 404, 503]) {
  expected.push(`TEST TRANSPORT schema HTTP ${status}: visible error and exit`);
  expected.push(`TEST TRANSPORT API HTTP ${status}: no empty success`);
}
expected.push(...['202 polling waits through terminal state with unreadable outputs', 'failed execution is visible', 'external polling rejected before any external request', 'explicit cancellation stops polling', 'unload cancels polling', 'polling timeout has visible error', 'missing CSRF prevents API write'].map(name => `TEST TRANSPORT ${name}`));
expected.push('TEST TRANSPORT review bare greet uses bootstrap API base');
for (const name of ['missing execution ID', 'unknown execution state', 'running result claimed ready']) expected.push(`TEST TRANSPORT review SYNC rejects ${name}`);
expected.push('TEST TRANSPORT review polling rejects unknown state', 'TEST TRANSPORT review polling ignores later external URL');
for (const raw of ['../greet', '/api/v1/main/flows', 'https://evil.test/greet']) expected.push(`TEST TRANSPORT review API rejects ${raw} before network write`);
const cases = [];
async function visit(suites, target = cases) {
  for (const suite of suites) {
    for (const spec of suite.specs || []) {
      for (const test of spec.tests) {
        const attachments = [];
        for (const result of test.results) for (const attachment of result.attachments || []) {
          if (attachment.path) attachments.push({name: attachment.name, path: attachment.path, sha256: hash(await readFile(attachment.path))});
          else if (attachment.body) attachments.push({name: attachment.name, sha256: hash(Buffer.from(attachment.body, 'base64'))});
        }
        target.push({name: spec.title, status: test.status, results: test.results.map(result => result.status), attachments});
      }
    }
    await visit(suite.suites || [], target);
  }
}
await visit(report.suites);
const preferenceCases = [];
await visit(preferences.suites, preferenceCases);
const failures = [];
try {
  assert.deepEqual(cases.map(entry => entry.name).sort(), expected.sort());
  for (const entry of cases) {
    assert.equal(entry.status, 'expected', entry.name);
    assert.deepEqual(entry.results, ['passed'], entry.name);
    assert.ok(entry.attachments.some(item => item.name === 'screenshot'), entry.name);
  }
  assert.equal(report.stats.unexpected, 0); assert.equal(report.stats.flaky, 0); assert.equal(report.stats.skipped, 0);
  assert.equal(translations.passed, true); assert.equal(translations.reports.length, 13);
  assert.equal(manifest.manifestVersion, 1); assert.equal(manifest.template.placeholders.length, 3); assert.ok(manifest.artifact.assetCount > 0);
  assert.deepEqual(Object.keys(runtimeManifest).sort(), ['assets', 'entry', 'version']);
  assert.equal(runtimeManifest.version, 1); assert.equal(runtimeManifest.entry, 'index.html');
  assert.ok(!Object.hasOwn(runtimeManifest.assets, 'runtime-manifest.json'));
  for (const [name, asset] of Object.entries(runtimeManifest.assets)) {
    assert.match(name, /^(?:index\.html|assets\/[^.].*)$/); assert.match(asset.sha256, /^[a-f0-9]{64}$/); assert.match(asset.contentType, /(?:; charset=utf-8|image\/|font\/)/);
  }
  for (const entry of translations.reports) assert.deepEqual(entry.errors, [], entry.locale);
  assert.deepEqual(preferenceCases.map(entry => entry.name).sort(), ['FORMAL preference regression: selected locale survives refresh', 'FORMAL preference regression: selected theme survives refresh']);
  for (const entry of preferenceCases) assert.ok(entry.attachments.some(item => item.name === 'screenshot'), entry.name);
  for (const [name, expectedHash] of Object.entries(inputs.source)) assert.equal(hash(await readFile(new URL(name, root))), expectedHash, `Source changed after build: ${name}`);
  for (const [name, expectedHash] of Object.entries(inputs.assets)) assert.equal(hash(await readFile(new URL(`dist/${name}`, root))), expectedHash, `Asset changed: ${name}`);
  for (const [name, expectedHash] of Object.entries(inputs.host.files)) assert.equal(hash(await readFile(new URL(`.host-sdk/${name}`, root))), expectedHash, `SDK extraction changed: ${name}`);
  assert.equal(hash(await readFile(new URL('../host-contract.properties', root))), inputs.host.hostContractSha256, 'Host contract changed after build');
  assert.equal(hash(await readFile(new URL('../.integration/host-webserver.jar', root))), inputs.host.extractedFromSha256, 'Host jar changed after build');
} catch (error) {failures.push(error.message);}
const runtimePassed = failures.length === 0;
const preferencesPassed = preferences.stats.expected === 2 && preferences.stats.unexpected === 0 && preferences.stats.skipped === 0 && preferences.stats.flaky === 0;
const summary = {id: inputs.id, scope: inputs.scope, sourceSha256: inputs.sourceSha256, chromiumReportSha256: hash(raw), stats: report.stats, cases, translations: {passed: translations.passed, count: translations.reports.length}, preferences: {reportSha256: hash(preferencesRaw), stats: preferences.stats, cases: preferenceCases, status: preferencesPassed ? 'passed' : 'failed: formal preference browser regression; inspect its report'}, runtimePassed, preferencesPassed, acceptancePassed: runtimePassed && preferencesPassed, failures};
await writeFile(new URL('reports/summary.json', root), JSON.stringify(summary, null, 2));
if (failures.length) throw new Error(failures.join('\n'));
console.log(`${cases.length} Chromium cases passed; failed=0 skipped=0 flaky=0; source=${inputs.sourceSha256}`);
if (!preferencesPassed) {
  console.error(`Preferences NOT passed: failed=${preferences.stats.unexpected}, skipped=${preferences.stats.skipped}; overall acceptance remains RED`);
  process.exitCode = 1;
}
