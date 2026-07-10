#!/usr/bin/env bash
# Build-time dependency rules for tatrman-server (contracts §7 / P2 / RO-6).
#
# tatrman-server may depend on org.tatrman:* (from tatrman + tatrman-server) and
# third-party. It may NOT depend on anything cz.tatrman:* or any kantheon
# module/artifact, and no proto here may import the kantheon namespace.
#
# On the empty S1 skeleton this passes trivially (nothing to violate); its real
# bite lands in S4's verify once the spine services and protos have arrived.
set -euo pipefail
fail=0

# 1. no cz.tatrman anywhere in build files
grep -rn --include='*.gradle.kts' --include='*.toml' 'cz\.tatrman' . && fail=1

# 2. no kantheon module/artifact deps
grep -rn --include='*.gradle.kts' -E 'project\(":(agents|frontends)' . && fail=1
grep -rn --include='*.toml' --include='*.gradle.kts' 'org\.tatrman\.kantheon' . && fail=1

# 3. no proto imports from the kantheon namespace
grep -rn --include='*.proto' 'org/tatrman/kantheon/' shared/ services/ workers/ tools/ infra/ 2>/dev/null && fail=1

exit $fail
