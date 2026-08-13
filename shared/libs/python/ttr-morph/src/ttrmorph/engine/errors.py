# SPDX-License-Identifier: Apache-2.0
"""What the engine refuses to guess about (contracts §4).

Every one of these is a *data* error, not a runtime condition: an analyst wrote
a vzor name that does not exist, a flag that is not in the inventory, or a
citation form that cannot belong to the pattern it was assigned. Generation has
no partial-success mode — a paradigm with a plausible-looking wrong form in it
is worse than no paradigm, because it reaches the snapshot and then the
gazetteer expansion and is never questioned again.
"""

from __future__ import annotations


class EngineError(ValueError):
    """Base for every paradigm-engine refusal."""


class UnknownVzor(EngineError):
    """No such pattern in the tables for this language."""


class BadFlag(EngineError):
    """A flag outside the declared inventory."""


class BadLemma(EngineError):
    """The citation form cannot carry this pattern.

    Contracts §4 names only `UnknownVzor` and `BadFlag`; this is the third
    thing that can be wrong and it used to be silent. A pattern that strips a
    citation ending the lemma does not have would otherwise produce a paradigm
    built on the wrong stem — every form wrong, no error, and the entry looks
    fine in the layer file. Failing here is what turns it into a diagnostic the
    importer can attach to a row.
    """
