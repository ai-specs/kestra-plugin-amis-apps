import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync, mkdirSync, copyFileSync, writeFileSync, readFileSync, existsSync, rmSync} from 'node:fs';
import {join} from 'node:path';
import {tmpdir} from 'node:os';
import {spawnSync} from 'node:child_process';

function fixture(helper) {
  const root = mkdtempSync(join(tmpdir(), 'amis-translations-test-'));
  for (const path of ['scripts', '.host-sdk', 'src/locales']) mkdirSync(join(root, path), {recursive: true});
  copyFileSync(new URL('../scripts/translations.mjs', import.meta.url), join(root, 'scripts/translations.mjs'));
  if (helper !== undefined) writeFileSync(join(root, '.host-sdk/check-translations.mjs'), helper);
  return {root, report: join(root, 'reports/translations.json'), run: () => spawnSync(process.execPath,
    [join(root, 'scripts/translations.mjs')], {encoding: 'utf8'})};
}

test('fresh translation entry creates the machine report from the checker stdout', () => {
  const files = fixture('console.log(JSON.stringify({passed:true,reports:[{locale:"en",errors:[]}]}));');
  try {
    assert(!existsSync(files.report));
    assert.equal(files.run().status, 0);
    assert.deepEqual(JSON.parse(readFileSync(files.report, 'utf8')), {passed: true, reports: [{locale: 'en', errors: []}]});
  } finally {rmSync(files.root, {recursive: true, force: true});}
});

test('failed, missing or malformed translation checker cannot retain stale passing evidence', () => {
  for (const helper of [undefined, 'console.log("not-json");',
    'console.log(JSON.stringify({passed:false,reports:[{locale:"en",errors:["missing"]}]}));process.exitCode=1;']) {
    const files = fixture(helper);
    try {
      mkdirSync(join(files.root, 'reports'));
      writeFileSync(files.report, '{"passed":true,"stale":true}');
      assert.equal(files.run().status, 1);
      assert(!existsSync(files.report) || !readFileSync(files.report, 'utf8').includes('stale'));
    } finally {rmSync(files.root, {recursive: true, force: true});}
  }
});
