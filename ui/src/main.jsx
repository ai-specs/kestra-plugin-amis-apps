import React from 'react';
import ReactDOM from 'react-dom';
import {render as renderAmis} from 'amis-core';
import 'amis/lib/renderers/Page';
import 'amis/lib/renderers/Panel';
import 'amis/lib/renderers/Tpl';
import 'amis/lib/renderers/Plain';
import 'amis/lib/renderers/Action';
import 'amis-core/lib/renderers/Form';
import 'amis/lib/renderers/Form/InputText';
import 'amis/lib/renderers/Form/Control';
import 'amis/lib/renderers/Form/InputNumber';
import 'amis/lib/renderers/Form/Select';
import 'amis/lib/renderers/Form/InputDate';
import 'amis/lib/renderers/Form/Switch';
import 'amis/lib/renderers/Form/InputArray';
import 'amis/lib/renderers/Service';
import 'amis/lib/renderers/Table/index';
import 'amis/lib/renderers/CRUD';
import 'amis/lib/renderers/Pagination';
import 'amis/lib/renderers/Dialog';
import 'amis-ui/lib/locale/en-US';
import 'amis-ui/lib/locale/zh-CN';
import lightSkin from 'amis/lib/themes/cxd.css?inline';
import darkSkin from 'amis/lib/themes/dark.css?inline';
import 'amis-ui/lib/themes/cxd';
import 'amis-ui/lib/themes/dark';
import 'amis/lib/helper.css';
import 'amis/sdk/iconfont.css';
import {supportedLocales, resolveLocale} from '../.host-sdk/locales.mjs';
import contract from '../.host-sdk/contract.json';
import {parseBootstrap} from './runtime.mjs';
import {navigatePage} from './navigation.mjs';
import {createExecutionSession} from './execution-session.mjs';
import {createPublishedPreferenceAdapter} from './preferences.mjs';
import {localeMessages} from './messages.mjs';
import './style.css';
const lifetime = new AbortController();
let cancelCurrent;
const media = window.matchMedia('(prefers-color-scheme: dark)');

