import {safeUrl} from './runtime.mjs';

export function pageNavigationUrl(config, raw, origin) {
  if (!config || !Array.isArray(config.pageIds)) throw new Error('Page navigation requires published declarations');
  const prefix = `/${config.namespace}/${config.flowId}/pages/`;
  const target = safeUrl(typeof raw === 'string' && /^[A-Za-z0-9_][A-Za-z0-9_-]{0,99}$/.test(raw) ? prefix + raw : raw, origin);
  const pageId = target.pathname.slice(prefix.length);
  if (!target.pathname.startsWith(prefix) || !config.pageIds.includes(pageId) || target.pathname !== prefix + pageId) throw new Error('Page navigation is outside the declared Flow');
  return target.href;
}
export function navigatePage(config, raw, origin, location, replace = false) {
  const url = pageNavigationUrl(config, raw, origin);
  if (replace) location.replace(url);
  else location.assign(url);
}

// Objects have set-like key order; arrays retain order and all JSON types stay distinct.
export function canonicalInputs(value) {
  const ancestors = new Set();
  function visit(item) {
    if (item === null || typeof item === 'string' || typeof item === 'boolean') return JSON.stringify(item);
    if (typeof item === 'number' && Number.isFinite(item)) return JSON.stringify(item);
    if (!item || typeof item !== 'object' || ancestors.has(item)) throw new Error('API inputs must be finite JSON values');
    {
      const array = Array.isArray(item);
      let prototype = item;
      while (prototype !== null && prototype !== Object.prototype && !(array && prototype === Array.prototype)) {
        const descriptors = Object.getOwnPropertyDescriptors(prototype);
        if (Object.hasOwn(descriptors, 'toJSON') || Reflect.ownKeys(descriptors).some(key => typeof key === 'symbol')) throw new Error('API inputs cannot customize JSON serialization');
        if (Object.hasOwn(descriptors, 'constructor') && descriptors.constructor.value !== Object && !(array && descriptors.constructor.value === Array)) throw new Error('API inputs must be plain JSON objects');
        for (const descriptor of Object.values(descriptors)) if (descriptor.get || descriptor.set) throw new Error('API inputs cannot contain accessors');
        prototype = Object.getPrototypeOf(prototype);
      }
    }
    ancestors.add(item);
    let result;
    if (Array.isArray(item)) {
      if (Object.keys(item).length !== item.length) throw new Error('API inputs cannot contain sparse arrays');
      result = '[' + Array.from(item, visit).join(',') + ']';
    } else {
      result = '{' + Object.keys(item).sort().map(key => JSON.stringify(key) + ':' + visit(item[key])).join(',') + '}';
    }
    ancestors.delete(item);
    return result;
  }
  return visit(value);
}
