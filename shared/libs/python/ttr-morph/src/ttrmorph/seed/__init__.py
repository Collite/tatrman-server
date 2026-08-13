# SPDX-License-Identifier: Apache-2.0
"""The seeding lane (design §6) — and the home of the vzor tables.

Pipeline order, which is also the order of trust: hand seed (closed class,
pronouns, numerals, irregulars, contractions) to kaikki import to CAC
extraction to LLM bootstrap over whatever gap remains, with simplemma as a
read-only QA oracle that never contributes an entry.

``data/cs/vzory.yaml`` lives here rather than beside the engine because it is
seed material, not engine code — the point of LM-2 is that the driver has no
language in it and a second language is a second data file, not a second
driver.
"""
