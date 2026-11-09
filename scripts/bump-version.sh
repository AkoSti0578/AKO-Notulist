#!/usr/bin/env bash
# Verhoogt versionCode met 1, zet versionName, commit en tagt (v<versie>).
# Gebruik:  scripts/bump-version.sh 1.2.0   daarna:  git push && git push --tags
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
NEW="${1:?Gebruik: scripts/bump-version.sh <MAJOR.MINOR.PATCH>}"
[[ "$NEW" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "Geen semver: $NEW"; exit 1; }
CODE=$(grep '^versionCode=' version.properties | cut -d= -f2)
NEXT=$((CODE + 1))
sed -i.bak -e "s/^versionCode=.*/versionCode=$NEXT/" -e "s/^versionName=.*/versionName=$NEW/" version.properties && rm -f version.properties.bak
git add version.properties
git commit -m "Release $NEW (versionCode $NEXT)"
git tag -a "v$NEW" -m "AKO Notulist $NEW"
echo "versionCode $CODE -> $NEXT, versionName $NEW, tag v$NEW aangemaakt."
echo "Publiceren:  git push && git push origin v$NEW"
