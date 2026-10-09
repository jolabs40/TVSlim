#!/usr/bin/env bash
# Builds the site and publishes it to Infomaniak web hosting.
#   bash deploy.sh            build and publish
#   bash deploy.sh --sec      dry run, shows what would change
# Uses the SSH alias `infomaniak` (~/.ssh/config). There is no rsync on the PC: the archive goes over ssh
# and the server's rsync does the sync, so --delete also removes pages that no longer exist.
#
# Rerun after every GitHub release (download links are read at build time) and after every catalogue
# change (the package pages are generated from it).
#
# Changed pages are submitted to IndexNow. The key is not a secret: the protocol requires it to be
# published, in public/<key>.txt.
# --checksum: compare content, not mtime, since every build rewrites every page.
set -euo pipefail
cd "$(dirname "$0")"

DEST='~/sites/tvslim.app'
SITE='https://tvslim.app'
INDEXNOW_KEY='78320a6785cc0686b844ead8180e861a'
SEC=''
[ "${1:-}" = "--sec" ] && SEC='--dry-run'

npm run build

# .user.ini and the maintenance page belong to Infomaniak: never touched.
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

# Changed pages: ">f... fr/packages/x/index.html" becomes $SITE/fr/packages/x/.
URLS=$(printf '%s\n' "$CHANGES" | sed -nE "s#^>f[^ ]* +((.*/)?)index\.html\$#$SITE/\1#p")
if [ -z "$URLS" ]; then
  echo "IndexNow : aucune page changée, rien à signaler."
  exit 0
fi
LIST=$(printf '%s\n' "$URLS" | sed 's/.*/"&"/' | paste -sd, -)
BODY="{\"host\":\"tvslim.app\",\"key\":\"$INDEXNOW_KEY\",\"keyLocation\":\"$SITE/$INDEXNOW_KEY.txt\",\"urlList\":[$LIST]}"
CODE=$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 -X POST 'https://api.indexnow.org/indexnow' \
  -H 'Content-Type: application/json; charset=utf-8' -d "$BODY") || CODE='échec'
# 200 or 202 means received. A failure here does not undo the publish.
echo "IndexNow : $(printf '%s\n' "$URLS" | wc -l | tr -d ' ') page(s) signalée(s), réponse $CODE"
