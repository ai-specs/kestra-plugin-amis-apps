import {createHash} from 'node:crypto';
import {readdir, readFile, writeFile, mkdir} from 'node:fs/promises';
import os from 'node:os';
const root = new URL('../', import.meta.url);
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
async function inventory(directory, prefix = '') {
  const entries = {};
  for (const item of (await readdir(new URL(directory, root), {withFileTypes: true})).sort((first, second) => first.name.localeCompare(second.name))) {
    const path = `${prefix}${item.name}`;
    if (item.isDirectory()) Object.assign(entries, await inventory(`${directory}${item.name}/`, `${path}/`));
    else entries[path] = hash(await readFile(new URL(`${directory}${item.name}`, root)));
  }
  return entries;
}
const source = {};
for (const directory of ['src/', 'scripts/', 'tests/']) Object.assign(source, Object.fromEntries(Object.entries(await inventory(directory)).map(([name, digest]) => [`${directory}${name}`, digest])));
for (const name of ['package.json', 'package-lock.json', 'index.html', 'vite.config.mjs', 'playwright.config.mjs', 'playwright.preferences.config.mjs', 'README.md', '.npmrc', '.gitignore']) source[name] = hash(await readFile(new URL(name, root)));
const assets = await inventory('dist/');
for (const name of Object.keys(assets)) {
  if (/monaco|tinymce|froala|codemirror|amis-editor|editor\./i.test(name)) throw new Error(`Editor asset outside P0: ${name}`);
  if (name.endsWith('.css') && /url\(\s*['"]?https?:/i.test(await readFile(new URL(`dist/${name}`, root), 'utf8'))) throw new Error(`External CSS dependency: ${name}`);
}
const runtimeManifest = JSON.parse(await readFile(new URL('dist/runtime-manifest.json', root)));
if (Object.hasOwn(runtimeManifest.assets, 'runtime-manifest.json')) throw new Error('Runtime manifest must not hash itself');
for (const [name, asset] of Object.entries(runtimeManifest.assets)) {
  if (assets[name] !== asset.sha256) throw new Error(`Runtime manifest hash mismatch: ${name}`);
}
const html = await readFile(new URL('dist/index.html', root), 'utf8');
for (const placeholder of ['__AMIS_BASE__', '__AMIS_CSRF__', '__AMIS_BOOTSTRAP__']) if (!html.includes(placeholder)) throw new Error(`Missing ${placeholder}`);
if (/(?:src|href)="(?:https?:|\/assets)/.test(html)) throw new Error('Non-relative build entry');
await mkdir(new URL('reports/', root), {recursive: true});
await writeFile(new URL('reports/inputs.json', root), JSON.stringify({id: 'AMIS-UI-20261125', scope: 'Independent UI test transport; NOT production authentication, RBAC or persistence', environment: {platform: process.platform, arch: process.arch, os: os.release(), node: process.version, npm: process.env.npm_config_user_agent, cwd: process.cwd()}, source, sourceSha256: hash(JSON.stringify(source)), assets, host: JSON.parse(await readFile(new URL('.host-sdk/provenance.json', root)))}, null, 2));
