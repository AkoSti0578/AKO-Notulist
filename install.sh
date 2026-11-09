#!/usr/bin/env bash
# Installeert AKO Notulist via adb met de Play Store als "installer package",
# zodat Android Auto de app toont. Gebruik:  ./install.sh [pad/naar/app.apk]
set -euo pipefail

APK="${1:-}"
if [[ -z "$APK" ]]; then
  APK=$(ls -t app/build/outputs/apk/release/*.apk 2>/dev/null | head -1 || true)
fi
if [[ -z "$APK" || ! -f "$APK" ]]; then
  echo "Geen APK gevonden. Bouw eerst:  ./gradlew assembleRelease   of geef het pad mee." >&2
  exit 1
fi
if ! command -v adb >/dev/null 2>&1; then
  echo "adb niet gevonden. Installeer de Android SDK Platform-Tools en zet adb in je PATH." >&2
  exit 1
fi

DEVICES=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')
COUNT=$(echo "$DEVICES" | grep -c . || true)
if [[ "$COUNT" -eq 0 ]]; then
  echo "Geen toestel gevonden. Zet USB-foutopsporing (of draadloze foutopsporing) aan en bevestig de vingerafdruk op je telefoon." >&2
  exit 1
elif [[ "$COUNT" -gt 1 && -z "${ANDROID_SERIAL:-}" ]]; then
  echo "Meerdere toestellen gevonden. Kies er één met:  ANDROID_SERIAL=<serial> ./install.sh" >&2
  echo "$DEVICES" >&2
  exit 1
fi

echo "Installeren: $APK"
# -r = bijwerken met behoud van gegevens, -i com.android.vending = Play Store als installer
adb install -r -i com.android.vending "$APK"

INFO=$(adb shell pm list packages -i nl.arnoldkolkman.akonotulist | tr -d '\r')
echo "$INFO"
if ! echo "$INFO" | grep -q "installer=com.android.vending"; then
  echo "LET OP: de installer is NIET de Play Store. Android Auto toont de app dan niet." >&2
  echo "Staat de Play Store (com.android.vending) op dit toestel? Zonder Play Store wordt -i genegeerd." >&2
  exit 2
fi
echo "Klaar. Open AKO Notulist op je telefoon en stel je OpenAI API-sleutel in."
