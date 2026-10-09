// Sitemap with hreflang alternates, so Google links the English and French pages of each package.
import type { APIRoute } from 'astro';
import { allPackageIds } from '../data/catalogue';
import { url, LANGS } from '../site';

const PAGES = ['/', '/download/', '/guide/', '/packages/', '/privacy/'];

export const GET: APIRoute = () => {
  const paths = [...PAGES, ...allPackageIds().map((id) => `/packages/${id}/`)];
  const entries = paths.flatMap((p) =>
    LANGS.map(
      (lang) => `  <url>
    <loc>${url(lang, p)}</loc>
${LANGS.map((l) => `    <xhtml:link rel="alternate" hreflang="${l}" href="${url(l, p)}"/>`).join('\n')}
    <xhtml:link rel="alternate" hreflang="x-default" href="${url('en', p)}"/>
  </url>`,
    ),
  );
  const xml = `<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:xhtml="http://www.w3.org/1999/xhtml">
${entries.join('\n')}
</urlset>
`;
  return new Response(xml, { headers: { 'Content-Type': 'application/xml; charset=utf-8' } });
};
