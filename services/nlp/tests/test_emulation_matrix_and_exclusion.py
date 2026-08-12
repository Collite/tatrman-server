# SPDX-License-Identifier: Apache-2.0
"""RV-P8.3 T1/T3 — advertised, echoed, and honestly excluded.

Three claims, and the third is the one with teeth:

1. the capability matrix ADVERTISES emulation when it is on — engine, model and
   the template revision — and **omits it entirely** when it is off;
2. every response echoes engine + model per op, and for a mixed analysis the
   echo differs per op (proven over the wire in `test_emulated_over_grpc.py`);
3. a suite whose case asserts an emulated op **names that case as skipped**.
   Never silently passes it: a green run that quietly contains an emulated
   assertion is worse than a red one, because it is a determinism claim made
   from a hosted model.
"""

from __future__ import annotations

from nlp_service.config import (
    AppConfig,
    BackendConfig,
    EnginesConfig,
    LangidEngineConfig,
    LlmEmulatedConfig,
)
from nlp_service.emulation import Exclusion, emulated_routes, exclusions
from nlp_service.engines import EngineRegistry
from nlp_service.engines.llm_emulated_engine import (
    EMULATED_ENGINE_NAME,
    TEMPLATE_VERSION,
)


def a_config(*, emulation: bool) -> AppConfig:
    return AppConfig(
        engines=EnginesConfig(
            morphodita=BackendConfig(
                url="http://morphodita:8080/tag",
                model="czech-morfflex2.0-pdtc1.0-220710",
                model_version="czech-morfflex2.0-pdtc1.0-220710",
            ),
            nametag3=BackendConfig(enabled=False),
            stanza=BackendConfig(enabled=False),
            spacy=BackendConfig(enabled=False),
            langid=LangidEngineConfig(model_version="lingua-2.0"),
            llm_emulated=LlmEmulatedConfig(
                enabled=emulation, url="http://llm-gateway:8080", model="claude-haiku-4-5"
            ),
        ),
        op_routing={
            "TOKENIZE.cs": "morphodita",
            "LEMMATIZE.cs": "morphodita",
            "DETECT_LANGUAGE": "langid",
            **({"NER.cs": EMULATED_ENGINE_NAME} if emulation else {}),
        },
    )


def rows(*, emulation: bool) -> list[dict]:
    return EngineRegistry(a_config(emulation=emulation)).served_capabilities()


class TestTheMatrix:
    def test_enabled_advertises_the_engine_its_model_and_the_template_revision(self):
        ner = [r for r in rows(emulation=True) if (r["language"], r["op"].value) == ("cs", "NER")]
        assert len(ner) == 1
        assert ner[0]["engine"] == EMULATED_ENGINE_NAME
        assert ner[0]["model_version"] == f"claude-haiku-4-5/tpl-{TEMPLATE_VERSION}"
        assert ner[0]["tier"] == "REMOTE_UNPINNED"

    def test_disabled_omits_it_entirely_rather_than_marking_it_off(self):
        """Absence is the pre-RV shape and it is also NLS's degrade signal: an op
        nothing in the lane can produce has no row. Emulation had to be built AS
        that rule — a row saying "emulated, disabled" would make a switched-off
        engine and a genuinely unroutable op differ only in which code removed
        the row, and a consumer reading the matrix to decide whether to ask for
        cs NER would read the first as a maybe."""
        assert not [r for r in rows(emulation=False) if r["engine"] == EMULATED_ENGINE_NAME]
        assert not [
            r for r in rows(emulation=False) if (r["language"], r["op"].value) == ("cs", "NER")
        ]

    def test_the_other_rows_are_untouched_either_way(self):
        without = {(r["language"], r["op"].value): r["engine"] for r in rows(emulation=False)}
        with_it = {(r["language"], r["op"].value): r["engine"] for r in rows(emulation=True)}
        assert with_it.keys() - without.keys() == {("cs", "NER")}
        for key, engine in without.items():
            assert with_it[key] == engine


class TestTheExclusionPredicate:
    def test_emulation_off_excludes_nothing(self):
        """The sharper twin of the plan's gate clause: unaffected when OFF is
        what makes "explicitly reduced when ON" mean anything."""
        assert emulated_routes(rows(emulation=False)) == set()
        assert (
            exclusions(
                cases=[("cs-ner-1", "cs", ["NER"]), ("cs-lemma-1", "cs", ["LEMMATIZE"])],
                emulated=emulated_routes(rows(emulation=False)),
            )
            == []
        )

    def test_emulation_on_excludes_the_asserting_cases_by_name_with_a_reason(self):
        skipped = exclusions(
            cases=[
                ("cs-ner-1", "cs", ["NER"]),
                ("cs-lemma-1", "cs", ["LEMMATIZE"]),
                ("en-ner-1", "en", ["NER"]),
            ],
            emulated=emulated_routes(rows(emulation=True)),
        )
        assert skipped == [Exclusion(case_id="cs-ner-1", reason="emulated: NER/cs")]

    def test_one_emulated_op_excludes_a_case_that_asserts_several(self):
        """A case's verdict is a conjunction. Excluding only when EVERY asserted
        op is emulated would let a case asserting lemmas and entities pass on the
        strength of the lemmas, with the emulated half riding along unremarked —
        which is exactly the silent pass this exists to prevent."""
        skipped = exclusions(
            cases=[("mixed", "cs", ["LEMMATIZE", "NER"])],
            emulated=emulated_routes(rows(emulation=True)),
        )
        assert [e.case_id for e in skipped] == ["mixed"]
        assert skipped[0].reason == "emulated: NER/cs"

    def test_the_language_matters(self):
        """`NER.en` is not emulated here, and a case asserting it must run."""
        assert (
            exclusions(
                cases=[("en-ner", "en", ["NER"])],
                emulated=emulated_routes(rows(emulation=True)),
            )
            == []
        )

    def test_the_predicate_reads_the_matrix_not_the_table(self):
        """A routing entry whose engine is not registered produces no row, so a
        case must not be excluded by an aspiration. (The registry refuses such a
        config outright — p8-2 — which is why this can be stated as an invariant
        of the matrix rather than defended in the predicate.)"""
        assert emulated_routes([]) == set()
