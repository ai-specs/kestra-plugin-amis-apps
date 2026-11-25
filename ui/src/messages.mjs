import {supportedLocales} from '../.host-sdk/locales.mjs';
import english from './locales/en.json' with {type: 'json'};
import chinese from './locales/zh_CN.json' with {type: 'json'};
const messages = {...english, ...chinese};

export const localeMessages = Object.freeze(Object.fromEntries(supportedLocales.map(locale => [locale, Object.freeze(messages[locale] || messages.en)])));
