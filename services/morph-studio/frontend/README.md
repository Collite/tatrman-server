# morph-studio — the FI-7 frontend

Vite + Vue 3 + TypeScript, mirroring `kantheon/frontends/iris`'s layout and
tooling, minus everything an internal editorial tool does not need: no design
system, no auth beyond the deployment's own, no state library. One stylesheet,
three views, a generated client.

Built to `dist/`, which the **backend** serves (`api._mount_frontend`). One
deployable, one origin, and therefore no CORS policy anywhere in this service.

```bash
just fe-install      # npm install, once
just fe-dev          # vite on :7291, proxying /v1 to a studio on :7290
just fe-build        # type-check + bundle into dist/
just fe-test         # vitest + @vue/test-utils, jsdom
just fe-api          # regenerate ../openapi.json and src/api/schema.d.ts
```

## The client is generated

`src/api/schema.d.ts` is **generated** from `../openapi.json` and should never be
edited. That document is itself dumped from the running app's schema by
`scripts/dump_openapi.py`, and `tests/test_openapi.py` in the backend suite fails
if the committed copy is stale — so a backend change that renames a field breaks
the python suite immediately, rather than surfacing as `undefined` in a browser
nobody is watching.

`src/api/client.ts` is the hand-written half: paths and parameters come from the
generated `paths` type, so a renamed endpoint is a compile error, and every
failure becomes an `ApiError` carrying the sentence the service actually wrote.

## What the components do not decide

* **Which patterns exist** — `GET /v1/vzory`. The inventory is data (LM-2); a
  hard-coded option list is the one place that ruling would fail to reach.
* **Which statuses exist, and which transitions are legal** — `GET /v1/machine`.
  The chips and the verdict buttons *are* the machine, so an invented state is
  unrepresentable and a button for a refused edge is unbuildable.

## Layout

```
src/
├── api/          schema.d.ts (GENERATED) + client.ts
├── components/   StatusChip · ProvenanceBadge · ParadigmTable · VzorPicker · ProposalDiff
├── composables/  useMachine · useVzory — fetched once, shared
├── views/        WordView (surface 1) · EntryView + NewEntryView (2) · QueueView (3+4)
├── feats.ts      UD feature strings → the Czech textbook grid (1.–7. pád)
└── provenance.ts the two provenances, which are not the same thing
```

`feats.ts` is worth a look before changing the table: the case order is the
textbook one rather than UD's alphabetical, because B-O5's whole premise is that
the sub-vzor inventory maps 1:1 onto tables people learned at school.
