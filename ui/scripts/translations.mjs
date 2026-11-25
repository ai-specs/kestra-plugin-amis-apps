import {spawnSync} from 'node:child_process';
import {mkdirSync, rmSync, writeFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';

const root = new URL('../', import.meta.url);
const report = new URL('reports/translations.json', root);
mkdirSync(new URL('reports/', root), {recursive: true});
rmSync(report, {force: true});
const result = spawnSync(process.execPath,
  [fileURLToPath(new URL('.host-sdk/check-translations.mjs', root)), fileURLToPath(new URL('src/locales/', root))],
  {cwd: fileURLToPath(root), encoding: 'utf8', maxBuffer: 10 * 1024 * 1024});
if (result.stdout) {
  writeFileSync(report, result.stdout);
  process.stdout.write(result.stdout);
}
if (result.stderr) process.stderr.write(result.stderr);
if (result.error) process.stderr.write(`${result.error.message}\n`);
let valid = false;
if (result.status === 0) {
  try {
    const evidence = JSON.parse(result.stdout);
    valid = evidence.passed === true && Array.isArray(evidence.reports) && evidence.reports.length > 0;
  } catch {
    process.stderr.write('Published translation checker did not return valid JSON evidence\n');
  }
}
process.exitCode = result.status === 0 && valid ? 0 : 1;
