// Link preview image: the GitHub repository's social preview, served from docs/ so there is no copy.
import type { APIRoute } from 'astro';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

export const GET: APIRoute = () =>
  new Response(readFileSync(resolve(process.cwd(), '../docs/social-preview.png')), { headers: { 'Content-Type': 'image/png' } });
