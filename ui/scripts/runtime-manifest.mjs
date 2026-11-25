import {createHash} from 'node:crypto';
import {readFile, readdir, writeFile} from 'node:fs/promises';
import {extname} from 'node:path';

const root = new URL('../', import.meta.url);
const dist = new URL('dist/', root);
const contentTypes = {
  '.css': 'text/css; charset=utf-8',
  '.eot': 'font/vnd.ms-fontobject',
  '.gif': 'image/gif',
  '.html': 'text/html; charset=utf-8',
  '.jpeg': 'image/jpeg',
  '.jpg': 'image/jpeg',
  '.js': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.map': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
  '.ttf': 'font/ttf',
  '.txt': 'text/plain; charset=utf-8',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2'
};
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const assets = {};

async function collect(directory, prefix = '') {
  for (const entry of (await readdir(directory, {withFileTypes: true})).sort((left, right) => left.name.localeCompare(right.name))) {
    const relative = `${prefix}${entry.name}`;
    if (relative === 'runtime-manifest.json') continue;
    const path = new URL(relative, dist);
    if (entry.isDirectory()) await collect(path, `${relative}/`);
    else {
      const type = contentTypes[extname(entry.name).toLowerCase()];
      if (!type) throw new Error(`Unsupported runtime asset extension: ${relative}`);
      const bytes = await readFile(path);
      const source = bytes.toString('utf8');
      const extension = extname(entry.name).toLowerCase();
      const externalReference = extension === '.js' ? /(?:fetch|XMLHttpRequest|new\s+URL)\(\s*["']https?:\/\//.test(source) : /(?:https?:\/\/|url\(\s*\/\/)[^\s'"\)]+/.test(source);
      if (externalReference && ['.css', '.html', '.js'].includes(extension)) {
        throw new Error(`External URL found in runtime asset: ${relative}`);
      }
      assets[relative] = {sha256: hash(bytes), contentType: type};
    }
  }
}

await collect(dist);
if (!assets['index.html']) throw new Error('Runtime entry index.html is missing');
const manifest = {version: 1, entry: 'index.html', assets};
await writeFile(new URL('runtime-manifest.json', dist), `${JSON.stringify(manifest, null, 2)}\n`);
console.log(JSON.stringify({version: manifest.version, entry: manifest.entry, assetCount: Object.keys(assets).length}));
