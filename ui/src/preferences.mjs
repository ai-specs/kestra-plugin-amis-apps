import {resolveLocale} from '../.host-sdk/locales.mjs';

export function validatePreferenceDescriptor(descriptor) {
  if (!descriptor || (descriptor.version ?? descriptor.contractVersion) !== 1 || descriptor.storage !== 'localStorage' ||
      descriptor.theme?.key !== 'theme' || descriptor.theme?.default !== 'syncWithSystem' ||
      descriptor.theme?.systemValue !== 'syncWithSystem' || descriptor.locale?.key !== 'lang' ||
      descriptor.locale?.default !== 'en' || !Array.isArray(descriptor.theme.values) ||
      !descriptor.theme.values.includes('light') || !descriptor.theme.values.includes('dark') ||
      !descriptor.theme.values.includes('dark-2') || !descriptor.theme.values.includes(descriptor.theme.systemValue) ||
      !Array.isArray(descriptor.locale.supported) || !descriptor.locale.aliases) {
    throw new Error('Unsupported public preferences descriptor');
  }
  return Object.freeze({
    version: 1, storage: descriptor.storage,
    theme: Object.freeze({...descriptor.theme, values: Object.freeze([...descriptor.theme.values])}),
    locale: Object.freeze({...descriptor.locale, supported: Object.freeze([...descriptor.locale.supported]), aliases: Object.freeze({...descriptor.locale.aliases})})
  });
}

export function requirePublishedPreferences(contract) {
  if (!contract?.preferences) throw new Error('Published SDK contract.preferences is missing; host preference compatibility is not available');
  return validatePreferenceDescriptor(contract.preferences);
}

export function resolvePreferences(descriptor, {theme, lang, systemDark}) {
  const checked = validatePreferenceDescriptor(descriptor);
  const selectedTheme = checked.theme.values.includes(theme) || theme === checked.theme.systemValue ? theme : checked.theme.default;
  const locale = typeof lang === 'string' && lang.trim() ? resolveLocale(lang.trim()) : checked.locale.default;
  return Object.freeze({
    theme: selectedTheme,
    resolvedTheme: selectedTheme === checked.theme.systemValue ? (systemDark ? 'dark' : 'light') : selectedTheme,
    locale
  });
}

export function createPreferenceAdapter(descriptor, {storage, events, media, signal, onChange, onError}) {
  const checked = validatePreferenceDescriptor(descriptor);
  if (!storage || !events || !media || typeof onChange !== 'function' || typeof onError !== 'function') throw new Error('Missing preferences adapter environment');
  let disposed = false;
  let current;
  const read = () => resolvePreferences(checked, {
    theme: storage.getItem(checked.theme.key), lang: storage.getItem(checked.locale.key), systemDark: media.matches
  });
  const publish = () => {
    if (disposed) return;
    const next = read();
    if (!current || next.theme !== current.theme || next.resolvedTheme !== current.resolvedTheme || next.locale !== current.locale) {
      current = next;
      onChange(next);
    }
    return current;
  };
  const handleEvent = () => {
    try {publish();} catch (error) {onError(error);}
  };
  const storageChanged = event => {
    if (event.storageArea === storage && (event.key === null || event.key === checked.theme.key || event.key === checked.locale.key)) handleEvent();
  };
  const dispose = () => {
    if (disposed) return;
    disposed = true;
    events.removeEventListener('storage', storageChanged);
    media.removeEventListener('change', handleEvent);
    signal?.removeEventListener('abort', dispose);
  };
  if (signal?.aborted) throw new Error('Preferences adapter lifetime already aborted');
  publish();
  events.addEventListener('storage', storageChanged);
  media.addEventListener('change', handleEvent);
  signal?.addEventListener('abort', dispose, {once: true});
  const write = (key, value) => {
    if (disposed) throw new Error('Preferences adapter disposed');
    storage.setItem(key, value);
    return publish();
  };
  return Object.freeze({
    read: () => {if (disposed) throw new Error('Preferences adapter disposed'); return read();},
    refresh: () => {if (disposed) throw new Error('Preferences adapter disposed'); return publish();},
    setTheme: value => {
      if (!checked.theme.values.includes(value) && value !== checked.theme.systemValue) throw new Error('Unsupported theme preference');
      return write(checked.theme.key, value);
    },
    setLocale: value => write(checked.locale.key, typeof value === 'string' && value.trim() ? resolveLocale(value.trim()) : checked.locale.default),
    dispose
  });
}

export function createPublishedPreferenceAdapter(contract, environment) {
  return createPreferenceAdapter(requirePublishedPreferences(contract), environment);
}
