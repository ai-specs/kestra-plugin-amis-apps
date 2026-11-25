import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import contract from '../.host-sdk/contract.json' with {type: 'json'};
import {supportedLocales} from '../.host-sdk/locales.mjs';
import {resolvePreferences, validatePreferenceDescriptor, requirePublishedPreferences, createPreferenceAdapter, createPublishedPreferenceAdapter} from '../src/preferences.mjs';

const draft = JSON.parse(await readFile(new URL('./fixtures/preferences-target-draft.json', import.meta.url)));
assert.match(draft.status, /TARGET DRAFT/);
const descriptor = draft.descriptor;
const resolve = values => resolvePreferences(descriptor, values);

function environment(initial = {}, dark = false) {
  const values = new Map(Object.entries(initial));
  const events = new EventTarget();
  const media = new EventTarget();
  media.matches = dark;
  const changes = []; const errors = [];
  const controller = new AbortController();
  const storage = {getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, value)};
  const event = (key, storageArea = storage) => {
    const change = new Event('storage');
    Object.assign(change, {key, storageArea}); events.dispatchEvent(change);
  };
  const adapter = createPreferenceAdapter(descriptor, {storage, events, media, signal: controller.signal, onChange: value => changes.push(value), onError: error => errors.push(error)});
  return {values, events, media, storage, changes, errors, controller, adapter, event};
}

for (const systemDark of [false, true]) test(`TARGET DRAFT missing theme follows system dark=${systemDark}; missing language is en`, () => {
  assert.deepEqual(resolve({systemDark}), {theme: 'syncWithSystem', resolvedTheme: systemDark ? 'dark' : 'light', locale: 'en'});
});
for (const theme of ['light', 'dark', 'dark-2']) for (const systemDark of [false, true]) test(`TARGET DRAFT explicit ${theme} ignores system dark=${systemDark}`, () => {
  assert.equal(resolve({theme, systemDark}).resolvedTheme, theme);
});
for (const theme of ['', 'system', 'unsupported', null, 7]) test(`TARGET DRAFT invalid theme ${JSON.stringify(theme)} defaults to syncWithSystem`, () => {
  assert.equal(resolve({theme, systemDark: true}).resolvedTheme, 'dark');
});
for (const lang of [null, undefined, '', '   ', 'unknown']) test(`TARGET DRAFT empty or unsupported locale ${JSON.stringify(lang)} defaults en`, () => assert.equal(resolve({lang}).locale, 'en'));
for (const lang of supportedLocales) test(`TARGET DRAFT official SDK locale ${lang}`, () => assert.equal(resolve({lang}).locale, lang));
for (const [lang, expected] of [['zh-CN', 'zh_CN'], ['pt-BR', 'pt_BR'], [' zh-CN ', 'zh_CN']]) test(`TARGET DRAFT official SDK alias ${lang}`, () => assert.equal(resolve({lang}).locale, expected));

