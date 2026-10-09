// llms.txt: summary for AI assistants, with the key facts and pages.
// Claim nothing here that is not on the site or in the READMEs.
import type { APIRoute } from 'astro';
import { catalogue, nomMarque, ORDRE_MARQUES } from '../data/catalogue';
import { publications } from '../data/releases';
import { SITE, REPO, STARTLIGHT, KOFI, CONTACT } from '../site';

export const GET: APIRoute = async () => {
  const cat = catalogue('en');
  const { windows, android } = await publications();
  const marques = ORDRE_MARQUES.filter((m) => cat.entrees.some((e) => e.marque === m)).map((m) => nomMarque(m, 'en'));
  const texte = `# TV Slim

> TV Slim is a free, open-source (Apache 2.0) debloater for Android TV and Google TV. From a Windows PC or an Android phone, it connects to the television over the home network with ADB — no root — and disables the advertising, the telemetry, the shop-demo leftovers and the preinstalled apps. Nothing is uninstalled: every package is disabled with \`pm disable-user --user 0\`, written to a log with the command that undoes it, and can be re-enabled at any time.

Key facts:
- Platforms: Windows 10/11 (64-bit) app, and TV Slim Remote for Android 8+ phones. An optional app for the TV shows a QR code to connect and keeps the TV reachable during standby.
- Works on any Android TV or Google TV device whose developer options offer network debugging (port 5555). Tested on a TCL Smart TV Pro (65C89K, Google TV, Android 14) and an NVIDIA Shield TV.
- Safeguards: a protected list (${cat.proteges.length} packages whose absence causes boot loops or breaks HDMI inputs or the remote) is always refused; the factory home screen cannot be disabled until another launcher is installed.
- After a system update re-enables packages or the Google TV home screen, TV Slim notices at the next connection and puts everything back after one confirmation.
- Privacy: no account, no telemetry; the app talks only to the TV and to GitHub for updates.
- Latest versions: Windows ${windows?.version ?? '(see releases)'}, Android ${android?.version ?? '(see releases)'}. Distributed on GitHub, not on the Play Store.
- The recommended ad-free home screen is Startlight Launcher, by the same author: ${STARTLIGHT}

## Pages
- [Home](${SITE}/): what TV Slim does, FAQ
- [Download](${SITE}/download/): Windows installer and portable, Android APKs, certificate fingerprint
- [Setup guide](${SITE}/guide/): turn on developer options and network debugging on an Android TV or Google TV, then connect
- [Package encyclopedia](${SITE}/packages/): ${cat.entrees.length} packages described (${marques.join(', ')}) with risk levels and side effects, and the ${cat.proteges.length} packages never to disable
- [Privacy](${SITE}/privacy/)
- French version: ${SITE}/fr/

## Links
- Source code and releases: ${REPO}
- Support: ${KOFI}
- Contact: ${CONTACT}
`;
  return new Response(texte, { headers: { 'Content-Type': 'text/plain; charset=utf-8' } });
};
