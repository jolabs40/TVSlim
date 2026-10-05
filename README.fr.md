<p align="center">
  <img src="TVSlim%20Windows/packaging/tvslim-256.png" alt="Logo de TV Slim" width="112">
</p>

<h1 align="center">TV Slim</h1>

<p align="center">
  <b>Allégez votre téléviseur Android TV ou Google TV — sans root, et chaque changement s'annule.</b><br>
  Coupez la publicité, la télémétrie et les applications préinstallées qui ralentissent votre
  Smart TV, depuis un PC Windows.
</p>

<p align="center">
  <a href="https://github.com/jolabs40/TVSlim/releases/latest"><img src="https://img.shields.io/github/v/release/jolabs40/TVSlim?label=derni%C3%A8re%20version&color=2e7d5b" alt="Dernière version"></a>
  <a href="https://github.com/jolabs40/TVSlim/releases/latest"><img src="https://img.shields.io/badge/Windows-10%20%7C%2011-0078D4" alt="Windows 10 et 11"></a>
  <a href="#questions-fréquentes"><img src="https://img.shields.io/badge/root-inutile-7fd8aa" alt="Sans root"></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/jolabs40/TVSlim?color=blue" alt="Licence Apache-2.0"></a>
</p>

<p align="center">
  <a href="https://github.com/jolabs40/TVSlim/releases/latest"><b>⬇ Télécharger pour Windows</b></a>
  &nbsp;·&nbsp; <a href="#démarrer-en-trois-étapes">Démarrer</a>
  &nbsp;·&nbsp; <a href="#questions-fréquentes">Questions fréquentes</a>
  &nbsp;·&nbsp; <a href="README.md">English version</a>
</p>

