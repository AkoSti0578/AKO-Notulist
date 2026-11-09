#!/usr/bin/env bash
# Scans staged files (or all tracked files with --all) for secrets before committing.
# Install as pre-commit hook:  ln -s ../../scripts/check-secrets.sh .git/hooks/pre-commit
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

if [[ "${1:-}" == "--all" ]]; then files=$(git ls-files); else files=$(git diff --cached --name-only --diff-filter=ACM); fi
[[ -z "$files" ]] && exit 0

fail=0
# 1) Files that must never be committed.
while IFS= read -r f; do
  case "$f" in
    *.jks|*.keystore|*.p12|keystore.properties|local.properties|*.env|secrets.properties)
      echo "VERBODEN BESTAND: $f"; fail=1 ;;
  esac
done <<< "$files"

# 2) Secret-looking content (OpenAI keys, private keys, AWS keys, hard-coded passwords).
pattern='sk-(proj-|svcacct-|admin-)?[A-Za-z0-9_-]{20,}|-----BEGIN [A-Z ]*PRIVATE KEY-----|AKIA[0-9A-Z]{16}|(store|key)Password[[:space:]]*=[[:space:]]*["'"'"'][^"'"'"']+["'"'"']'
while IFS= read -r f; do
  [[ -f "$f" ]] || continue
  [[ "$f" == "scripts/check-secrets.sh" ]] && continue
  if grep -I -n -E "$pattern" "$f" >/dev/null 2>&1; then
    echo "MOGELIJK GEHEIM in $f:"; grep -I -n -E "$pattern" "$f" | sed 's/\(sk-[A-Za-z0-9_-]\{4\}\)[A-Za-z0-9_-]*/\1…/g'
    fail=1
  fi
done <<< "$files"

if [[ $fail -ne 0 ]]; then echo "Commit afgebroken: verwijder de geheimen hierboven."; exit 1; fi
echo "Geheimencontrole: niets gevonden."