test('TARGET DRAFT same-document writes notify own view without a storage event', () => {
  const state = environment();
  try {
    state.adapter.setTheme('dark-2'); state.adapter.setLocale('zh-CN');
    assert.equal(state.values.get('theme'), 'dark-2'); assert.equal(state.values.get('lang'), 'zh_CN');
    assert.deepEqual(state.changes.at(-1), {theme: 'dark-2', resolvedTheme: 'dark-2', locale: 'zh_CN'});
    assert.equal(state.changes.length, 3);
    assert.deepEqual([...state.values.keys()].sort(), ['lang', 'theme']);
  } finally {state.adapter.dispose();}
});
test('TARGET DRAFT refresh and new document reparse saved preferences', () => {
  const first = environment();
  try {
    first.adapter.setTheme('dark'); first.adapter.setLocale('pt-BR');
    const second = environment(Object.fromEntries(first.values));
    try {assert.deepEqual(second.adapter.read(), {theme: 'dark', resolvedTheme: 'dark', locale: 'pt_BR'});} finally {second.adapter.dispose();}
    first.values.set('lang', 'zh-CN'); first.adapter.refresh();
    assert.equal(first.changes.at(-1).locale, 'zh_CN');
  } finally {first.adapter.dispose();}
});
test('TARGET DRAFT cross-document storage re-reads stored values, not event payload', () => {
  const state = environment();
  try {
    state.values.set('theme', 'dark'); state.event('theme');
    state.values.set('lang', 'zh-CN'); state.event('lang');
    assert.deepEqual(state.changes.at(-1), {theme: 'dark', resolvedTheme: 'dark', locale: 'zh_CN'});
    const count = state.changes.length;
    state.values.set('theme', 'light'); state.event('unrelated'); state.event('theme', {});
    assert.equal(state.changes.length, count);
    state.values.clear(); state.event(null);
    assert.deepEqual(state.changes.at(-1), {theme: 'syncWithSystem', resolvedTheme: 'light', locale: 'en'});
  } finally {state.adapter.dispose();}
});
test('TARGET DRAFT system changes refresh syncWithSystem and leave explicit theme intact', () => {
  const state = environment();
  try {
    state.media.matches = true; state.media.dispatchEvent(new Event('change'));
    assert.equal(state.changes.at(-1).resolvedTheme, 'dark');
    state.adapter.setTheme('dark-2'); const count = state.changes.length;
    state.media.matches = false; state.media.dispatchEvent(new Event('change'));
    assert.equal(state.changes.length, count);
    state.adapter.setTheme('syncWithSystem'); assert.equal(state.changes.at(-1).resolvedTheme, 'light');
  } finally {state.adapter.dispose();}
});
test('TARGET DRAFT repeated state does not repeat notifications', () => {
  const state = environment();
  try {state.adapter.refresh(); state.event(null); state.media.dispatchEvent(new Event('change')); assert.equal(state.changes.length, 1);} finally {state.adapter.dispose();}
});
test('TARGET DRAFT abort removes listeners; repeated dispose is safe', () => {
  const state = environment();
  state.controller.abort(); state.adapter.dispose();
  state.values.set('theme', 'dark'); state.event('theme'); state.media.matches = true; state.media.dispatchEvent(new Event('change'));
  assert.equal(state.changes.length, 1); assert.throws(() => state.adapter.setTheme('light'), /disposed/); assert.throws(() => state.adapter.read(), /disposed/);
});
test('TARGET DRAFT write failure throws and does not publish fake persisted success', () => {
  const state = environment();
  try {
    state.storage.setItem = () => {throw new Error('Storage denied');};
    assert.throws(() => state.adapter.setTheme('dark'), /Storage denied/); assert.equal(state.changes.length, 1);
  } finally {state.adapter.dispose();}
});
test('TARGET DRAFT event storage failure reaches explicit error callback', () => {
  const state = environment();
  try {
    state.storage.getItem = () => {throw new Error('Storage unavailable');}; state.event('theme');
    assert.equal(state.errors[0].message, 'Storage unavailable'); assert.equal(state.changes.length, 1);
  } finally {state.adapter.dispose();}
});
test('TARGET DRAFT invalid theme write makes no change', () => {
  const state = environment();
  try {assert.throws(() => state.adapter.setTheme('system'), /Unsupported theme/); assert.equal(state.values.size, 0);} finally {state.adapter.dispose();}
});
test('TARGET DRAFT descriptor validation rejects missing or unsupported shape', () => {
  for (const value of [null, {}, {...descriptor, version: 2}, {...descriptor, storage: 'sessionStorage'}, {...descriptor, theme: {...descriptor.theme, key: 'private-key'}}]) assert.throws(() => validatePreferenceDescriptor(value));
});
test('TARGET DRAFT descriptor is copied and frozen', () => {
  const result = validatePreferenceDescriptor(descriptor);
  assert.ok(Object.isFrozen(result.theme.values)); assert.notEqual(result.theme.values, descriptor.theme.values);
});
test('RELEASE BOUNDARY missing official preferences rejects; no draft fallback', () => {
  assert.throws(() => requirePublishedPreferences({}), /Published SDK contract.preferences is missing/);
  assert.throws(() => createPublishedPreferenceAdapter({}, {}), /Published SDK contract.preferences is missing/);
  if (!contract.preferences) assert.throws(() => requirePublishedPreferences(contract), /Published SDK contract.preferences is missing/);
});
test('RELEASE BOUNDARY draft is not imported into production entry or extracted SDK', async () => {
  const entry = await readFile(new URL('../src/main.jsx', import.meta.url), 'utf8');
  assert.doesNotMatch(entry, /preferences-target-draft|createPreferenceAdapter/);
  assert.doesNotMatch(JSON.stringify(contract), /TARGET DRAFT/);
});
