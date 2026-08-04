---
schema: ttr-skill/v1
op: op:trend
triggers:
  - { text: "vývoj", lang: cs, method: TYPOS(1) }
  - { text: "trend", lang: cs, method: EXACT }
  - { text: "vývoj v čase", lang: cs, method: TOKENS }
requires: [ time-grain ]
version: 1
---
Change over time — the hartland_cz conformance fixture's operator.

The BODY is here to prove it stays out of the matcher (RV-35): only the `triggers:` above
reach lex-matcher, as `op:trend` rows with target class OPERATOR. Everything below this line
is the Golem's business and must never appear as a candidate. If a conformance query ever
matched this paragraph, the skill-body boundary would be broken.

Retrieval: group by the finest requested time grain; order chronologically; require at least
two periods.
