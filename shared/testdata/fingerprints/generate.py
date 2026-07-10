#!/usr/bin/env python3
"""Generator + reference for the cross-engine schema-fingerprint fixture set.

Fork Stage 3.4 T2. The schema fingerprint is the cross-engine schema-identity
check shared by Charon (`Integrity.kt`), Mssql (Kotlin), Polars (Python),
and — later — Metis. review-006 R3 (Bora, 2026-06-15) settled it as a SHA-256
over a **canonical, implementation-independent** string form of the *logical*
Arrow schema (NOT raw IPC bytes — those are not byte-stable across Arrow
implementations).

This script writes, into this directory:
  - `*.arrow`           — one Arrow IPC stream per fixture schema;
  - `fingerprints.json` — { "<file>.arrow": "<sha256-hex>" } expected digests;
  - `canonical.txt`     — the canonical strings (debugging / cross-check).

Every implementation reads the SAME `*.arrow` bytes and recomputes the digest
with its own copy of the algorithm; all must equal `fingerprints.json`. That is
the "same algorithm, multiple implementations, must agree" CI pin.

Canonical form (must match Charon `Integrity.canonicalSchemaString`):
  - top-level fields joined by '\\n', declaration order;
  - field := name '|' type '|' nullability ['<' child ';' child … '>'];
  - nullability := 'null' (nullable) | 'nonnull';
  - type tokens spell out every parameter, using SHARED unit tokens
    ('s|ms|us|ns', date 'day|ms', interval 'ym|dt|mdn');
  - metadata excluded;
  - SHA-256 of the UTF-8 bytes, lowercase hex.

Nested-type child recursion (the cross-engine subtlety, Stream B note):
  - struct → its declared child fields, names kept (semantic);
  - list/large_list/fixed_size_list → the single `value_field` (Arrow names it
    `item`);
  - map → the single `value_field`, i.e. the **`entries` struct** (NOT the
    flattened key/value pair). This matches Arrow Java's `Field.children` — the
    runtime form Charon and Mssql see — so an entries-wrapped map fingerprints
    identically across Kotlin and Python.

Run (pyarrow pinned to the worker's version):

    uv run --with pyarrow==18.0.0 python3 shared/testdata/fingerprints/generate.py
"""
import hashlib
import json
import sys
from pathlib import Path

import pyarrow as pa
from pyarrow import ipc


def encode_type(t: pa.DataType) -> str:
    if pa.types.is_null(t):
        return "null"
    if pa.types.is_boolean(t):
        return "bool"
    if pa.types.is_integer(t):
        return "int" + str(t.bit_width) + ("s" if pa.types.is_signed_integer(t) else "u")
    if pa.types.is_floating(t):
        return "float" + {16: "16", 32: "32", 64: "64"}[t.bit_width]
    if pa.types.is_decimal(t):
        bit_width = 256 if pa.types.is_decimal256(t) else 128
        return f"decimal{bit_width}_{t.precision}_{t.scale}"
    if pa.types.is_string(t):
        return "utf8"
    if pa.types.is_large_string(t):
        return "large_utf8"
    if pa.types.is_fixed_size_binary(t):
        return "fixed_size_binary_" + str(t.byte_width)
    if pa.types.is_binary(t):
        return "binary"
    if pa.types.is_large_binary(t):
        return "large_binary"
    if pa.types.is_date32(t):
        return "date_day"
    if pa.types.is_date64(t):
        return "date_ms"
    if pa.types.is_time(t):
        bit_width = 32 if pa.types.is_time32(t) else 64
        return f"time_{t.unit}_{bit_width}"
    if pa.types.is_timestamp(t):
        return "timestamp_" + t.unit + "_" + (t.tz if t.tz is not None else "")
    if pa.types.is_duration(t):
        return "duration_" + t.unit
    # map is a list subtype — check it before the list family.
    if pa.types.is_map(t):
        return "map_" + ("sorted" if t.keys_sorted else "unsorted")
    if pa.types.is_list(t):
        return "list"
    if pa.types.is_large_list(t):
        return "large_list"
    if pa.types.is_fixed_size_list(t):
        return "fixed_size_list_" + str(t.list_size)
    if pa.types.is_struct(t):
        return "struct"
    raise ValueError(f"fingerprint: unsupported Arrow type {t}")


