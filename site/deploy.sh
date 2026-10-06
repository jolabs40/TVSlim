#!/usr/bin/env bash
# Construit le site puis le publie sur l'Hébergement Web Infomaniak, comme startlightlauncher.com.
#   bash deploy.sh            construit et publie
#   bash deploy.sh --sec      montre ce qui changerait, sans rien écrire
# Passe par l'alias SSH `infomaniak` (~/.ssh/config). Pas de rsync sur le PC : l'archive part par ssh,
# et c'est le rsync du serveur qui synchronise — d'où --delete sans risque de laisser traîner une page
# retirée (un paquet sorti du catalogue, par exemple).
#
# À relancer après chaque publication GitHub : les liens de téléchargement se lisent à la construction.
# Et après chaque changement du catalogue : l'encyclopédie des paquets en est tirée.
#
# Les pages changées sont signalées à IndexNow (Bing, Yandex, Seznam…). La clé n'est pas un secret : le
# protocole veut qu'elle soit publiée, dans public/<clé>.txt.
# --checksum : rsync compare le CONTENU, pas la date — chaque build réécrit toutes les pages.
set -euo pipefail
cd "$(dirname "$0")"

DEST='~/sites/tvslim.app'
SITE='https://tvslim.app'
INDEXNOW_KEY='78320a6785cc0686b844ead8180e861a'
SEC=''
[ "${1:-}" = "--sec" ] && SEC='--dry-run'

npm run build

# .user.ini et la page de maintenance appartiennent à Infomaniak : jamais touchés.
CHANGES=$(tar -C dist -czf - . | ssh infomaniak "set -e
  mkdir -p ~/tmp
  T=\$(mktemp -d ~/tmp/tvslim-site.XXXXXX)
  tar -xzf - -C \"\$T\"
  rsync -a --checksum --delete --itemize-changes $SEC \
    --exclude=.user.ini --exclude=.infomaniak-maintenance.html \
    \"\$T\"/ $DEST/
  rm -rf \"\$T\"")
echo "$CHANGES" | tail -40
echo "($(printf '%s\n' "$CHANGES" | grep -c . || true) changement(s))"

if [ -n "$SEC" ]; then
  echo "Répétition à blanc : rien n'a été publié."
  exit 0
fi
echo "Publié : $SITE/"

# Les pages changées : « >f… fr/packages/x/index.html » donne $SITE/fr/packages/x/.
URLS=$(printf '%s\n' "$CHANGES" | sed -nE "s#^>f[^ ]* +((.*/)?)index\.html\$#$SITE/\1#p")
if [ -z "$URLS" ]; then
  echo "IndexNow : aucune page changée, rien à signaler."
  exit 0
fi
LIST=$(printf '%s\n' "$URLS" | sed 's/.*/"&"/' | paste -sd, -)
BODY="{\"host\":\"tvslim.app\",\"key\":\"$INDEXNOW_KEY\",\"keyLocation\":\"$SITE/$INDEXNOW_KEY.txt\",\"urlList\":[$LIST]}"
CODE=$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 -X POST 'https://api.indexnow.org/indexnow' \
  -H 'Content-Type: application/json; charset=utf-8' -d "$BODY") || CODE='échec'
# 200 ou 202 : reçu. Un échec n'annule pas la publication, qui est faite.
echo "IndexNow : $(printf '%s\n' "$URLS" | wc -l | tr -d ' ') page(s) signalée(s), réponse $CODE"
