// The favicon is the small-size simplified logo drawn by tools/logo.py.
import type { APIRoute } from 'astro';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

export const GET: APIRoute = () =>
  new Response(readFileSync(resolve(process.cwd(), '../docs/logo/tvslim-petit.svg')), { headers: { 'Content-Type': 'image/svg+xml' } });
