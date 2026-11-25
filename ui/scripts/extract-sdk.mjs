import {createHash} from 'node:crypto';
import {readFile, mkdir, writeFile} from 'node:fs/promises';
import {execFileSync} from 'node:child_process';

const root = new URL('../', import.meta.url);
const jar = new URL('../../.integration/host-webserver.jar', import.meta.url);
const provenance = JSON.parse(await readFile(new URL('../../.integration/host-provenance.json', import.meta.url)));
const properties = await readFile(new URL('../../host-contract.properties', import.meta.url), 'utf8');
const imageLines = properties.split(/\r?\n/).filter(line => /^overlay\.image\s*=/.test(line));
if (imageLines.length !== 1) throw new Error('Expected exactly one overlay.image in host-contract.properties');
const image = imageLines[0].slice(imageLines[0].indexOf('=') + 1).trim();
const hash = createHash('sha256').update(await readFile(jar)).digest('hex');
if (hash !== provenance.sha256 || !/@sha256:[a-f0-9]{64}$/.test(image) || provenance.image !== image) {
  throw new Error('Published host SDK input mismatch');
}
await mkdir(new URL('.host-sdk/', root), {recursive: true});
const files = {};
for (const name of ['contract.json', 'locales.mjs', 'README.md', 'check-translations.mjs']) {
  const bytes = execFileSync('unzip', ['-p', jar.pathname, `META-INF/kestra/ui-sdk/v1/${name}`]);
  await writeFile(new URL(`.host-sdk/${name}`, root), bytes);
  files[name] = createHash('sha256').update(bytes).digest('hex');
}
await writeFile(new URL('.host-sdk/provenance.json', root), JSON.stringify({input: provenance, hostContractSha256: createHash('sha256').update(properties).digest('hex'), extractedFromSha256: hash, files}, null, 2));
