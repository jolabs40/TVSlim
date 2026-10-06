// Le favicon est le logo lui-même, tel que outils/logo.py le dessine — en petit, sa version simplifiée.
import type { APIRoute } from 'astro';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

export const GET: APIRoute = () =>
  new Response(readFileSync(resolve(process.cwd(), '../docs/logo/tvslim-petit.svg')), { headers: { 'Content-Type': 'image/svg+xml' } });
