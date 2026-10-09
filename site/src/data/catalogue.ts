// The app catalogue, read from the repository at build time, so the package pages say nothing TV Slim
// does not say itself. The French overlay applies like `Catalogue.traduit()`: a missing translation falls
// back to English.

import base from '../../../TVSlim/core/src/main/assets/catalogue.json';
import fr from '../../../TVSlim/core/src/main/assets/catalogue-fr.json';
import type { Lang } from '../site';

export type Risque = 'aucun' | 'faible' | 'moyen' | 'eleve';

export interface Paquet {
  paquet: string;
  nom: string;
  description: string;
  categorie: string;
  risque: Risque;
  marque: string;
  effetDeBord?: string;
  tailleMo?: number;
  eprouve: boolean;
}

export interface Protege {
  paquet: string;
  raison: string;
  marque: string;
}

export interface Profil {
  id: string;
  nom: string;
  description: string;
  categories: string[];
}

interface EntreeBrute {
  paquet: string;
  nom: string;
  description: string;
  categorie: string;
  risque?: Risque;
  marque?: string;
  effetDeBord?: string;
  tailleMo?: number;
  eprouve?: boolean;
}

const surcharge = fr as unknown as {
  entrees: Record<string, { nom?: string; description?: string; effetDeBord?: string }>;
  categories: Record<string, string>;
  profils: Record<string, { nom?: string; description?: string }>;
  proteges: Record<string, string>;
};

/** Protected packages have no brand in the catalogue, so it is derived from the package name. */
const PREFIXES: [RegExp, string][] = [
  [/^com\.tcl\.|^com\.tvos\.|^com\.tpa\./, 'TCL'],
  [/^org\.droidtv\.|^com\.tpv\./, 'Philips'],
  [/^com\.nvidia\./, 'NVIDIA'],
  [/^com\.sony\./, 'Sony'],
  [/^com\.mediatek\./, 'MediaTek'],
  [/^com\.xiaomi\.|^mitv\.|^com\.mitv\./, 'Xiaomi'],
  [/^com\.google\./, 'Google'],
  [/^com\.android\.|^android$/, 'AOSP'],
];

export const marqueDe = (paquet: string) => PREFIXES.find(([re]) => re.test(paquet))?.[1] ?? 'Third party';

/** Display order: device makers first, Android and third parties last. */
export const ORDRE_MARQUES = ['TCL', 'Philips', 'Sony', 'NVIDIA', 'Xiaomi', 'MediaTek', 'Google', 'AOSP', 'Third party'];

export const nomMarque = (marque: string, lang: Lang) =>
  marque === 'AOSP' ? 'Android (AOSP)' : marque === 'Third party' ? (lang === 'fr' ? 'Éditeurs tiers' : 'Third-party apps') : marque;

export function catalogue(lang: Lang) {
  const fr = lang === 'fr';
  const entrees: Paquet[] = (base.entrees as EntreeBrute[]).map((e) => {
    const t = fr ? surcharge.entrees[e.paquet] ?? {} : {};
    return {
      paquet: e.paquet,
      nom: t.nom ?? e.nom,
      description: t.description ?? e.description,
      categorie: e.categorie,
      risque: e.risque ?? 'faible',
      marque: e.marque || marqueDe(e.paquet),
      effetDeBord: t.effetDeBord ?? e.effetDeBord,
      tailleMo: e.tailleMo,
      eprouve: e.eprouve ?? true,
    };
  });
  const proteges: Protege[] = base.proteges.map((p) => ({
    paquet: p.paquet,
    raison: (fr ? surcharge.proteges[p.paquet] : undefined) ?? p.raison,
    marque: marqueDe(p.paquet),
  }));
  const categories = new Map(
    base.categories.map((c) => [c.id, (fr ? surcharge.categories[c.id] : undefined) ?? c.nom]),
  );
  const profils: Profil[] = base.profils.map((p) => ({
    id: p.id,
    nom: (fr ? surcharge.profils[p.id]?.nom : undefined) ?? p.nom,
    description: (fr ? surcharge.profils[p.id]?.description : undefined) ?? p.description,
    categories: p.categories,
  }));
  /** The profile that selects this package; none for an untested entry. */
  const profilDe = (e: Paquet) => (e.eprouve ? profils.find((p) => p.categories.includes(e.categorie)) : undefined);
  return { entrees, proteges, categories, profils, profilDe };
}

/** Every package id that gets a page: entries and protected packages. */
export const tousLesPaquets = () => [...base.entrees.map((e) => e.paquet), ...base.proteges.map((p) => p.paquet)];
