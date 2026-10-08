// Ce que tout le site partage : adresses, chemins par langue, et les textes de l'en-tête et du pied.
// Les faits (fonctions, garde-fous, confidentialité) viennent des README : rien d'affirmé qui ne
// soit vrai, et les mêmes libellés que l'interface.

export type Lang = 'en' | 'fr';
export const LANGS: Lang[] = ['en', 'fr'];

export const SITE = 'https://tvslim.app';
export const BRAND = 'TV Slim';
export const REPO = 'https://github.com/jolabs40/TVSlim';
export const RELEASES = `${REPO}/releases`;
export const ANDROID_RELEASES = `${REPO}/releases?q=android&expanded=true`;
export const ISSUE_DEVICE = `${REPO}/issues/new?template=nouvel-appareil.yml`;
export const KOFI = 'https://ko-fi.com/jolabs40';
/** L'adresse de contact, la même que dans « À propos » des deux applications. */
export const CONTACT = 'support@tvslim.app';
export const STARTLIGHT = 'https://startlightlauncher.com';
export const OG_IMAGE = `${SITE}/og.png`;
/** L'éditeur, le même @id sur tvslim.app et startlightlauncher.com : les deux sites parlent du même. */
export const ORG_ID = 'https://jolabs40.net/#org';
/** Empreinte du certificat des deux APK — `empreinteCertificat` de TVSlim/gradle.properties. */
export const CERT_SHA256 =
  '42:96:BD:A0:51:8F:1A:59:1D:66:91:4E:6B:AB:7B:2F:0D:00:30:BE:6D:6C:79:8E:F3:AE:D5:D2:79:CF:96:F8';

/** Le chemin d'une page dans une langue : l'anglais à la racine, le français sous /fr. */
export const path = (lang: Lang, p: string) => (lang === 'en' ? p : `/fr${p}`);
export const url = (lang: Lang, p: string) => SITE + path(lang, p);

export const ui = {
  en: {
    skip: 'Skip to content',
    nav: { download: 'Download', guide: 'Setup guide', packages: 'Packages', github: 'GitHub', kofi: 'Support TV Slim on Ko-fi' },
    switchTo: 'Français',
    footer: {
      tagline: 'Free, open-source debloater for Android TV and Google TV.',
      privacy: 'Privacy',
      license: 'Apache 2.0 licence',
      support: 'Buy me a coffee',
      disclaimer:
        'TV Slim is not affiliated with any television, TV box or launcher maker. Brand names belong to their owners and are used only to identify devices and packages. Every change TV Slim makes is reversible, but you remain in charge of what you disable.',
    },
    risk: { aucun: 'No known risk', faible: 'Low risk', moyen: 'Medium risk', eleve: 'High risk' },
    protected: 'Never disable',
    untested: 'Not tested',
    meta: {
      title: 'TV Slim — Debloat Android TV & Google TV, without root',
      description:
        'Free, open-source debloater for Android TV and Google TV. Disable the ads, the telemetry and the preinstalled apps from a Windows PC or an Android phone — no root, every change undoable.',
    },
  },
  fr: {
    skip: 'Aller au contenu',
    nav: { download: 'Télécharger', guide: 'Guide', packages: 'Paquets', github: 'GitHub', kofi: 'Soutenir TV Slim sur Ko-fi' },
    switchTo: 'English',
    footer: {
      tagline: 'Outil de débloat libre et gratuit pour Android TV et Google TV.',
      privacy: 'Confidentialité',
      license: 'Licence Apache 2.0',
      support: 'M’offrir un café',
      disclaimer:
        'TV Slim n’est affilié à aucun fabricant de téléviseurs, de box ou de launchers. Les noms de marque appartiennent à leurs propriétaires et ne servent qu’à identifier les appareils et les paquets. Chaque changement de TV Slim est réversible, mais vous restez responsable de ce que vous désactivez.',
    },
    risk: { aucun: 'Aucun risque connu', faible: 'Risque faible', moyen: 'Risque moyen', eleve: 'Risque élevé' },
    protected: 'Ne jamais désactiver',
    untested: 'Non éprouvé',
    meta: {
      title: 'TV Slim — Débloater Android TV et Google TV, sans root',
      description:
        'Outil de débloat libre et gratuit pour Android TV et Google TV. Désactivez la publicité, la télémétrie et les applications préinstallées depuis un PC Windows ou un téléphone Android — sans root, chaque changement réversible.',
    },
  },
} as const;
