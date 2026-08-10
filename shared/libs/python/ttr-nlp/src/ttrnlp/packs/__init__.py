# SPDX-License-Identifier: Apache-2.0
"""Pack and list lifecycle (NL-15).

Filled in at NLS-P1/P2: `diag.py` (the shared diagnostic shape), `loader.py`
(sources -> immutable snapshot, FAIL-ALL-OR-NOTHING) and `validate.py` — THE
single validation code path that the CLI, the service boot and `ReloadPacks` all
run, so a pack that validates on a laptop validates identically in the cluster.
"""