function App() {
  const [locale, setLocale] = React.useState(resolveLocale(localStorage.getItem('lang') || navigator.language));
  const [theme, setTheme] = React.useState(localStorage.getItem('theme') || 'syncWithSystem');
  const [schema, setSchema] = React.useState(null);
  const [error, setError] = React.useState('');
  const [state, setState] = React.useState('loading');
  const [recovery, setRecovery] = React.useState(null);
  const [configuration, setConfiguration] = React.useState(null);
  const [sessions, setSessions] = React.useState([]);
  const [recoveredData, setRecoveredData] = React.useState(null);
  const [systemDark, setSystemDark] = React.useState(media.matches);
  const transport = React.useRef();
  const executionBusy = React.useRef(false);
  const preferences = React.useRef();
  const text = localeMessages[locale];
  const multipleApis = (configuration?.apiIds?.length ?? 1) > 1;
  const busy = multipleApis ? sessions.some(entry => ['waiting', 'paused'].includes(entry.state)) : ['waiting', 'paused'].includes(state);
  const message = value => ['Flow changed. Refresh the page before submitting again.', 'HTTP 409: Flow changed. Refresh the page before submitting again.'].includes(value) ? text.refreshRequired : value;
  const resolvedTheme = theme === 'syncWithSystem' ? (systemDark ? 'dark' : 'light') : theme;
  const skin = resolvedTheme === 'light' ? 'cxd' : 'dark';

  React.useEffect(() => {
    document.documentElement.lang = locale.replace('_', '-');
  }, [locale]);
  React.useEffect(() => {
    document.documentElement.dataset.theme = resolvedTheme;
    document.documentElement.style.colorScheme = resolvedTheme === 'light' ? 'light' : 'dark';
  }, [resolvedTheme]);
  React.useEffect(() => {
    const preventDuplicate = event => {
      if (executionBusy.current && event.target.closest('[data-amis-content] button[type="submit"]') && (event.type !== 'keydown' || ['Enter', ' '].includes(event.key))) {
        event.preventDefault(); event.stopImmediatePropagation();
      }
    };
    document.addEventListener('click', preventDuplicate, true);
    document.addEventListener('mousedown', preventDuplicate, true);
    document.addEventListener('keydown', preventDuplicate, true);
    try {
      preferences.current = createPublishedPreferenceAdapter(contract, {
        storage: window.localStorage, events: window, media, signal: lifetime.signal,
        onChange: next => {setLocale(next.locale); setTheme(next.theme); setSystemDark(media.matches);},
        onError: failure => {setError(failure.message); setState('error');}
      });
      const config = parseBootstrap(JSON.parse(document.getElementById('amis-bootstrap').textContent), location.origin);
      if (!contract.themes.includes('light') || !contract.themes.includes('dark')) throw new Error('Unsupported SDK theme contract');
      setConfiguration(config);
      const multiple = (config.apiIds?.length ?? 1) > 1;
      transport.current = createExecutionSession(config, {
        origin: location.origin,
        csrf: document.querySelector('meta[name="csrf-token"]')?.content,
        signal: lifetime.signal,
        onError: value => {if (!multiple) setError(value);},
        onRecovery: value => {if (!multiple) setRecovery(value);},
        onSessions: setSessions,
        onState: next => {if (!multiple) {executionBusy.current = ['waiting', 'paused'].includes(next); setState(next); if (next === 'waiting') setError('');}}
      }, window.sessionStorage);
      transport.current.schema().then(value => {setSchema(value); setState('idle');}).catch(failure => {setError(failure.message); setState('error');});
    } catch (failure) {setError(failure.message); setState('error');}
    return () => {document.removeEventListener('click', preventDuplicate, true); document.removeEventListener('mousedown', preventDuplicate, true); document.removeEventListener('keydown', preventDuplicate, true); preferences.current?.dispose(); lifetime.abort();};
  }, []);

  const renderedPage = React.useMemo(() => schema && renderAmis(schema, {locale: locale === 'zh_CN' ? 'zh-CN' : 'en-US', theme: skin, ...(recoveredData ? {data: recoveredData} : {})}, {
        fetcher: options => transport.current.fetcher({...options, cancelExecutor: cancel => {if (!multipleApis) cancelCurrent = cancel; options.cancelExecutor?.(cancel);}}),
        isCancel: failure => failure?.name === 'AbortError',
        notify: (type, message) => {if (type === 'error') setError(String(message));},
        alert: message => {setError(String(message)); return Promise.resolve();},
        confirm: () => Promise.resolve(false),
        jumpTo: (target, action) => {try {navigatePage(configuration, target, location.origin, location, action?.replace === true);} catch (failure) {setError(failure.message);}},
        updateLocation: (target, replace) => {try {navigatePage(configuration, target, location.origin, location, replace === true);} catch (failure) {setError(failure.message);}}
      }), [schema, locale, skin, recoveredData, configuration, multipleApis]);

  return <>
    <style data-amis-skin={skin}>{skin === 'dark' ? darkSkin : lightSkin}</style>
    <header>
      <strong>AMIS Apps</strong>
      <label>{text.language}<select aria-label={text.language} value={locale} onChange={event => preferences.current?.setLocale(event.target.value)}>
        {supportedLocales.map(value => <option key={value} value={value}>{value}</option>)}
      </select></label>
      <label>{text.theme}<select aria-label={text.theme} value={theme} onChange={event => preferences.current?.setTheme(event.target.value)}>
        <option value="syncWithSystem">{text.system}</option><option value="light">{text.light}</option><option value="dark">{text.dark}</option><option value="dark-2">Dark 2</option>
      </select></label>
      <a href="/oidc/logout">{text.logout}</a>
    </header>
    <main aria-busy={state === 'loading' || busy}>
      <p role="status" aria-live="polite">{text[state] || ''}</p>
      {error && <section role="alert"><strong>{text.error}</strong><p>{message(error)}</p><a href="/oidc/logout">{text.logout}</a></section>}
      {!multipleApis && (state === 'waiting' || state === 'paused') && <button onClick={() => cancelCurrent?.()}>{text.cancel}</button>}
      {!multipleApis && recovery && <section aria-label={text.recovery}>
        <p>{recovery.phase === 'pending' ? `${text.recovery}: ${recovery.executionId}` : text.uncertain}</p>
        {recovery.phase === 'pending' && !['waiting', 'paused'].includes(state) && <button onClick={() => {
          transport.current.resume({cancelExecutor: cancel => {cancelCurrent = cancel;}})
            .then(result => {setRecoveredData(result.data.data); setError('');}).catch(failure => setError(failure.message));
        }}>{text.resume}</button>}
        {!['waiting', 'paused'].includes(state) && <button onClick={() => {
          if (window.confirm(text.forgetConfirm)) {transport.current.forget(); setError(''); setState('idle');}
        }}>{text.forget}</button>}
      </section>}
      {multipleApis && sessions.map(entry => <section key={entry.apiUrl} aria-label={`${text.recovery}: ${entry.apiUrl.slice(configuration.apiBase.length)}`} data-api-id={entry.apiUrl.slice(configuration.apiBase.length)}>
        <h2>{entry.apiUrl.slice(configuration.apiBase.length)}</h2>
        <p role="status" aria-live="polite">{text[entry.state] || ''}</p>
        {entry.error && <p role="alert">{message(entry.error)}</p>}
        {entry.active && <button onClick={() => transport.current.cancel(entry.apiUrl)}>{text.cancel}</button>}
        {entry.recovery && <p>{entry.recovery.phase === 'pending' ? `${text.recovery}: ${entry.recovery.executionId}` : text.uncertain}</p>}
        {entry.recovery?.phase === 'pending' && !entry.active && <button onClick={() => transport.current.resume({}, entry.apiUrl).catch(() => {})}>{text.resume}</button>}
        {entry.recovery && !entry.active && <button onClick={() => {if (window.confirm(text.forgetConfirm)) transport.current.forget(entry.apiUrl);}}>{text.forget}</button>}
        {entry.result !== null && <div aria-label={text.recoveredResult}><pre>{JSON.stringify(entry.result, null, 2)}</pre></div>}
      </section>)}
      {schema && <fieldset className="amis-content" data-amis-content disabled={!multipleApis && (state === 'waiting' || state === 'paused')}>{renderedPage}</fieldset>}
    </main>
  </>;
}

window.addEventListener('pagehide', () => {lifetime.abort(); ReactDOM.unmountComponentAtNode(document.getElementById('root'));}, {once: true});
ReactDOM.render(<App />, document.getElementById('root'));
