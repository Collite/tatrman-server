#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# check-spdx.sh — CI gate: fail if any tracked source file is missing the SPDX
# license identifier in its first three lines. Companion to add-spdx.sh, which
# applies the header. Both scripts stay in-repo so external contributors can run
# them locally before opening a PR.
#
# Scope: tracked *.kt *.kts *.py *.proto *.ts, excluding generated / vendored
# trees (build output, ANTLR-generated parsers, protobuf *_pb2 stubs, node_modules,
# graphify-out).
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

spdx='SPDX-License-Identifier: Apache-2.0'
offenders=()

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
  if ! grep -qF -- "$spdx" <<<"$header"; then
    offenders+=("$f")
  fi
done < <(git ls-files -- '*.kt' '*.kts' '*.py' '*.proto' '*.ts')

if [ ${#offenders[@]} -gt 0 ]; then
  printf 'Missing "%s" in the first 3 lines of %d file(s):\n' "$spdx" "${#offenders[@]}"
  printf '  %s\n' "${offenders[@]}"
  echo
  echo 'Fix: ./scripts/add-spdx.sh'
  exit 1
fi

echo "SPDX header check: OK (all tracked source files carry the Apache-2.0 identifier)."
