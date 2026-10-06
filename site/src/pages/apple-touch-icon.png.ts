// L'icône d'un raccourci sur l'écran d'accueil d'un téléphone : carrée, le système en arrondit les coins.
import type { APIRoute } from 'astro';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

export const GET: APIRoute = () =>
  new Response(readFileSync(resolve(process.cwd(), '../docs/logo/tvslim-carre-180.png')), { headers: { 'Content-Type': 'image/png' } });