def children(t: pa.DataType):
    if pa.types.is_struct(t):
        return [t.field(i) for i in range(t.num_fields)]
    # map before list (MapType subclasses ListType). pyarrow exposes key_field +
    # item_field, but the IPC bytes (and Arrow Java's Field.children) carry the
    # entries-wrapped form: a non-nullable `entries` struct of {key, value}.
    # Synthesize it so the Python canonical string matches what Arrow Java reads
    # back from these same bytes.
    if pa.types.is_map(t):
        return [pa.field("entries", pa.struct([t.key_field, t.item_field]), nullable=False)]
    if pa.types.is_list(t) or pa.types.is_large_list(t) or pa.types.is_fixed_size_list(t):
        return [t.value_field]
    return []


def encode_field(f: pa.Field) -> str:
    nullability = "null" if f.nullable else "nonnull"
    kids = children(f.type)
    child_part = "" if not kids else "<" + ";".join(encode_field(c) for c in kids) + ">"
    return f.name + "|" + encode_type(f.type) + "|" + nullability + child_part


def canonical_schema_string(schema: pa.Schema) -> str:
    return "\n".join(encode_field(f) for f in schema)


def fingerprint(schema: pa.Schema) -> str:
    return hashlib.sha256(canonical_schema_string(schema).encode("utf-8")).hexdigest()


# --- fixture schemas ---------------------------------------------------------

# Charon's exact reference schema (services/charon/.../fixtures/integrity/regenerate.py).
# Pinned so this set is provably the SAME algorithm Charon already ships.
REFERENCE = pa.schema(
    [
        pa.field("name", pa.utf8(), nullable=False),
        pa.field("count", pa.int64(), nullable=False),
        pa.field("score", pa.float64(), nullable=True),
        pa.field("amount", pa.decimal128(38, 9), nullable=True),
        pa.field("ts", pa.timestamp("us", tz="UTC"), nullable=True),
        pa.field("d", pa.date32(), nullable=True),
        pa.field("active", pa.bool_(), nullable=False),
        pa.field("payload", pa.binary(), nullable=True),
        pa.field(
            "meta",
            pa.struct([pa.field("key", pa.utf8(), nullable=False), pa.field("val", pa.int32(), nullable=True)]),
            nullable=True,
        ),
    ]
)

SCALARS = pa.schema(
    [
        pa.field("i8", pa.int8(), nullable=False),
        pa.field("u32", pa.uint32(), nullable=True),
        pa.field("f32", pa.float32(), nullable=True),
        pa.field("flag", pa.bool_(), nullable=False),
        pa.field("price", pa.decimal128(18, 2), nullable=True),
        pa.field("when_ms", pa.timestamp("ms"), nullable=True),
        pa.field("blob", pa.large_binary(), nullable=True),
    ]
)

LIST = pa.schema(
    [
        pa.field("id", pa.int64(), nullable=False),
        pa.field("tags", pa.list_(pa.int64()), nullable=True),
    ]
)

MAP = pa.schema(
    [
        pa.field("id", pa.int64(), nullable=False),
        pa.field("attrs", pa.map_(pa.utf8(), pa.int32()), nullable=True),
    ]
)

FIXTURES = {
    "reference.arrow": REFERENCE,
    "scalars.arrow": SCALARS,
    "list.arrow": LIST,
    "map.arrow": MAP,
}

# Pinned cross-engine anchor: the reference schema digest Charon already ships.
REFERENCE_DIGEST = "69779ea65b0e127c59dc4f537bc33f62f08835c0098dbf313d61b35955fea7b8"


def write_ipc(schema: pa.Schema, path: Path) -> None:
    sink = pa.OSFile(str(path), "wb")
    with ipc.new_stream(sink, schema):
        pass  # schema-only stream; the fingerprint is over the schema


def main() -> int:
    here = Path(__file__).parent
    digests: dict[str, str] = {}
    canon: dict[str, str] = {}
    for fname, schema in FIXTURES.items():
        write_ipc(schema, here / fname)
        digests[fname] = fingerprint(schema)
        canon[fname] = canonical_schema_string(schema)

    assert digests["reference.arrow"] == REFERENCE_DIGEST, (
        f"reference.arrow digest {digests['reference.arrow']} != Charon's pinned {REFERENCE_DIGEST} — "
        "the canonical algorithm has drifted from Charon's Integrity.kt"
    )

    (here / "fingerprints.json").write_text(json.dumps(digests, indent=2, sort_keys=True) + "\n")
    (here / "canonical.txt").write_text(
        "\n\n".join(f"# {f}\n{canon[f]}" for f in sorted(canon)) + "\n"
    )
    print(f"pyarrow={pa.__version__}")
    for f, d in sorted(digests.items()):
        print(f"{d}  {f}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
