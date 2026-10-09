// @ts-check
import { defineConfig } from 'astro/config';

// Static site served by Infomaniak web hosting (Apache).
// format 'directory': /fr/packages/ -> fr/packages/index.html, served without rewrites.
// The catalogue, logo and screenshots are read from the parent repository folder, hence fs.allow.
export default defineConfig({
  site: 'https://tvslim.app',
  output: 'static',
  build: { format: 'directory', inlineStylesheets: 'auto' },
  trailingSlash: 'ignore',
  i18n: {
    defaultLocale: 'en',
    locales: ['en', 'fr'],
    routing: { prefixDefaultLocale: false },
  },
  vite: { server: { fs: { allow: ['..'] } } },
});
