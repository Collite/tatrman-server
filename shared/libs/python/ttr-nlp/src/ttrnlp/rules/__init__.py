# SPDX-License-Identifier: Apache-2.0
"""The JAPE-class rule engine (NL-1, NL-13).

Own declarative YAML DSL with JAPE-shaped vocabulary, compiled to PAMPAC.
Filled in at NLS-P1: `dsl.py` (YAML -> PackModel), `compiler.py`
(PackModel -> PAMPAC), `executor.py` (the JAPE-exact control styles),
`pipeline.py` (phase runner), `schema/pack.schema.json` (the shipped schema).
"""
