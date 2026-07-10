# Source file headers (RO-18)

Every **new** source file authored in this repo starts with an SPDX header:

```kotlin
// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Collite
```

Use the comment syntax of the file's language (`//` for Kotlin/Java/proto,
`#` for shell/Python/YAML/TOML, `<!-- … -->` for XML/Markdown where a header is
wanted).

## Scope in SV-P0

- **New files** (this repo, from S1 onward): header required.
- **Moved files** (services grafted from kantheon in S3): headers are added in
  the **SV-P2 license sweep**, not during the move — the S3 diff stays a pure
  history-preserving move (no content edits), so the graft is auditable.

The full license header policy (NOTICE, third-party attributions, the DCO
sign-off rule for contributions) is finalized in SV-P2.
