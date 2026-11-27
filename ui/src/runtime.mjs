const flowIdentifier = /^[a-zA-Z0-9][a-zA-Z0-9._-]*$/;
const customIdentifier = /^[A-Za-z0-9_][A-Za-z0-9_-]{0,99}$/;
const executionIdentifier = /^[A-Za-z0-9_-]{1,150}$/;
const namespacePattern = /^[a-z0-9][a-z0-9_-]*(\.[a-z0-9_-]+)*$/;
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const executionStates = new Set(['CREATED', 'SUBMITTED', 'RUNNING', 'PAUSED', 'RESTARTED', 'KILLING', 'SUCCESS', 'WARNING', 'FAILED', 'KILLED', 'CANCELLED', 'QUEUED', 'RETRYING', 'RETRIED', 'SKIPPED', 'BREAKPOINT', 'RESUBMITTED']);
const failedStates = new Set(['FAILED', 'KILLED', 'CANCELLED']);
const terminalStates = new Set(['SUCCESS', 'WARNING', 'RETRIED', 'SKIPPED', 'RESUBMITTED', ...failedStates]);

function validateExecutionResponse(body, httpStatus) {
  if (typeof body.executionId !== 'string' || !executionIdentifier.test(body.executionId)) throw new Error('Invalid execution ID');
  if (typeof body.executionState !== 'string' || !executionStates.has(body.executionState)) throw new Error('Unsupported execution state');
  if (!Number.isInteger(body.status) || typeof body.resultReady !== 'boolean' || !object(body.data)) throw new Error('Invalid execution response');
  if ((body.resultReady && !terminalStates.has(body.executionState)) ||
      (httpStatus === 200 && !body.resultReady) || (httpStatus === 202 && body.resultReady) ||
      ![200, 202].includes(httpStatus)) throw new Error('Invalid execution readiness');
}

export function safeUrl(raw, origin) {
  if (typeof raw !== 'string' || /[%\\\s]/.test(raw) || raw.includes('/.') || raw.startsWith('//')) throw new Error('Unsafe URL');
  const url = new URL(raw, origin);
  if (url.origin !== origin || url.username || url.password || url.search || url.hash) throw new Error('Unsafe URL');
  return url;
}

export function parseBootstrap(value, origin) {
  if (!object(value) || value.version !== 1 || typeof value.namespace !== 'string' || typeof value.flowId !== 'string' || typeof value.pageId !== 'string' ||
      !namespacePattern.test(value.namespace) || value.namespace.length > 150 || !flowIdentifier.test(value.flowId) || value.flowId.length > 100 || !customIdentifier.test(value.pageId)) throw new Error('Invalid bootstrap');
  const prefix = `/${value.namespace}/${value.flowId}`;
  for (const [field, expected] of Object.entries({schemaUrl: `${prefix}/schemas/${value.pageId}`, apiBase: `${prefix}/apis/`, assetBase: `${prefix}/assets/`})) {
    if (safeUrl(value[field], origin).pathname !== expected) throw new Error(`Invalid ${field}`);
  }
  const maxima = {requestTimeoutMs: 30000, pollIntervalMs: 10000, pollTimeoutMs: 300000};
  for (const [field, max] of Object.entries(maxima)) {
    if (!Number.isInteger(value.limits?.[field]) || value.limits[field] < 1 || value.limits[field] > max) throw new Error(`Invalid ${field}`);
  }
  const fields = ['flowRevision', 'pageIds', 'apiIds'];
  if (fields.some(field => Object.hasOwn(value, field))) {
    if (!Number.isSafeInteger(value.flowRevision) || value.flowRevision <= 0 || value.flowRevision > 2147483647) throw new Error('Invalid flowRevision');
    for (const field of ['pageIds', 'apiIds']) {
      const ids = value[field];
      if (!Array.isArray(ids) || new Set(ids).size !== ids.length || ids.some(id => typeof id !== 'string' || !customIdentifier.test(id))) throw new Error(`Invalid ${field}`);
    }
    if (!value.pageIds.includes(value.pageId)) throw new Error('Current page is not declared');
  }
  return value;
}

export function validateExecutionUrl(raw, apiUrl, executionId, origin) {
  if (typeof executionId !== 'string' || !executionIdentifier.test(executionId)) throw new Error('Invalid execution ID');
  const url = safeUrl(raw, origin);
  if (url.pathname !== `${apiUrl.pathname}/executions/${executionId}`) throw new Error('Unsafe execution URL');
  return url;
}

function delay(milliseconds, signal) {
  return new Promise((resolve, reject) => {
    const abort = () => { clearTimeout(timer); signal.removeEventListener('abort', abort); reject(signal.reason); };
    const timer = setTimeout(() => { signal.removeEventListener('abort', abort); resolve(); }, milliseconds);
    signal.addEventListener('abort', abort, {once: true});
    if (signal.aborted) abort();
  });
}

