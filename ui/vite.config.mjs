import {defineConfig} from 'vite';

export default defineConfig({
  base: './',
  resolve: {alias: [{find: /^amis-ui$/, replacement: new URL('./src/amis-ui-public.mjs', import.meta.url).pathname}]},
  css: {lightningcss: {errorRecovery: true}},
  build: {assetsInlineLimit: 0},
  define: {'process.env.NODE_ENV': JSON.stringify('production')}
});
