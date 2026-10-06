// Les dernières publications, lues sur GitHub au moment de construire le site : les liens de
// téléchargement visent les vrais fichiers. Sans réseau, la page renvoie vers la liste des publications.
// ⚠️ Une nouvelle version n'apparaît ici qu'après une reconstruction du site (deploy.sh).

export interface Fichier {
  nom: string;
  url: string;
  taille: number;
}

export interface Publication {
  tag: string;
  version: string;
  date: string;
  url: string;
  fichiers: Fichier[];
}

interface ReleaseGithub {
  tag_name: string;
  html_url: string;
  published_at: string;
  draft: boolean;
  prerelease: boolean;
  assets: { name: string; browser_download_url: string; size: number }[];
}

const comparer = (a: string, b: string) => {
  const x = a.split('.').map(Number), y = b.split('.').map(Number);
  for (let i = 0; i < 3; i++) if ((x[i] ?? 0) !== (y[i] ?? 0)) return (x[i] ?? 0) - (y[i] ?? 0);
  return 0;
};

let enCours: Promise<{ windows?: Publication; android?: Publication }> | undefined;

export function publications() {
  enCours ??= (async () => {
    try {
      const entetes: Record<string, string> = { Accept: 'application/vnd.github+json', 'User-Agent': 'tvslim.app' };
      if (process.env.GITHUB_TOKEN) entetes.Authorization = `Bearer ${process.env.GITHUB_TOKEN}`;
      const reponse = await fetch('https://api.github.com/repos/jolabs40/TVSlim/releases?per_page=30', { headers: entetes });
      if (!reponse.ok) throw new Error(`GitHub : HTTP ${reponse.status}`);
      const toutes = ((await reponse.json()) as ReleaseGithub[]).filter((r) => !r.draft && !r.prerelease);
      const derniere = (prefixe: string): Publication | undefined => {
        const r = toutes
          .filter((r) => r.tag_name.startsWith(prefixe))
          .sort((a, b) => comparer(b.tag_name.slice(prefixe.length), a.tag_name.slice(prefixe.length)))[0];
        if (!r) return undefined;
        return {
          tag: r.tag_name,
          version: r.tag_name.slice(prefixe.length),
          date: r.published_at.slice(0, 10),
          url: r.html_url,
          fichiers: r.assets
            .filter((f) => !f.name.endsWith('.idsig'))
            .map((f) => ({ nom: f.name, url: f.browser_download_url, taille: f.size })),
        };
      };
      return { windows: derniere('windows-v'), android: derniere('android-v') };
    } catch (erreur) {
      console.warn(`Publications GitHub illisibles, liens génériques : ${erreur}`);
      return {};
    }
  })();
  return enCours;
}

export const fichier = (p: Publication | undefined, test: (nom: string) => boolean) => p?.fichiers.find((f) => test(f.nom));

export const mo = (octets: number, lang: 'en' | 'fr') => {
  const n = (octets / 1024 / 1024).toFixed(octets < 10 * 1024 * 1024 ? 1 : 0);
  return lang === 'fr' ? `${n.replace('.', ',')} Mo` : `${n} MB`;
};
