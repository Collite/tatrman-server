#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Vendor the ttr-service library into every per-service chart, then build the
# umbrella's subchart deps. All dependencies are file:// — this runs OFFLINE and
# is deterministic. The vendored `charts/` + `Chart.lock` are gitignored build
# artifacts (rebuilt here on demand), so re-running produces no git diff.
#
# Used by scripts/helm-golden.sh and the `helm` CI job. Idempotent.
set -euo pipefail

cd "$(dirname "$0")/.."
REPO_ROOT="$(pwd)"
UMBRELLA="helm/tatrman-server"

# Every per-service chart is a `<component>/k8s` dir that depends on ttr-service.
mapfile -t CHARTS < <(find services workers tools infra -path '*/k8s/Chart.yaml' \
  -exec dirname {} \; | sort)

echo "→ vendoring ttr-service into ${#CHARTS[@]} per-service charts"
for c in "${CHARTS[@]}"; do
  # `update` (not `build`) so charts without a committed Chart.lock resolve too.
  helm dependency update "$REPO_ROOT/$c" >/dev/null
done

echo "→ building umbrella subchart deps ($UMBRELLA)"
helm dependency update "$REPO_ROOT/$UMBRELLA" >/dev/null

echo "✓ helm deps ready"
