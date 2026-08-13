# SPDX-License-Identifier: Apache-2.0
"""Tokenizer profiles — the language data the driver reads (LM-2).

`base` is the shape; `cs` is the only language in v1. Slovak is the next one
and is expected to be a sibling module here and nothing else.
"""

from __future__ import annotations

from ttrnlp.morph.profiles.base import KINDS, AttachRule, Profile, Rule
from ttrnlp.morph.profiles.cs import CS

#: Every profile by name. `tokenize(text, profile=...)` resolves through here.
PROFILES: dict[str, Profile] = {CS.name: CS}


class UnknownProfile(KeyError):
    """No profile by that name is registered."""


def get_profile(name: str) -> Profile:
    """Look up a profile by language code.

    Raises:
        UnknownProfile: Naming what is registered. A silent fallback to cs
            would tokenize another language by Czech rules and produce plausible
            output — the worst possible failure for a substrate every other
            component reads offsets from.
    """
    try:
        return PROFILES[name]
    except KeyError:
        raise UnknownProfile(
            f"no tokenizer profile {name!r}; registered: {sorted(PROFILES)}"
        ) from None


__all__ = [
    "KINDS",
    "PROFILES",
    "AttachRule",
    "Profile",
    "Rule",
    "UnknownProfile",
    "get_profile",
]
