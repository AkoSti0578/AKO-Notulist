#!/usr/bin/env bash
# Maakt een release-keystore + keystore.properties (beide buiten git) en toont de waarden
# voor de GitHub Secrets. Eenmalig uitvoeren; MAAK DAARNA EEN BACK-UP VAN DE KEYSTORE.
set -euo pipefail
cd "$(dirname "$0")/.."  # project root; works with or without git

KEYSTORE="${1:-$HOME/.android-keystores/ako-notulist-release.jks}"
ALIAS="ako-notulist"
if [[ -e "$KEYSTORE" ]]; then echo "Bestaat al: $KEYSTORE (niet overschreven)"; exit 1; fi
mkdir -p "$(dirname "$KEYSTORE")"

read -r -s -p "Kies een sterk keystore-wachtwoord (min. 6 tekens): " PASS; echo
read -r -s -p "Herhaal wachtwoord: " PASS2; echo
[[ "$PASS" == "$PASS2" ]] || { echo "Wachtwoorden verschillen."; exit 1; }

keytool -genkeypair -v -keystore "$KEYSTORE" -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 36500 \
  -storepass "$PASS" -keypass "$PASS" -dname "CN=AKO Notulist, O=Arnold Kolkman, C=NL"

cat > keystore.properties <<PROPS
storeFile=$KEYSTORE
storePassword=$PASS
keyAlias=$ALIAS
keyPassword=$PASS
PROPS
chmod 600 keystore.properties "$KEYSTORE"

echo
echo "Klaar: $KEYSTORE en keystore.properties (staat in .gitignore)."
echo "GitHub Secrets (Settings > Secrets and variables > Actions):"
echo "  KEYSTORE_BASE64   = uitvoer van:  base64 -w0 \"$KEYSTORE\""
echo "  KEYSTORE_PASSWORD = je wachtwoord"
echo "  KEY_ALIAS         = $ALIAS"
echo "  KEY_PASSWORD      = je wachtwoord"
echo
echo "!!! Maak NU een back-up van $KEYSTORE en het wachtwoord (bijv. in een wachtwoordkluis)."
echo "!!! Zonder deze keystore kun je de app nooit meer bijwerken."
