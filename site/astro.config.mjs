// @ts-check
import { defineConfig } from 'astro/config';

// Site statique servi par l'Hébergement Web Infomaniak (Apache), comme startlightlauncher.com.
// format 'directory' : /fr/packages/ -> fr/packages/index.html, servi sans réécriture.
// Le catalogue, le logo et les captures se lisent dans le dépôt, un cran au-dessus : fs.allow.
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
