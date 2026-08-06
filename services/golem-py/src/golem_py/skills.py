# SPDX-License-Identifier: Apache-2.0
"""Skill bodies — the operator library, read from the compiled lexicon archive (P4.3).

RV-35/37: an operator word is ordinary VOCABULARY (`op:trend` is a lexicon entry the
matcher binds like any other term), and its BEHAVIOUR is a Golem-side artifact keyed by
that same `op:` id. The matcher never reads a body; the Golem never re-derives a
trigger. This module is the Golem's half.

## The archive layout (T3 recon, verified against hartland's real 8 845-byte archive)

`lexicon.tar.zst` is `SnapshotWriter`'s deterministic container — zstd-19 over a USTAR
tar, `mtime=0`, bytewise path order — holding exactly three documents:

    snapshot.json                  {kind: "lexicon", producedBy, resolvedFrom{modelSnapshotId}}
    docs/lexicon.json              the entry table (the MATCHER's half)
    docs/operator-library.json     {schemaVersion: "ttr-operator-library/v1",
                                    operators: {"op:trend": {body, version, checksum, source}}}

We read the second document and nothing else. The mount convention is `p3-3` T2's —
`*_LEXICON_ARCHIVE_PATH`, the same path the four Kotlin archive readers take — so an
estate mounts ONE ConfigMap and every reader, in either language, sees the same bytes.

## ⚑ Two findings recorded here, both for Bora, both cheap to fix and neither ours

1. **`requires:` does not reach the artifact.** A skill file declares
   `requires: [ time-grain ]` in frontmatter, `LexiconValidator` parses it into
   `SkillFile.requires` — and the compiler drops it: neither `CompiledEntry` nor
   `OperatorEntry` carries it. The only surviving trace is the body's prose
   *"Applicability: `time-grain` — …"* line. So the applicability check T2(d) asks for
   is parsed OUT OF PROSE below, which works today and is exactly as fragile as it
   sounds. The structural fix is one field on `OperatorEntry` in `tatrman`.
2. **The Golem does NOT ship a second copy of the stdlib bodies.** The design says the
   standard operator library lives in both Golems; the compiler already merges stdlib
   with estate overrides into every archive (estate wins, recorded in each entry's
   `source`), so a second copy here would be the same prose maintained twice in two
   languages — the drift RV-37 avoids by making bodies artifacts in the first place.
   The layered API is kept because a platform deployment may mount an estate archive
   over a stdlib one; the OS Golem reads one archive and honours what the compiler
   resolved.
"""

from __future__ import annotations

import hashlib
import io
import json
import re
import tarfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import zstandard

from golem_py.errors import GolemError

OPERATOR_DOC = "docs/operator-library.json"
LEXICON_DOC = "docs/lexicon.json"
MANIFEST_DOC = "snapshot.json"
SCHEMA_ID = "ttr-operator-library/v1"

# A body is prose FOR THE GOLEM (contracts §2), so the parse is a dumb section split and
# stays one: the moment it becomes a grammar, an author has to learn it.
_SECTION = re.compile(r"^(Retrieval|Formatting|Applicability):\s*", re.MULTILINE)
# `Applicability: `time-grain` — …` — the backticked token is the requirement's name.
_REQUIREMENT = re.compile(r"`([a-z0-9-]+)`")


class SkillError(GolemError):
    """A body that cannot be trusted or found. Surfaced as a refusal, never a silent
    skip: an operator the question named and we could not honour changes the answer."""


