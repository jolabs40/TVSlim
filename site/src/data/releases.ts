// Latest releases, read from GitHub at build time so the download links point at the actual files.
// Without network, the links fall back to the releases list.
// A new version only shows up here once the site is rebuilt (deploy.sh).

export interface ReleaseFile {
  name: string;
  url: string;
  size: number;
}

export interface Release {
  tag: string;
  version: string;
  date: string;
  url: string;
  files: ReleaseFile[];
}

interface GithubRelease {
  tag_name: string;
  html_url: string;
  published_at: string;
  draft: boolean;
  prerelease: boolean;
  assets: { name: string; browser_download_url: string; size: number }[];
}

const compareVersions = (a: string, b: string) => {
  const x = a.split('.').map(Number), y = b.split('.').map(Number);
  for (let i = 0; i < 3; i++) if ((x[i] ?? 0) !== (y[i] ?? 0)) return (x[i] ?? 0) - (y[i] ?? 0);
  return 0;
};

let pending: Promise<{ windows?: Release; android?: Release }> | undefined;

export function latestReleases() {
  pending ??= (async () => {
    try {
      const headers: Record<string, string> = { Accept: 'application/vnd.github+json', 'User-Agent': 'tvslim.app' };
      if (process.env.GITHUB_TOKEN) headers.Authorization = `Bearer ${process.env.GITHUB_TOKEN}`;
      const response = await fetch('https://api.github.com/repos/jolabs40/TVSlim/releases?per_page=30', { headers });
      if (!response.ok) throw new Error(`GitHub : HTTP ${response.status}`);
      const releases = ((await response.json()) as GithubRelease[]).filter((r) => !r.draft && !r.prerelease);
      const latest = (prefix: string): Release | undefined => {
        const r = releases
          .filter((r) => r.tag_name.startsWith(prefix))
          .sort((a, b) => compareVersions(b.tag_name.slice(prefix.length), a.tag_name.slice(prefix.length)))[0];
        if (!r) return undefined;
        return {
          tag: r.tag_name,
          version: r.tag_name.slice(prefix.length),
          date: r.published_at.slice(0, 10),
          url: r.html_url,
          files: r.assets
            .filter((f) => !f.name.endsWith('.idsig'))
            .map((f) => ({ name: f.name, url: f.browser_download_url, size: f.size })),
        };
      };
      return { windows: latest('windows-v'), android: latest('android-v') };
    } catch (error) {
      console.warn(`Publications GitHub illisibles, liens génériques : ${error}`);
      return {};
    }
  })();
  return pending;
}

export const findFile = (release: Release | undefined, test: (name: string) => boolean) =>
  release?.files.find((f) => test(f.name));

export const formatSize = (bytes: number, lang: 'en' | 'fr') => {
  const n = (bytes / 1024 / 1024).toFixed(bytes < 10 * 1024 * 1024 ? 1 : 0);
  return lang === 'fr' ? `${n.replace('.', ',')} Mo` : `${n} MB`;
};
