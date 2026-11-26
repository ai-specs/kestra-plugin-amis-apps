import assert from 'node:assert/strict';
import {readFile, writeFile, mkdir, copyFile, chmod} from 'node:fs/promises';
import {randomBytes} from 'node:crypto';
import {execFileSync, spawn} from 'node:child_process';
import {resolve, isAbsolute} from 'node:path';
import {root, sha256} from '../ui/scripts/p1-report.mjs';

// No implicit infrastructure startup: the coordinator passes an explicit options file.
export function checkedOptions(value) {
  assert.equal(value.schemaVersion, 1);
  assert.equal(value.startIsolatedStack, true, 'The coordinator must explicitly allocate an exclusive Docker window');
  assert.ok(isAbsolute(value.directory) && value.directory === resolve(value.directory) && resolve(value.directory).startsWith(resolve(root, '.work') + '/'));
  assert.ok(isAbsolute(value.jar)); assert.match(value.jarSha256, /^[a-f0-9]{64}$/);
  assert.ok(Number.isInteger(value.port) && value.port > 1024 && value.port < 65536);
  return value;
}
export function composeConfiguration(options, secrets) {
  const {port} = options; const url = `http://localhost:${port}`;
  const configuration = {
    datasources: {postgres: {url: 'jdbc:postgresql://postgres:5432/amis_p1', driverClassName: 'org.postgresql.Driver', username: 'amis_p1', password: secrets.database}},
    kestra: {repository: {type: 'postgres'}, queue: {type: 'postgres'}, storage: {type: 'local', local: {'base-path': '/app/storage'}},
      url: `${url}/`, plugins: {'auto-install': {enabled: false}}, 'amis-apps': {enabled: true, 'poll-timeout': 'PT5S'}, rbac: {enabled: false},
      'anonymous-usage-report': {enabled: false}, 'ui-anonymous-usage-report': {enabled: false}, oidc: {enabled: true, issuer: url}},
    micronaut: {server: {port}, security: {enabled: true, authentication: 'idtoken', filter: {enabled: true},
      redirect: {'prior-to-login': true, 'login-success': '/ui/', 'login-failure': '/oidc/login?error=1', unauthorized: {url: '/oauth/login/kestra'}},
      'intercept-url-map': [...['/oidc/**', '/.well-known/**', '/oauth/**', '/ping', '/ui/assets/**', '/ui/manifest.webmanifest', '/favicon.ico', '/api/v1/configs/login'].map(pattern => ({pattern, access: ['isAnonymous()']})), {pattern: '/**', access: ['isAuthenticated()']}],
      oauth2: {clients: {kestra: {'client-id': 'kestra-ui', 'client-secret': secrets.client, scopes: ['openid', 'profile', 'email'], openid: {issuer: url, token: {url: `${url}/oidc/token`}, 'jwks-uri': `${url}/oidc/jwks`, 'end-session': {enabled: false}}}}}}}
  };
  const labels = {owner: options.owner || 'amis-p1-test'};
  return {networks: {default: {labels}}, volumes: {storage: {labels}, postgresData: {labels}}, services: {
    postgres: {image: 'postgres:18', labels, volumes: ['postgresData:/var/lib/postgresql'], environment: {POSTGRES_DB: 'amis_p1', POSTGRES_USER: 'amis_p1', POSTGRES_PASSWORD: secrets.database}, healthcheck: {test: ['CMD-SHELL', 'pg_isready -U amis_p1 -d amis_p1'], interval: '2s', retries: 45}},
    kestra: {image: options.hostImage, labels, command: ['server', 'standalone'], depends_on: {postgres: {condition: 'service_healthy'}}, ports: [`127.0.0.1:${port}:${port}`],
      volumes: [`${options.jar}:/app/libs/kestra-amis-apps.jar:ro`, 'storage:/app/storage'],
      environment: {KESTRA_CONFIGURATION: JSON.stringify(configuration), KESTRA_OIDC_UI_CLIENT_SECRET: secrets.client, KESTRA_OIDC_BOOTSTRAP_USERNAME: 'p1-admin', KESTRA_OIDC_BOOTSTRAP_PASSWORD: secrets.password, KESTRA_OIDC_BOOTSTRAP_EMAIL: 'p1@example.invalid'}}
  }};
}
async function run(optionsPath) {
  const options = checkedOptions(JSON.parse(await readFile(optionsPath)));
  const hostImage = (await readFile(resolve(root, 'host-contract.properties'), 'utf8')).trim().replace(/^overlay.image=/, '');
  assert.equal(sha256(await readFile(options.jar)), options.jarSha256, 'Candidate jar hash differs');
  const running = execFileSync('docker', ['ps', '--format', '{{.Image}}'], {encoding: 'utf8'}).trim().split('\n');
  assert.ok(!running.some(image => /(?:^|\/)postgres(?::|@|$)/.test(image)), 'Another PostgreSQL is running; coordinator must allocate the single-PG window');
  await mkdir(options.directory);
  const runtimeJar=resolve(options.directory,'kestra-amis-apps.jar');
  await copyFile(options.jar,runtimeJar);await chmod(runtimeJar,0o444);
  assert.equal(sha256(await readFile(runtimeJar)),options.jarSha256);
  const secrets = Object.fromEntries(['database', 'client', 'password'].map(key => [key, randomBytes(24).toString('hex')]));
  const project = `amis-p1-${randomBytes(5).toString('hex')}`;
  const composePath = resolve(options.directory, 'compose.json');
  await writeFile(composePath, JSON.stringify(composeConfiguration({...options, jar:runtimeJar, hostImage, owner: project}, secrets)), {mode: 0o600});
  const browserPath = resolve(options.directory, 'browser-options.json');
  await writeFile(browserPath, JSON.stringify({...options, runtimeJar, hostImage, project, composePath, baseUrl: `http://localhost:${options.port}`, username: 'p1-admin', password: secrets.password}), {mode: 0o600});
  const compose = args => execFileSync('docker', ['compose', '-p', project, '-f', composePath, ...args], {stdio: 'pipe', maxBuffer: 16 * 1024 * 1024});
  try {
    compose(['up', '-d']);
    const ids = compose(['ps', '-q']).toString().trim().split('\n').filter(Boolean);
    for (const id of ids) {
      assert.equal(execFileSync('docker', ['inspect', '--format', '{{index .Config.Labels \"owner\"}}', id], {encoding:'utf8'}).trim(), project, 'Container owner differs');
    }
    const result = await new Promise((resolveResult, reject) => {
      const child = spawn(process.execPath, [resolve(root, 'ui/scripts/p1-production.mjs'), browserPath], {cwd: root, stdio: 'inherit'});
      child.once('error', reject); child.once('exit', code => resolveResult(code));
    });
    assert.equal(sha256(await readFile(runtimeJar)),options.jarSha256,'Mounted runtime jar changed');
    assert.equal(result, 0, 'Production browser gate failed; inspect its report');
  } finally {
    // Raw host logs may contain sensitive runtime values. Keep locally; never publish these files.
    try {await writeFile(resolve(options.directory, 'host-private.log'), compose(['logs', '--no-color', 'kestra']), {mode: 0o600});} finally {
      const ids = compose(['ps', '-aq']).toString().trim().split('\n').filter(Boolean);
      for (const id of ids) assert.equal(execFileSync('docker', ['inspect', '--format', '{{index .Config.Labels \"owner\"}}', id], {encoding:'utf8'}).trim(), project);
      compose(['down', '-v', '--remove-orphans']);
      assert.equal(execFileSync('docker', ['ps', '-aq', '--filter', `label=owner=${project}`], {encoding:'utf8'}).trim(), '', 'Owned containers remain');
      assert.equal(execFileSync('docker', ['network', 'ls', '-q', '--filter', `label=owner=${project}`], {encoding:'utf8'}).trim(), '', 'Owned network remains');
      assert.equal(execFileSync('docker', ['volume', 'ls', '-q', '--filter', `label=owner=${project}`], {encoding:'utf8'}).trim(), '', 'Owned volume remains');
    }
  }
}
if (process.argv[1] === new URL(import.meta.url).pathname) {
  if (process.argv.length !== 3) throw new Error('Usage: node scripts/run-p1-production.mjs coordinator-options.json');
  await run(process.argv[2]);
}
