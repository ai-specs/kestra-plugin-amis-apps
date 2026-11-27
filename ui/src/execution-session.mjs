import {createTransport, safeUrl, validateExecutionUrl} from './runtime.mjs';
import {canonicalInputs} from './navigation.mjs';

// Every page/API owns its identity record. Records never confer authorization.
export function createExecutionSession(config, options, storage) {
  const legacyKey = `amis.execution.v1:${config.namespace}/${config.flowId}/${config.pageId}`;
  const modern = Array.isArray(config.apiIds);
  const entries = new Map();
  let refreshRequired = false;
  function apiPath(raw) {
    const value = typeof raw === 'string' && /^[A-Za-z0-9_][A-Za-z0-9_-]{0,99}$/.test(raw) ? config.apiBase + raw : raw;
    const url = safeUrl(value, options.origin);
    const id = url.pathname.slice(config.apiBase.length);
    if (!url.pathname.startsWith(config.apiBase) || !/^[A-Za-z0-9_][A-Za-z0-9_-]{0,99}$/.test(id) || modern && !config.apiIds.includes(id)) throw new Error('Only same Flow POST API is allowed');
    return url.pathname;
  }
  const keyFor = api => modern ? `amis.execution.v2:${config.namespace}/${config.flowId}/${config.pageId}/${api.slice(config.apiBase.length)}` : legacyKey;
  function validate(value, version, api) {
    if (!value || value.version !== version || !['submitting', 'pending'].includes(value.phase) || Object.keys(value).some(key => !(value.phase === 'pending' ? ['version', 'phase', 'apiUrl', 'executionId', 'executionUrl'] : ['version', 'phase', 'apiUrl']).includes(key))) throw new Error('Invalid execution recovery record');
    const actual = apiPath(value.apiUrl);
    if (api && actual !== api) throw new Error('Recovery record belongs to another API');
    if (value.phase === 'pending') validateExecutionUrl(value.executionUrl, safeUrl(actual, options.origin), value.executionId, options.origin);
    return value.phase === 'pending' ? {version, phase: value.phase, apiUrl: actual, executionId: value.executionId, executionUrl: value.executionUrl} : {version, phase: value.phase, apiUrl: actual};
  }
  const snapshots = () => [...entries.values()].map(entry => ({apiUrl: entry.api, state: entry.state, error: entry.error, recovery: entry.current, active: Boolean(entry.active), result: entry.result}));
  const publish = () => options.onSessions?.(snapshots());
  function save(entry, value) {
    if (value) storage.setItem(keyFor(entry.api), JSON.stringify(value));
    else storage.removeItem(keyFor(entry.api));
    entry.current = value;
    options.onRecovery?.(value, entry.api); publish();
  }
  function get(api) {
    if (entries.has(api)) return entries.get(api);
    const entry = {api, state: 'idle', error: '', current: null, active: null, result: null};
    const version = modern ? 2 : 1;
    const raw = storage.getItem(keyFor(api));
    if (raw) entry.current = validate(JSON.parse(raw), version, api);
    entry.transport = createTransport(config, {...options,
      onState: state => {entry.state = state; if (state === 'waiting') entry.error = ''; options.onState?.(state, api); publish();},
      onError: message => {entry.error = message; options.onError?.(message, api); publish();},
      onExecution: value => {save(entry, value ? validate({...value, version, phase: 'pending'}, version, api) : null); options.onExecution?.(value, api);}
    });
    entries.set(api, entry);
    options.onRecovery?.(entry.current, api);
    return entry;
  }
  if (modern) {
    // Validate all destination records before migration; conflict never picks a winner.
    for (const id of config.apiIds) get(config.apiBase + id);
    const old = storage.getItem(legacyKey);
    if (old) {
      const record = validate(JSON.parse(old), 1);
      const entry = get(record.apiUrl), migrated = {...record, version: 2};
      if (entry.current && canonicalInputs(entry.current) !== canonicalInputs(migrated)) throw new Error('Conflicting legacy execution recovery records');
      if (!entry.current) storage.setItem(keyFor(entry.api), JSON.stringify(migrated));
      // On failure keep the legacy record. No implicit POST is ever attempted.
      storage.removeItem(legacyKey);
      entry.current = migrated; options.onRecovery?.(migrated, entry.api);
    }
  } else {
    const old = storage.getItem(legacyKey);
    if (old) {const record = validate(JSON.parse(old), 1); get(record.apiUrl);}
    else options.onRecovery?.(null);
  }
  publish();
  function run(entry, identity, request, operation) {
    if (entry.active) {
      if (entry.active.identity !== identity) {const error = new Error('An execution request is already active'); entry.error = error.message; options.onError?.(error.message, entry.api); publish(); return Promise.reject(error);}
      const cancel = entry.active.cancel;
      request.cancelExecutor?.(cancel); request.config?.cancelExecutor?.(cancel);
      return entry.active.promise;
    }
    const controller = new AbortController();
    const cancel = () => controller.abort();
    const promise = Promise.resolve().then(() => operation(controller.signal)).catch(error => {
      if (error.refreshRequired) refreshRequired = true;
      entry.error = error.message; options.onError?.(error.message, entry.api); publish();
      throw error;
    }).finally(() => {entry.active = null; publish();});
    entry.active = {identity, promise, cancel};
    request.cancelExecutor?.(cancel); request.config?.cancelExecutor?.(cancel); publish();
    return promise;
  }
  function select(raw) {
    if (raw) return get(apiPath(raw));
    const current = [...entries.values()].filter(entry => entry.current);
    if (current.length !== 1) throw new Error('No confirmed execution to resume; select an API');
    return current[0];
  }
  return {
    async schema() {
      try {return await createTransport(config, options).schema();}
      catch (error) {if (error.refreshRequired) refreshRequired = true; throw error;}
    },
    snapshots,
    fetcher(request) {
      try {
        if (refreshRequired) throw Object.assign(new Error('Flow changed. Refresh the page before submitting again.'), {refreshRequired: true});
        if ((request.method ?? 'get').toLowerCase() !== 'post') throw new Error('Only same Flow POST API is allowed');
        const api = apiPath(request.url), entry = get(api);
        if (!request.data || typeof request.data !== 'object' || Array.isArray(request.data)) throw new Error('API body must be a JSON object');
        const input = JSON.parse(canonicalInputs(request.data));
        const identity = canonicalInputs([api, 'post', input]);
        return run(entry, identity, request, async signal => {
          if (entry.current) throw new Error(entry.current.phase === 'pending' ? 'Resume the existing execution before submitting again' : 'Submission outcome is unknown; do not automatically retry');
          save(entry, {version: modern ? 2 : 1, phase: 'submitting', apiUrl: api});
          try {return await entry.transport.fetcher({...request, data: input, url: api, cancelExecutor: undefined, config: {...request.config, cancelExecutor: undefined}, signal: request.signal ? AbortSignal.any([signal, request.signal]) : signal});}
          catch (error) {
            if (entry.current?.phase === 'submitting' && (/^HTTP (400|401|403|404|405|408|409|413|415|422|429)(?:$|:)/.test(error.message) || /^(Missing CSRF token|API body must|Only same Flow POST)/.test(error.message))) save(entry, null);
            throw error;
          }
        });
      } catch (error) {
        try {const entry = get(apiPath(request.url)); entry.error = error.message; options.onError?.(error.message, entry.api); publish();} catch {}
        return Promise.reject(error);
      }
    },
    resume(request = {}, api) {
      try {
        const entry = select(api);
        return run(entry, 'resume', request, async signal => {
          if (entry.current?.phase !== 'pending') throw new Error('No confirmed execution to resume');
          const result = await entry.transport.fetcher({...request, url: entry.api, method: 'post', cancelExecutor: undefined, config: {...request.config, cancelExecutor: undefined}, signal: request.signal ? AbortSignal.any([signal, request.signal]) : signal, resumeExecution: entry.current});
          entry.result = result.data.data; publish();
          return result;
        });
      } catch (error) {return Promise.reject(error);}
    },
    cancel(api) {const entry = select(api); entry.active?.cancel();},
    forget(api) {const entry = select(api); if (entry.active) throw new Error('Wait for the active request to finish'); save(entry, null); entry.error = ''; entry.state = 'idle'; publish();}
  };
}
