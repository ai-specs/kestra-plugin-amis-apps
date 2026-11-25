import {createHash} from 'node:crypto';
import {readFile, writeFile} from 'node:fs/promises';
import {readdir} from 'node:fs/promises';
const root = new URL('../', import.meta.url);
const digest = bytes => createHash('sha256').update(bytes).digest('hex');
const dist = new URL('dist/', root);
const entries = {};
async function visit(directory, prefix = '') {
  for (const entry of (await readdir(directory, {withFileTypes: true})).sort((left, right) => left.name.localeCompare(right.name))) {
    const name = `${prefix}${entry.name}`;
    if (entry.isDirectory()) await visit(new URL(`${entry.name}/`, directory), `${name}/`);
    else entries[name] = {sha256: digest(await readFile(new URL(name, dist))), bytes: (await readFile(new URL(name, dist))).byteLength};
  }
}
await visit(dist);
const html = await readFile(new URL('index.html', dist), 'utf8');
const placeholders = ['__AMIS_BASE__', '__AMIS_CSRF__', '__AMIS_BOOTSTRAP__'];
for (const placeholder of placeholders) if (!html.includes(placeholder)) throw new Error(`Missing template placeholder ${placeholder}`);
const manifest = {
  manifestVersion: 1,
  scope: 'Classpath PageDocument consumer input; not production authorization evidence',
  template: {classpath: 'META-INF/kestra/amis-apps/runtime/index.html', builtPath: 'dist/index.html', contentType: 'text/html; charset=utf-8', placeholders, replacementRules: {__AMIS_BASE__: 'same-origin Flow prefix ending in /', __AMIS_CSRF__: 'escaped current-session CSRF meta value', __AMIS_BOOTSTRAP__: 'escaped JSON bootstrap V1 object'}},
  assets: {classpathRoot: 'META-INF/kestra/amis-apps/runtime/', routeSuffix: '/assets/{assetPath}', cache: 'no-store; assets do not provide schema/API/result authorization', allowlist: Object.fromEntries(Object.entries(entries).filter(([name]) => name.startsWith('assets/')))},
  forbidden: ['unlisted asset paths', 'external CDN URLs', 'asset paths containing .., backslash, percent encoding or query/fragment', 'using this manifest as an authorization decision'],
  artifact: {indexSha256: entries['index.html']?.sha256, assetCount: Object.keys(entries).filter(name => name.startsWith('assets/')).length}
};
await writeFile(new URL('reports/page-document-manifest.json', root), JSON.stringify(manifest, null, 2));
console.log(JSON.stringify({manifestVersion: manifest.manifestVersion, assetCount: manifest.artifact.assetCount, indexSha256: manifest.artifact.indexSha256}));
