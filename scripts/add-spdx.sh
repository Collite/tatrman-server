#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# add-spdx.sh — idempotently insert the SPDX license identifier as the first line
# (after a shebang, if present) of every tracked source file in check-spdx.sh's
# scope. Files already carrying the identifier in their first 3 lines are left
# untouched, so this is safe to re-run. Comment syntax follows the file type:
# `//` for Kotlin/proto/TS, `#` for Python. One-liner only — no banner blocks
# (decision RO-18).
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

spdx='SPDX-License-Identifier: Apache-2.0'
added=0

while IFS= read -r f; do
  case "$f" in
    build/*|*/build/*)               continue ;;
    */generated/*)                   continue ;;
    */vendor/*)                      continue ;;  # third-party (grammars-v4 PostgreSQL base classes)
    docs/*/examples/*)               continue ;;  # vendored samples (byx=MIT/Microsoft, kyx=Alteryx)
    infra/backstage/*)               continue ;;  # third-party Backstage scaffold (Apache-2.0 upstream, "The Backstage Authors")
    *_pb2.py|*_pb2_grpc.py|*_pb2.pyi) continue ;;
    node_modules/*|*/node_modules/*) continue ;;
    graphify-out/*|*/graphify-out/*) continue ;;
  esac

  header="$(head -n 3 "$f")"
  if grep -qF -- "$spdx" <<<"$header"; then
    continue  # already headered
  fi

  case "$f" in
    *.py)                    comment="# $spdx" ;;
    *.kt|*.kts|*.proto|*.ts) comment="// $spdx" ;;
    *)                       continue ;;
  esac

  first="$(head -n 1 "$f")"
  tmp="$(mktemp)"
  if [[ "$first" == '#!'* ]]; then
    { printf '%s\n' "$first"; printf '%s\n' "$comment"; tail -n +2 "$f"; } > "$tmp"
  else
    { printf '%s\n' "$comment"; cat "$f"; } > "$tmp"
  fi
  cat "$tmp" > "$f"   # redirect (not mv) to preserve the file's mode
  rm -f "$tmp"
  added=$((added + 1))
done < <(git ls-files -- '*.kt' '*.kts' '*.py' '*.proto' '*.ts')

echo "add-spdx.sh: inserted header into $added file(s)."
