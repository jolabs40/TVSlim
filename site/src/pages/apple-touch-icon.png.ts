// Home screen shortcut icon on phones: square, the OS rounds the corners.
import type { APIRoute } from 'astro';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

export const GET: APIRoute = () =>
  new Response(readFileSync(resolve(process.cwd(), '../docs/logo/tvslim-carre-180.png')), { headers: { 'Content-Type': 'image/png' } });