![TV Slim pour Windows connecté à un téléviseur TCL Google TV : modèle, Android 14, mémoire, 55 paquets désactivés, et les launchers installés — Startlight et Projectivy, l'accueil Google TV désactivé](docs/captures/tv-slim-windows-television.png)

**TV Slim est un outil de débloat libre et gratuit pour Android TV et Google TV.** Il se connecte à
votre téléviseur par le réseau de la maison, en ADB, montre ce qui y est réellement installé, et
désactive la publicité, la télémétrie, les restes du mode démonstration et les applications
préinstallées que vous n'avez jamais demandées — sur un TCL, un Philips, un NVIDIA Shield, une
Xiaomi Mi Box et d'autres. Rien n'est désinstallé, et rien n'est à installer sur le téléviseur :
chaque paquet est *désactivé*, inscrit au journal avec la commande qui l'annule, et se réactive à
tout moment.

## Pourquoi TV Slim

- 🛡️ **Réversible par construction** — les paquets sont désactivés, jamais désinstallés. Annulez une
  ligne du journal, ou restaurez tout d'un clic.
- 🔌 **Sans root, rien à installer sur le téléviseur** — de l'ADB tout simple, en Wi-Fi ou en
  Ethernet. TV Slim trouve seul votre téléviseur sur le réseau.
- 📖 **Il dit ce que vous désactivez** — chaque paquet a sa description, son niveau de risque et ses
  effets de bord connus, avec des profils prêts à l'emploi : publicité et télémétrie, assistants
  vocaux, services de streaming préinstallés, applications du constructeur.
- 🧱 **Des garde-fous qu'on ne contourne pas d'un clic** — les paquets dont l'absence provoquerait
  une boucle de redémarrage ou casserait la télécommande sont refusés, et l'accueil d'usine reste
  tant qu'aucun autre n'est installé.
- 🔁 **Tient tête aux mises à jour** — quand une mise à jour système rallume la publicité ou l'accueil
  Google TV, TV Slim le voit à la connexion suivante et remet tout en place après une confirmation.
- 🏠 **Un accueil sans publicité** — voyez les launchers installés, passez à Startlight, à Projectivy
  ou à un autre, et écartez l'accueil d'usine.
- 🧰 **Tout ce que permet ADB, avec des boutons** — applications (ouvrir, forcer l'arrêt, désinstaller
  les vôtres), fichiers dans les deux sens par glisser-déposer, installation d'APK, captures,
  vidéo de l'écran, miroir en direct, mémoire et stockage.
- 🔒 **Confidentiel** — pas de compte, pas de télémétrie. TV Slim parle à votre téléviseur et à GitHub
  pour les mises à jour, à rien d'autre.

## Captures

| Il trouve le téléviseur sur le réseau | Les paquets désactivés, expliqués | Chaque application, avec son vrai nom |
|:---:|:---:|:---:|
| ![TV Slim trouve un TCL Smart TV Pro et un NVIDIA Shield sur le réseau local](docs/captures/tv-slim-find-tv-on-network.png) | ![L'onglet Paquets : recommandations TCL, Channel+, Privacy Sandbox et télémétrie Google désactivés, chacun avec un bouton Réactiver](docs/captures/tv-slim-packages.png) | ![L'onglet Applications : Prime Video désactivé, Projectivy, SmartTube et Spotify installés, avec Ouvrir, Forcer l'arrêt et Désinstaller](docs/captures/tv-slim-apps.png) |

## Démarrer en trois étapes

**1. Téléchargez TV Slim pour Windows** sur la **[page des publications](https://github.com/jolabs40/TVSlim/releases/latest)** :

| Fichier | Pour |
|---|---|
| `TVSlim-Windows-x.y.z.msi` | **Recommandé.** Installe TV Slim pour votre compte — sans droits d'administrateur — et le tient à jour. |
| `TVSlim-Windows-x.y.z-portable.zip` | S'utilise sans installation : décompressez, puis ouvrez `TV Slim.exe`. |

Windows 10 ou 11, 64 bits. Java est inclus : il n'y a rien d'autre à installer.

> **« Windows a protégé votre ordinateur » ?** TV Slim est un logiciel libre et gratuit, dont
> l'installateur n'est pas signé par un certificat payant : SmartScreen peut vous avertir la
> première fois. Cliquez sur **Informations complémentaires**, puis sur **Exécuter quand même**.
> `SHA256SUMS.txt`, publié à côté, permet de vérifier que le fichier est authentique.

**2. Préparez le téléviseur (une seule fois).**

1. Sur le téléviseur, ouvrez **Paramètres → À propos** (parfois sous *Système* ou *Préférences de
   l'appareil*) et appuyez sept fois sur OK sur le **numéro de build**. Les options pour les
   développeurs apparaissent.
2. Dans les **options pour les développeurs**, activez le débogage réseau — selon le téléviseur, il
   s'appelle *Débogage réseau*, *Débogage ADB* ou *Débogage USB*.

**3. Connectez-vous et allégez-le.** Lancez TV Slim sur un PC branché au même réseau. Il trouve le
téléviseur tout seul en quelques secondes ; sinon, saisissez son adresse IP (indiquée dans
*Paramètres → Réseau*). À la première connexion, le téléviseur demande d'**autoriser le débogage
depuis cet ordinateur** : cochez *Toujours autoriser* et acceptez à la télécommande — il ne le
redemandera plus, même après un redémarrage. Ouvrez ensuite **Paquets**, choisissez un profil et
cliquez sur **Appliquer**.

## Compatibilité

- **Tout appareil Android TV ou Google TV** dont les options pour les développeurs proposent le
  débogage réseau : téléviseurs (TCL, Philips, Sony, Hisense, Sharp, Toshiba, Grundig, Haier,
  Panasonic…) et box (NVIDIA Shield TV, Xiaomi Mi Box, Freebox, boîtiers Google TV…). TV Slim
  reconnaît le fabricant et affiche son logo.
- **Le catalogue** décrit les paquets de Google TV et d'Android TV, et ceux de TCL, Philips, NVIDIA et
  Xiaomi. Sur les autres marques, les paquets que TV Slim ne connaît pas encore sont listés à part,
  sans rien proposer d'en faire, et *Proposer au catalogue* envoie leur inventaire en quelques clics.
- **Éprouvé** sur un TCL Smart TV Pro (65C89K, Google TV, Android 14) et un NVIDIA Shield TV.
- **Côté PC** : Windows 10 ou 11, 64 bits, sur le même réseau local que le téléviseur. Une
  application pour téléphone Android, TV Slim Remote, est dans ce dépôt ; elle n'est pas encore
  publiée.

## Ce qu'on peut faire

- **Téléviseur** — modèle et fabricant, version d'Android, mémoire, paquets actifs et désactivés ;
  l'écran d'accueil et les launchers installés, reconnus à leur logo, l'accueil d'usine compris même
  désactivé ; le remplaçant recommandé, Startlight Launcher (bientôt sur le Play Store) ; accorder
  les permissions privilégiées dont certaines applications ont besoin (`WRITE_SECURE_SETTINGS`,
  `DUMP`…) ; installer un APK sur le téléviseur — glissé dans la fenêtre ou choisi dans vos fichiers —
  après une confirmation qui montre le paquet, sa version et ce qu'il remplace ; envoyer une commande
  ADB de votre cru et lire sa sortie. Si le téléviseur a défait une partie de vos réglages — après
  une mise à jour système, le plus souvent —, une carte le dit, et *Tout remettre* les réapplique.
- **Paquets** — les paquets connus réellement présents sur *votre* téléviseur, avec leur niveau de
  risque, leur taille, leur description et leurs effets de bord connus ; des profils prêts à
  l'emploi (publicité et télémétrie, assistants vocaux, services de streaming préinstallés…) ;
  application en une fois, après une confirmation qui liste les effets de bord. Sauvegarder la
  configuration du téléviseur — paquets et écran d'accueil — dans un fichier, et la réinjecter plus
  tard, sur le même téléviseur ou sur un autre du même modèle. Chaque paquet porte une icône
  d'origine : Android, constructeur ou autre. Ceux que TV Slim ne connaît pas encore sont listés à
  part, sans rien proposer d'en faire ; leur inventaire s'exporte — emplacement, droits du système,
  services sensibles déclarés, mémoire et stockage — pour enrichir le catalogue. Quand certains
  viennent du constructeur, *Proposer au catalogue* l'exporte, puis ouvre
  [un formulaire GitHub](https://github.com/jolabs40/TVSlim/issues/new?template=nouvel-appareil.yml)
  pour l'envoyer. Un paquet décrit d'après un tel envoi est marqué *non éprouvé* : aucun profil ne
  le coche.
- **Applications** — chaque application du menu et chaque application que vous avez installée, avec
  son vrai nom et son icône : l'ouvrir sur le téléviseur, forcer son arrêt, la désactiver ou la
  réactiver (selon les mêmes règles que l'onglet Paquets), ou désinstaller une application que vous
  avez installée vous-même. Android ne donne ni les noms ni les icônes par ADB : une petite aide
  (quelques Ko) est copiée dans `/data/local/tmp` le temps de la lecture, lancée, puis effacée — rien
  n'est installé —, et ce qu'elle lit est gardé sur votre PC pour la fois suivante.
- **Mémoire** — mémoire vive : ce que coûte réellement chaque processus, et la comparaison
  avant/après depuis la première visite. Stockage : l'espace libre et occupé, et les applications
  les plus lourdes.
- **Fichiers** — parcourir les dossiers du téléviseur comme dans l'Explorateur : stockage interne,
  Téléchargements, Films, clé USB ou carte SD branchée… et y déposer des fichiers ou un dossier
  entier, sous-dossiers compris, glissés dans la fenêtre ou choisis dans vos fichiers. Sous Windows,
  copier un fichier ou un dossier vers le PC, et supprimer ce qui encombre le téléviseur — au survol
  d'une ligne ou par un clic droit. Les droits sont ceux d'ADB : le stockage partagé s'écrit,
  `Android/data` compris ; les dossiers du système, non.
- **Écran** — depuis la barre du haut de chaque onglet : une capture d'écran du téléviseur,
  enregistrée dans *Images\TV Slim*, avec un aperçu pour la copier. Une vidéo de l'écran, enregistrée
  par le téléviseur lui-même puis copiée dans *Vidéos\TV Slim* et effacée du téléviseur — sans son,
  que son enregistreur ne capture jamais, et 3 minutes au plus avant Android 14 ; et le miroir de
  l'écran en direct dans sa propre fenêtre, par [scrcpy](https://github.com/Genymobile/scrcpy),
  téléchargé depuis GitHub la première fois s'il n'est pas installé. Enregistrer ferme le miroir. Les
  vidéos protégées (Netflix, la plupart des chaînes) sortent en noir : c'est le téléviseur qui le
  décide.
- **Journal** — chaque action, avec la commande qui l'annule ; annuler une seule ligne ou tout
  restaurer ; export en Markdown.

### Garde-fous

- Rien de ce qui est livré avec le téléviseur n'est jamais désinstallé : uniquement `pm disable-user
  --user 0`, annulé par `pm enable`. Seule exception, une application que vous avez installée
  vous-même, que l'onglet Applications peut désinstaller après confirmation — TV Slim vérifie juste
  avant qu'il ne s'agit pas d'un paquet du système.
- Une liste de paquets protégés est refusée quelle que soit la sélection — ceux dont l'absence
  provoque une boucle de redémarrage ou casse la télécommande, par exemple.
- L'écran d'accueil d'usine ne se désactive pas tant qu'aucun launcher tiers n'est installé.
- Un APK ne s'installe qu'après confirmation, et jamais en désinstallant ce qui est en place : une
  application signée d'une autre clé n'est pas touchée.
- Rien n'est déposé sur le téléviseur avant une confirmation qui dit ce qui sera remplacé. Rien n'y
  est effacé avant une confirmation qui dit ce qui disparaît — pour un dossier, combien de fichiers et
  combien de gigaoctets —, et un stockage entier (stockage interne, clé USB, dossier `Android`) ne
  s'efface jamais d'un bloc. Une copie vers le PC arrêtée en route ne laisse aucun fichier tronqué.
- Seule exception, la carte **Commande ADB** : ce qu'on y tape échappe à ces garde-fous et ne s'annule
  pas depuis TV Slim. La carte le dit, et chaque commande est consignée au journal.

## Questions fréquentes

### Comment enlever la publicité de l'accueil de mon Android TV ou Google TV ?

La publicité vient du service de recommandations du fabricant et de l'écran d'accueil d'usine. Dans
TV Slim, le profil **Publicité, télémétrie et mode démo** désactive le premier — sur un TCL,
*Recommandations TCL* et *Channel+*, par exemple. Pour un accueil sans aucune publicité, installez un
autre launcher, choisissez-le dans l'onglet **Téléviseur**, puis appliquez le profil **Remplacement
de l'écran d'accueil**, qui désactive l'accueil Google TV : TV Slim ne le permet qu'une fois le
remplaçant en place.

### Débloater son téléviseur, est-ce sans danger ? Peut-on le rendre inutilisable ?

TV Slim ne fait que *désactiver* des paquets, et refuse ceux dont l'absence provoquerait une boucle
de redémarrage ou casserait la télécommande. Chaque changement est inscrit au journal avec la
commande qui l'annule, et **Tout restaurer** ramène le téléviseur à son point de départ. En dernier
recours, une réinitialisation d'usine du téléviseur réactive tous les paquets — et *Réinjecter une
config* réapplique ensuite vos choix.

### Faut-il rooter le téléviseur ?

Non. TV Slim passe par ADB, le pont de débogage que tout appareil Android TV ou Google TV porte
d'origine. Il suffit d'activer le débogage réseau dans les options pour les développeurs.

### Installe-t-il quelque chose sur le téléviseur ?

Non. L'onglet Applications copie une petite aide dans `/data/local/tmp` pour lire les noms et les
icônes, la lance, puis l'efface aussitôt. Un APK ne s'installe sur le téléviseur que si vous le
demandez.

### Une mise à jour système a ramené la publicité et l'accueil Google TV. Que faire ?

Reconnectez TV Slim. Il compare le téléviseur à son journal : si des paquets que vous aviez
désactivés tournent à nouveau, ou si l'accueil d'usine a repris la main, une carte le dit, et *Tout
remettre* réapplique vos changements après une confirmation.

### Quelle différence avec la désinstallation de paquets par ADB ?

`pm uninstall --user 0`, qu'emploient beaucoup de tutoriels et d'outils de débloat pour téléphones,
retire l'application pour votre utilisateur ; la récupérer demande de trouver la bonne commande, et
une réinitialisation d'usine peut être la seule issue après une erreur. TV Slim ne le fait jamais :
il désactive, explique chaque paquet avant que vous n'y touchiez, tient un journal qui s'annule
ligne à ligne, et connaît les paquets auxquels il ne faut jamais toucher sur un téléviseur.

### Est-ce gratuit ?

Oui — gratuit, libre sous licence Apache 2.0, sans publicité, sans compte et sans télémétrie. S'il
vous a rendu service, vous pouvez [m'offrir un café](#soutenir-le-projet).

## Mises à jour

Au démarrage, TV Slim demande à GitHub s'il existe une nouvelle version — désactivable dans
**À propos**. Rien ne se télécharge avant un clic sur **Installer**. Chaque installateur est signé
par une clé Ed25519 : TV Slim vérifie la signature avant de l'exécuter, refuse tout le reste, puis
redémarre tout seul.

Chacun peut refaire cette vérification sur un fichier téléchargé, avec un JDK 17 ou plus récent,
depuis une copie de ce dépôt et avec le fichier `.msi.sig` à côté de l'installateur :

```sh
java "TVSlim Windows/outils/VerifierMiseAJour.java" TVSlim-Windows-x.y.z.msi x.y.z "TVSlim Windows/gradle.properties"
```

## Confidentialité

TV Slim parle à votre téléviseur sur votre réseau local, et à GitHub pour chercher les mises à
jour — et, la première fois que vous ouvrez le miroir sans scrcpy installé, pour le télécharger
depuis sa publication GitHub, après votre accord et en vérifiant son empreinte. À rien d'autre. Pas
de compte, pas de télémétrie. La clé ADB qui permet à cet ordinateur de parler à vos téléviseurs est
gardée chiffrée par Windows (DPAPI) dans `%APPDATA%\TVSlim`, à côté des journaux.

## Soutenir le projet

TV Slim est gratuit et le restera. S'il vous a rendu service, vous pouvez m'offrir un café sur
**[Ko-fi](https://ko-fi.com/jolabs40)**.

[![ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/K7Z1286U38)

L'application en parle de deux façons, pas davantage : la tasse de Ko-fi dans sa barre du haut (et
un lien dans *À propos*), et un bandeau après un débloat, un transfert de fichiers ou une
installation d'APK réussis — au plus une fois par mois. *J'ai déjà fait un don* le fait disparaître
pour de bon. L'application ne contacte jamais Ko-fi elle-même : le bouton ouvre votre navigateur.

Une étoile sur ce dépôt aide aussi d'autres personnes à trouver TV Slim.

## Désinstaller

*Paramètres → Applications → TV Slim → Désinstaller*. Les journaux restent dans `%APPDATA%\TVSlim`,
au cas où il faudrait restaurer quelque chose plus tard ; supprimez ce dossier pour tout effacer.
Pour retirer à cet ordinateur l'accès à un téléviseur : *Options pour les développeurs → Révoquer
les autorisations de débogage USB*.

## Compiler depuis les sources

Il faut un JDK 21.

```sh
cd "TVSlim Windows"
./gradlew run          # lancer l'application
./gradlew jvmTest      # les tests, y compris ceux du noyau partagé avec Android
./gradlew packageMsi   # l'installateur, dans build/compose/binaries/main/msi
```

| Dossier | Contenu |
|---|---|
| `TVSlim/` | Applications Android : `core` (catalogue, moteur, garde-fous), `app-mobile` (TV Slim Remote), `app-tv` |
| `TVSlim Windows/` | Application Windows (Kotlin, Compose Multiplatform). Elle compile directement les sources du `core` Android : un seul catalogue, un seul moteur, un seul jeu de garde-fous. |

### Publier une version Windows

Poussez un tag annoté `windows-vX.Y.Z` : la chaîne teste, fabrique, signe et publie. La clé de
signature vient du secret `TVSLIM_CLE_SIGNATURE`. Un fork doit générer sa propre paire de clés
(`java outils/GenererCleSignature.java`) et remplacer `clePubliqueMisesAJour` dans
`gradle.properties` avant sa première publication.

## Licence

Voir [LICENSE](LICENSE).

TV Slim n'est affilié à aucun fabricant de téléviseurs, de box ou de launchers. Les noms et logos des
marques appartiennent à leurs propriétaires et ne servent qu'à identifier l'appareil ou l'application
détectés ; [NOTICE-LOGOS.md](NOTICE-LOGOS.md) dit d'où vient chacun. Startlight Launcher, que TV
Slim recommande, vient du même auteur. Chaque changement que fait TV Slim est réversible, mais vous
restez maître de ce que vous désactivez.