@dataclass
class SkillBody:
    op: str
    body: str
    version: int
    checksum: str
    source_file: str = ""
    retrieval: str = ""
    formatting: str = ""
    # Parsed from the `Applicability:` prose — see finding (1) in the module docstring.
    requires: list[str] = field(default_factory=list)

    @classmethod
    def parse(cls, op: str, entry: dict[str, Any]) -> SkillBody:
        body = entry["body"]
        sections = _split_sections(body)
        applicability = sections.get("Applicability", "")
        return cls(
            op=op,
            body=body,
            version=entry.get("version", 0),
            checksum=entry.get("checksum", ""),
            source_file=(entry.get("source") or {}).get("file", ""),
            retrieval=sections.get("Retrieval", "").strip(),
            formatting=sections.get("Formatting", "").strip(),
            requires=_REQUIREMENT.findall(applicability),
        )

    def verify(self) -> None:
        """`sha256:<hex>` over the body bytes — the compiler's own rule. A tampered or
        truncated body must refuse to load rather than shape a retrieval."""
        actual = "sha256:" + hashlib.sha256(self.body.encode("utf-8")).hexdigest()
        if self.checksum and actual != self.checksum:
            raise SkillError(
                f"{self.op}: body checksum mismatch (declared {self.checksum}, actual {actual})"
            )


def _split_sections(body: str) -> dict[str, str]:
    parts = _SECTION.split(body)
    # split() yields [preamble, name, text, name, text, …]
    return {parts[i]: parts[i + 1] for i in range(1, len(parts) - 1, 2)}


def read_archive_doc(archive_path: str | Path, doc: str = OPERATOR_DOC) -> str:
    """Pull one document out of a `kind: "lexicon"` archive."""
    raw = Path(archive_path).read_bytes()
    # `max_output_size` is required: the frames are written with content-size off, and
    # an unbounded decompress on an untrusted file is a memory bomb.
    data = zstandard.ZstdDecompressor().decompress(raw, max_output_size=256 * 1024 * 1024)
    with tarfile.open(fileobj=io.BytesIO(data)) as tar:
        member = tar.extractfile(doc)
        if member is None:
            raise SkillError(f"{archive_path}: archive has no {doc}")
        return member.read().decode("utf-8")


@dataclass
class SkillLibrary:
    """One layer of operator bodies, cached by `(version, checksum)` per op."""

    bodies: dict[str, SkillBody] = field(default_factory=dict)
    schema_version: str = SCHEMA_ID
    archive_id: str = ""

    @classmethod
    def from_json(cls, text: str, *, archive_id: str = "") -> SkillLibrary:
        raw = json.loads(text)
        schema = raw.get("schemaVersion", "")
        if schema != SCHEMA_ID:
            raise SkillError(f"unknown operator-library schema {schema!r} (expected {SCHEMA_ID!r})")
        bodies = {}
        for op, entry in raw.get("operators", {}).items():
            parsed = SkillBody.parse(op, entry)
            parsed.verify()
            bodies[op] = parsed
        return cls(bodies=bodies, schema_version=schema, archive_id=archive_id)

    @classmethod
    def from_archive(cls, archive_path: str | Path) -> SkillLibrary:
        return cls.from_json(read_archive_doc(archive_path), archive_id=str(archive_path))

    def get(self, op: str) -> SkillBody:
        try:
            return self.bodies[op]
        except KeyError as exc:
            raise SkillError(
                f"{op} is not in the loaded operator library — the question named an "
                "operator this estate cannot honour"
            ) from exc

    def has(self, op: str) -> bool:
        return op in self.bodies


@dataclass
class LayeredSkillLibrary:
    """Estate over stdlib. First layer that has the op wins — the same precedence the
    compiler enforces for triggers, applied to bodies."""

    layers: list[SkillLibrary] = field(default_factory=list)

    def get(self, op: str) -> SkillBody:
        for layer in self.layers:
            if layer.has(op):
                return layer.get(op)
        raise SkillError(
            f"{op} is not in any loaded operator library — the question named an "
            "operator this estate cannot honour"
        )

    def has(self, op: str) -> bool:
        return any(layer.has(op) for layer in self.layers)


def load_library(*archive_paths: str | Path) -> LayeredSkillLibrary:
    """Layers in the order given: estate first, stdlib last."""
    return LayeredSkillLibrary([SkillLibrary.from_archive(p) for p in archive_paths])
