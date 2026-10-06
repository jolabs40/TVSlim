// L'image d'aperçu des liens partagés : celle du dépôt GitHub, sans copie à tenir à jour.
import type { APIRoute } from 'astro';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

export const GET: APIRoute = () =>
  new Response(readFileSync(resolve(process.cwd(), '../docs/social-preview.png')), { headers: { 'Content-Type': 'image/png' } });
