"""Canonical, implementation-independent Arrow schema fingerprint.

The cross-engine schema-identity check shared by Charon (`Integrity.kt`),
Brontes (Kotlin), Steropes (this module), and — later — Metis. review-006 R3
(Bora, 2026-06-15) settled it as a SHA-256 over a canonical string form of the
*logical* Arrow schema, NOT raw IPC bytes (those are not byte-stable across
Arrow implementations / versions).

This MUST stay byte-identical to Charon's `Integrity.canonicalSchemaString` and
to `shared/testdata/fingerprints/generate.py`; the shared fixture set
(`shared/testdata/fingerprints/`) is the CI pin that proves all implementations
agree. See `test_fingerprint.py`.

Canonical form:
  - top-level fields joined by '\\n', declaration order;
  - field := name '|' type '|' nullability ['<' child ';' child … '>'];
  - nullability := 'null' (nullable) | 'nonnull';
  - type tokens spell out every parameter, shared unit tokens ('s|ms|us|ns', …);
  - metadata excluded;
  - SHA-256 of the UTF-8 bytes, lowercase hex.

Nested-type child recursion: struct → declared fields (names kept); list family
→ the `value_field` (Arrow names it `item`); map → the entries-wrapped struct
`{key, value}` (the form Arrow Java exposes), synthesized from pyarrow's
key_field/item_field so Kotlin and Python agree on the same bytes.
"""
import hashlib

import pyarrow as pa


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


def _children(t: pa.DataType) -> list[pa.Field]:
    if pa.types.is_struct(t):
        return [t.field(i) for i in range(t.num_fields)]
    # map before list (MapType subclasses ListType): entries-wrapped {key, value}.
    if pa.types.is_map(t):
        return [pa.field("entries", pa.struct([t.key_field, t.item_field]), nullable=False)]
    if pa.types.is_list(t) or pa.types.is_large_list(t) or pa.types.is_fixed_size_list(t):
        return [t.value_field]
    return []


def encode_field(f: pa.Field) -> str:
    nullability = "null" if f.nullable else "nonnull"
    kids = _children(f.type)
    child_part = "" if not kids else "<" + ";".join(encode_field(c) for c in kids) + ">"
    return f.name + "|" + encode_type(f.type) + "|" + nullability + child_part


def canonical_schema_string(schema: pa.Schema) -> str:
    return "\n".join(encode_field(f) for f in schema)


def schema_fingerprint(schema: pa.Schema) -> str:
    """SHA-256 (lowercase hex) of the canonical logical-schema string."""
    return hashlib.sha256(canonical_schema_string(schema).encode("utf-8")).hexdigest()
