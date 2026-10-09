// The app catalogue, read from the repository at build time, so the package pages say nothing TV Slim
// does not say itself. The French overlay applies like `Catalogue.traduit()`: a missing translation falls
// back to English.

import base from '../../../TVSlim/core/src/main/assets/catalogue.json';
import frJson from '../../../TVSlim/core/src/main/assets/catalogue-fr.json';
import type { Lang } from '../site';

export type RiskLevel = 'aucun' | 'faible' | 'moyen' | 'eleve';

export interface PackageEntry {
  packageName: string;
  name: string;
  description: string;
  category: string;
  risk: RiskLevel;
  brand: string;
  sideEffect?: string;
  sizeMb?: number;
  tested: boolean;
}

export interface ProtectedPackage {
  packageName: string;
  reason: string;
  brand: string;
}

export interface Profile {
  id: string;
  name: string;
  description: string;
  categories: string[];
}

/** An entry as written in catalogue.json, whose keys stay in French. */
interface RawEntry {
  paquet: string;
  nom: string;
  description: string;
  categorie: string;
  risque?: RiskLevel;
  marque?: string;
  effetDeBord?: string;
  tailleMo?: number;
  eprouve?: boolean;
}

const frOverlay = frJson as unknown as {
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

export const brandOf = (packageName: string) => PREFIXES.find(([re]) => re.test(packageName))?.[1] ?? 'Third party';

/** Display order: device makers first, Android and third parties last. */
export const BRAND_ORDER = ['TCL', 'Philips', 'Sony', 'NVIDIA', 'Xiaomi', 'MediaTek', 'Google', 'AOSP', 'Third party'];

export const brandName = (brand: string, lang: Lang) =>
  brand === 'AOSP' ? 'Android (AOSP)' : brand === 'Third party' ? (lang === 'fr' ? 'Éditeurs tiers' : 'Third-party apps') : brand;

export function catalogue(lang: Lang) {
  const fr = lang === 'fr';
  const entries: PackageEntry[] = (base.entrees as RawEntry[]).map((e) => {
    const t = fr ? frOverlay.entrees[e.paquet] ?? {} : {};
    return {
      packageName: e.paquet,
      name: t.nom ?? e.nom,
      description: t.description ?? e.description,
      category: e.categorie,
      risk: e.risque ?? 'faible',
      brand: e.marque || brandOf(e.paquet),
      sideEffect: t.effetDeBord ?? e.effetDeBord,
      sizeMb: e.tailleMo,
      tested: e.eprouve ?? true,
    };
  });
  const protectedPackages: ProtectedPackage[] = base.proteges.map((p) => ({
    packageName: p.paquet,
    reason: (fr ? frOverlay.proteges[p.paquet] : undefined) ?? p.raison,
    brand: brandOf(p.paquet),
  }));
  const categories = new Map(
    base.categories.map((c) => [c.id, (fr ? frOverlay.categories[c.id] : undefined) ?? c.nom]),
  );
  const profiles: Profile[] = base.profils.map((p) => ({
    id: p.id,
    name: (fr ? frOverlay.profils[p.id]?.nom : undefined) ?? p.nom,
    description: (fr ? frOverlay.profils[p.id]?.description : undefined) ?? p.description,
    categories: p.categories,
  }));
  /** The profile that selects this package; none for an untested entry. */
  const profileOf = (e: PackageEntry) => (e.tested ? profiles.find((p) => p.categories.includes(e.category)) : undefined);
  return { entries, protectedPackages, categories, profiles, profileOf };
}

/** Every package id that gets a page: entries and protected packages. */
export const allPackageIds = () => [...base.entrees.map((e) => e.paquet), ...base.proteges.map((p) => p.paquet)];