export function createTransport(config, {origin, csrf, signal, fetchImpl = fetch, onError = () => {}, onState = () => {}, onExecution = () => {}, onSchema = () => {}}) {
  async function request(url, options, outerSignal) {
    const timeout = AbortSignal.timeout(config.limits.requestTimeoutMs);
    const combined = AbortSignal.any([signal, timeout, ...(outerSignal ? [outerSignal] : [])]);
    const response = await fetchImpl(url.href, {...options, credentials: 'same-origin', redirect: 'error', cache: 'no-store', signal: combined});
    if (!response.ok) {
      const refreshRequired = response.status === 409 && response.headers.get('X-AMIS-Error-Code') === 'FLOW_REVISION_CONFLICT';
      await response.arrayBuffer();
      const error = new Error(refreshRequired ? 'HTTP 409: Flow changed. Refresh the page before submitting again.' : `HTTP ${response.status}`);
      error.refreshRequired = refreshRequired;
      throw error;
    }
    if (config.flowRevision && response.headers.get('X-AMIS-Flow-Revision') !== String(config.flowRevision)) throw Object.assign(new Error('Flow changed. Refresh the page before submitting again.'), {refreshRequired: true});
    const body = await response.json();
    if (!object(body)) throw new Error('Invalid JSON response');
    return {response, body};
  }
  return {
    async schema() {
      const result = await request(safeUrl(config.schemaUrl, origin), {method: 'GET', ...(config.flowRevision ? {headers: {'X-AMIS-Flow-Revision': String(config.flowRevision)}} : {})});
      if (config.flowRevision) {
        const sha256 = result.response.headers.get('X-AMIS-Schema-SHA256');
        if (!/^[a-f0-9]{64}$/.test(sha256 ?? '')) throw new Error('Invalid schema source digest');
        onSchema({flowRevision: config.flowRevision, sha256});
      }
      return result.body;
    },
    async fetcher({url: raw, method = 'get', data, signal: callerSignal, cancelExecutor, config: requestConfig = {}, resumeExecution}) {
      const controller = new AbortController();
      cancelExecutor?.(() => controller.abort());
      requestConfig.cancelExecutor?.(() => controller.abort());
      const combined = AbortSignal.any([signal, controller.signal, ...(callerSignal ? [callerSignal] : []), ...(requestConfig.signal ? [requestConfig.signal] : []), AbortSignal.timeout(config.limits.pollTimeoutMs)]);
      try {
        const base = safeUrl(config.apiBase, origin).pathname;
        const url = safeUrl(typeof raw === 'string' && customIdentifier.test(raw) ? `${base}${raw}` : raw, origin);
        const apiId = url.pathname.slice(base.length);
        if (method.toLowerCase() !== 'post' || !url.pathname.startsWith(base) || !customIdentifier.test(apiId) || config.apiIds && !config.apiIds.includes(apiId)) throw new Error('Only same Flow POST API is allowed');
        if (!resumeExecution && (typeof csrf !== 'string' || !csrf.trim() || csrf.includes('__AMIS_') || /[\r\n]/.test(csrf))) throw new Error('Missing CSRF token');
        if (!resumeExecution && !object(data)) throw new Error('API body must be a JSON object');
        onState('waiting');
        const recoveryUrl = resumeExecution && validateExecutionUrl(resumeExecution.executionUrl, url, resumeExecution.executionId, origin);
        let result = recoveryUrl ? await request(recoveryUrl, {method: 'GET'}, combined) : await request(url, {method: 'POST', headers: {'Content-Type': 'application/json', 'X-CSRF-TOKEN': csrf, ...(config.flowRevision ? {'X-AMIS-Flow-Revision': String(config.flowRevision)} : {})}, body: JSON.stringify(data)}, combined);
        const first = result.body;
        validateExecutionResponse(first, result.response.status);
        if (config.flowRevision) validateExecutionUrl(first.executionUrl, url, first.executionId, origin);
        if (resumeExecution && first.executionId !== resumeExecution.executionId) throw new Error('Execution identity changed');
        let pollingUrl = recoveryUrl;
        if (result.response.status === 202 || first.resultReady === false) {
          pollingUrl = recoveryUrl || validateExecutionUrl(first.executionUrl, url, first.executionId, origin);
          onExecution({apiUrl: url.pathname, executionId: first.executionId, executionUrl: pollingUrl.pathname});
        }
        let wasPaused = false;
        while (true) {
          const body = result.body;
          validateExecutionResponse(body, result.response.status);
          if (config.flowRevision) validateExecutionUrl(body.executionUrl, url, first.executionId, origin);
          if (pollingUrl && body.executionId !== first.executionId) throw new Error('Execution identity changed');
          if (body.status !== 0 || failedStates.has(body.executionState)) {
            if (terminalStates.has(body.executionState)) onExecution(null);
            throw new Error(`Execution failed (${body.executionState || body.status})`);
          }
          if (body.resultReady === true && result.response.status === 200) {
            if (!object(body.data)) throw new Error('Missing result data');
            onExecution(null);
            onState('success');
            return {data: body};
          }
          if (!pollingUrl || result.response.status !== 202 || body.resultReady !== false) throw new Error('Result not ready');
          const paused = ['PAUSED', 'BREAKPOINT'].includes(body.executionState);
          if (paused !== wasPaused) onState(paused ? 'paused' : 'waiting');
          wasPaused = paused;
          await delay(config.limits.pollIntervalMs, combined);
          result = await request(pollingUrl, {method: 'GET'}, combined);
        }
      } catch (error) {
        const message = error.name === 'TimeoutError' ? 'Request timed out' : error.name === 'AbortError' ? 'Request cancelled' : error.message;
        onState('error');
        onError(message);
        throw Object.assign(new Error(message), {name: error.name, refreshRequired: error.refreshRequired === true});
      }
    }
  };
}
