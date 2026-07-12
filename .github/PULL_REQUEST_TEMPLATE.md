<!-- Thanks for contributing to Tatrman Server! Please fill this in and delete the hints. -->

## What this changes

<!-- A short description of the change and the issue/RFC it addresses. -->

Closes #

## Type of change

- [ ] **Edge** — worker / connector / emit plugin / deployment glue / docs (PR + review + conformance)
- [ ] **Core** — service contract / MCP surface / plan wire format (**requires an RFC** — link it below)

RFC / design discussion:

## Checklist

- [ ] Commits are signed off (`git commit -s`) — the DCO applies to every commit,
      including agent-authored ones (see [CONTRIBUTING.md](../CONTRIBUTING.md)).
- [ ] Tests added or updated; `./gradlew build` (and `just test-py` if Python) passes.
- [ ] `bash scripts/check-spdx.sh` and `bash scripts/check-dependency-rules.sh` are green.
- [ ] For an SPI contribution (worker/connector/plugin): the relevant conformance
      fixtures pass.
- [ ] No retired persona strings on wire/build surfaces (the CI grep-gate).
- [ ] No unrelated changes bundled in.
