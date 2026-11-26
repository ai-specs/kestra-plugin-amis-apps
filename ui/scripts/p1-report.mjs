import {createHash} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {readFile, writeFile, mkdir, copyFile, rm} from 'node:fs/promises';
import {resolve, relative, basename} from 'node:path';
import {fileURLToPath} from 'node:url';
export const root = fileURLToPath(new URL('../../', import.meta.url));
const reports = resolve(root, 'ui/reports');
export const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
export async function sourceEvidence() {
  const names = execFileSync('git', ['ls-files', '--cached', '--others', '--exclude-standard', '-z'], {cwd: root, encoding: 'utf8'}).split('\0').filter(Boolean).sort();
  const files = {};
  for (const name of names) files[name] = sha256(await readFile(resolve(root, name)));
  return {sourceCommit: execFileSync('git', ['rev-parse', 'HEAD'], {cwd: root, encoding: 'utf8'}).trim(), sourceSha256: sha256(JSON.stringify(files))};
}
export async function writeP1Report(scope, results, metadata = {}) {
  const kind = scope === 'test-transport' ? 'transport' : 'production';
  const manifestBytes = await readFile(resolve(root, 'contracts/p1-cases.json'));
  const expected = JSON.parse(manifestBytes)[kind];
  const extras = results.filter(result => !expected.includes(result.id));
  const cases = expected.map(id => {
    const found = results.filter(result => result.id === id);
    return found.length === 1 ? found[0] : {id, status: found.length ? 'failed' : 'not-run', evidence: [], ...(found.length ? {error: 'Duplicate case ID'} : {})};
  });
  const stats = {expected: expected.length, passed: 0, failed: 0, skipped: 0, notRun: 0};
  for (const item of cases) stats[item.status === 'not-run' ? 'notRun' : item.status]++;
  const host = JSON.parse(await readFile(resolve(root, '.integration/host-provenance.json')));
  const report = {schemaVersion: 1, scope, manifestSha256: sha256(manifestBytes), ...await sourceEvidence(), hostImage: host.image,
    hostExecutableSha256: sha256(await readFile(resolve(root, '.integration/host-webserver.jar'))), jarSha256: metadata.jarSha256 ?? null,
    cases, stats, passed: stats.passed === expected.length && extras.length === 0, ...(extras.length ? {errors: ['Unexpected IDs: ' + extras.map(value => value.id).join(', ')]} : {}), ...metadata};
  await mkdir(reports, {recursive: true});
  await writeFile(resolve(reports, `p1-${kind}.json`), JSON.stringify(report, null, 2) + '\n');
  return report;
}
export default class P1Reporter {
  results = [];
  async onBegin() {await rm(resolve(reports,'p1-evidence'),{recursive:true,force:true});}
  async onTestEnd(test, result) {
    const evidence = [];
    for (const [index, attachment] of result.attachments.entries()) {
      if (!attachment.path && !attachment.body) continue;
      if (attachment.name === 'trace') continue; // Raw network traces remain local; publish only sanitized observations and screenshots.
      const name = `${this.results.length}-${index}-${basename(attachment.path || attachment.name).replace(/[^\w.-]/g, '_')}`;
      const path = resolve(reports, 'p1-evidence', name); await mkdir(resolve(reports, 'p1-evidence'), {recursive: true});
      if (attachment.path) await copyFile(attachment.path, path); else await writeFile(path, attachment.body);
      evidence.push({path: relative(reports, path), sha256: sha256(await readFile(path))});
    }
    this.results.push({id: test.title, status: result.status === 'passed' ? 'passed' : result.status === 'skipped' ? 'skipped' : 'failed', evidence,
      ...(result.error ? {error: result.error.message} : {})});
  }
  async onEnd() {await writeP1Report('test-transport', this.results);}
}
