# SPDX-License-Identifier: Apache-2.0
"""Route resolution result (RG-P1.S1.T3).

A `Route` is the fully-resolved decision for one (language, op): which engine
serves it, the explicit model id + version it will echo (S-1), the pinning
`tier`, and any diagnostics attached (RG-NLP-002 for a Lindat tier, RG-NLP-010
for a degrade-floor fallback). `route.model` is **never empty** — the floor
names its deterministic in-front producer.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Mapping, Optional, Tuple

from nlp_service.engines.base import NlpEngine, NlpOp

# The degrade floor's producer identity (S-1 holds even when no backend serves
# the op): deterministic in-front tokenize + S-2 fold + langid.
FLOOR_ENGINE = "floor"
FLOOR_MODEL = "tokenize+fold+langid"
FLOOR_MODEL_VERSION = "s2"


@dataclass(frozen=True)
class Route:
    op: NlpOp
    language: str
    engine: str
    model: str
    model_version: str
    tier: str
    adapter: Optional[NlpEngine] = None
    is_floor: bool = False
    info: Tuple[str, ...] = field(default_factory=tuple)


# ── RV-P8.2: the routing config's rejection catalogue (RV-40) ────────────────


class RoutingConfigError(ValueError):
    """A routing table names something that cannot serve it.

    Raised where the registry is BUILT — at boot on both the REST and gRPC paths
    — rather than where a route is resolved. Validating inside `route()` would
    pass every test about the messages and still let a broken estate boot green,
    then answer requests from whatever engine the last-resort scan happened to
    find. A config that cannot serve must not load half-way.
    """


def validate_routing(
    *,
    routing: Mapping[str, str],
    engines: Mapping[str, NlpEngine],
    lane: str,
    declared: Mapping[str, bool],
    withheld: frozenset[str] | set[str],
    default_language: str,
) -> None:
    """Check every key of the ACTIVE LANE'S RESOLVED table, or raise.

    `declared` is every engine name this build knows about mapped to whether the
    config enabled it — which is what lets an unroutable target be diagnosed as
    the *right one of three mistakes*: a typo, an engine switched off, or an
    engine this lane does not carry. "Not registered" is true of all three and
    useful for none.

    The lane is why validation is per-lane rather than over the whole file: a
    `lane_overrides.option` block naming MorphoDiTa is not an error while running
    `default` — it is the other lane's business, and rejecting it would make the
    two lanes unable to live in one config, which is the entire point of the
    section.
    """
    for key, engine_name in sorted(routing.items()):
        op, language = _parse_key(key, default_language)
        if op is None:
            raise RoutingConfigError(
                f"routing key {key!r} does not name an NLP op — "
                f"expected `OP.lang`, `OP.lang.fallback` or `DETECT_LANGUAGE` "
                f"(ops: {', '.join(o.value for o in NlpOp)})"
            )

        engine = engines.get(engine_name)
        if engine is None:
            raise RoutingConfigError(_why_absent(key, engine_name, engines, declared, withheld, lane))

        if not engine.supports(language, op):
            serves = sorted(
                f"{o.value}.{lang}"
                for lang in sorted(engine.supported_languages())
                for o in NlpOp
                if engine.supports(lang, o)
            )
            raise RoutingConfigError(
                f"routing key {key!r} sends {op.value} to {engine_name!r}, which does not "
                f"serve {op.value} for {language!r} — it serves: "
                f"{', '.join(serves) or '(nothing)'}"
            )


def _parse_key(key: str, default_language: str) -> tuple[Optional[NlpOp], str]:
    """`OP.lang[.fallback]` or the bare `DETECT_LANGUAGE`, as the registry reads it."""
    body = key[: -len(".fallback")] if key.endswith(".fallback") else key
    if body == NlpOp.DETECT_LANGUAGE.value:
        return NlpOp.DETECT_LANGUAGE, default_language
    op_name, _, language = body.partition(".")
    try:
        return NlpOp(op_name), language
    except ValueError:
        return None, language


def _why_absent(
    key: str,
    name: str,
    engines: Mapping[str, NlpEngine],
    declared: Mapping[str, bool],
    withheld: frozenset[str] | set[str],
    lane: str,
) -> str:
    registered = ", ".join(sorted(engines)) or "(none)"
    if name in withheld:
        return (
            f"routing key {key!r} names engine {name!r}, which the {lane!r} lane does not "
            f"carry — it exists only inside another lane's overlay. Registered here: {registered}"
        )
    if name in declared and not declared[name]:
        return (
            f"routing key {key!r} names engine {name!r}, which is DISABLED "
            f"(`engines.{name}.enabled: false`). Enable it or route this op elsewhere — "
            f"a config that cannot serve must not load half-way. Registered here: {registered}"
        )
    return (
        f"routing key {key!r} names engine {name!r}, which this build does not know. "
        f"Registered here: {registered}"
    )
