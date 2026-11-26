import {createTransport, safeUrl, validateExecutionUrl} from './runtime.mjs';

// Browser session recovery stores identity only. Every GET remains authorized by the server.
export function createExecutionSession(config, options, storage) {
  const key = `amis.execution.v1:${config.namespace}/${config.flowId}/${config.pageId}`;
  let current;
  let active;
  const report = value => options.onRecovery?.(value);
  function validate(value) {
    if (!value || value.version !== 1 || !['submitting', 'pending'].includes(value.phase) || Object.keys(value).some(key => !(value.phase === 'pending' ? ['version', 'phase', 'apiUrl', 'executionId', 'executionUrl'] : ['version', 'phase', 'apiUrl']).includes(key))) throw new Error('Invalid execution recovery record');
    const api = safeUrl(value.apiUrl, options.origin);
    if (!api.pathname.startsWith(config.apiBase) || !/^[A-Za-z0-9_][A-Za-z0-9_-]{0,99}$/.test(api.pathname.slice(config.apiBase.length))) throw new Error('Invalid execution recovery API');
    if (value.phase === 'pending') validateExecutionUrl(value.executionUrl, api, value.executionId, options.origin);
    return value.phase === 'pending' ? {version: 1, phase: value.phase, apiUrl: api.pathname, executionId: value.executionId, executionUrl: value.executionUrl} : {version: 1, phase: value.phase, apiUrl: api.pathname};
  }
  function save(value) {
    if (value) storage.setItem(key, JSON.stringify(value));
    else storage.removeItem(key);
    current = value;
    report(current);
  }
  const raw = storage.getItem(key);
  if (raw) current = validate(JSON.parse(raw));
  report(current);
  const transport = createTransport(config, {...options, onExecution: value => {
    save(value ? validate({...value, version: 1, phase: 'pending'}) : null);
    options.onExecution?.(value);
  }});
  function run(identity, operation) {
    if (active) {
      if (active.identity === identity) return active.promise;
      return Promise.reject(new Error('An execution request is already active'));
    }
    const promise = Promise.resolve().then(operation).finally(() => {active = null;});
    active = {identity, promise};
    return promise;
  }
  return {
    schema: transport.schema,
    fetcher(request) {
      const identity = JSON.stringify([request.url, request.method, request.data]);
      return run(identity, async () => {
        if (current) throw new Error(current.phase === 'pending' ? 'Resume the existing execution before submitting again' : 'Submission outcome is unknown; do not automatically retry');
        const base = config.apiBase;
        const rawUrl = typeof request.url === 'string' && /^[A-Za-z0-9_][A-Za-z0-9_-]{0,99}$/.test(request.url) ? `${base}${request.url}` : request.url;
        const apiUrl = safeUrl(rawUrl, options.origin).pathname;
        const record = validate({version: 1, phase: 'submitting', apiUrl});
        // Persist before sending: an interrupted POST must not be replayed after refresh.
        save(record);
        try { return await transport.fetcher(request); }
        catch (error) {
          if (current?.phase === 'submitting' && (/^HTTP (400|401|403|404|405|408|409|413|415|422|429)$/.test(error.message) ||
              /^(Missing CSRF token|API body must|Only same Flow POST)/.test(error.message))) save(null);
          throw error;
        }
      });
    },
    resume(request = {}) {
      return run('resume', () => {
        if (current?.phase !== 'pending') throw new Error('No confirmed execution to resume');
        return transport.fetcher({...request, url: current.apiUrl, method: 'post', resumeExecution: current});
      });
    },
    forget() {
      if (active) throw new Error('Wait for the active request to finish');
      save(null);
    }
  };
}
