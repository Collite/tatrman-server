# SPDX-License-Identifier: Apache-2.0
"""RG-P1.S2.T5 — the CNEC → universal label trap (cross-link E).

NameTag 3's CNEC 2.0 tagset is fine-grained (`gu` = city, `gc` = country, `pf`
= first name, `oa` = product/artifact, …). Downstream (the resolver's universal
path) wants coarse universal classes. The adapter maps by the **leading class
letter** and — critically — **never silently drops** an entity: an unmapped
class collapses to MISC, not to nothing. This guards the known
leading-letter-map brittleness (a product like `Octavie` tagged `o*`/`P*` must
survive as MISC so the resolver's domain path can still catch it).
"""

from __future__ import annotations

from nlp_service.config import BackendConfig
from nlp_service.engines.base import NlpOp
from nlp_service.engines.nametag_engine import Nametag3Engine


def _engine() -> Nametag3Engine:
    return Nametag3Engine(
        BackendConfig(url="http://nametag3:8001/recognize", model="nametag3-czech-cnec2.0-240830")
    )


class TestCnecToUniversal:
    def test_geo_prefix_maps_to_location(self):
        eng = _engine()
        # NameTag vertical output: "pražských pobočkách" tagged as a geo entity.
        vertical = "pražských\tB-gu\npobočkách\tI-gu\n"
        ents = eng._parse_vertical(vertical, "v pražských pobočkách")
        assert len(ents) == 1
        assert ents[0].label == "LOCATION"
        assert ents[0].text == "pražských pobočkách"

    def test_person_prefix_maps_to_person(self):
        eng = _engine()
        ents = eng._parse_vertical("Novák\tB-ps\n", "Novák")
        assert ents[0].label == "PERSON"

    def test_institution_prefix_maps_to_organization(self):
        eng = _engine()
        ents = eng._parse_vertical("Shell\tB-if\n", "Shell")
        assert ents[0].label == "ORGANIZATION"

    def test_time_prefix_maps_to_date(self):
        eng = _engine()
        ents = eng._parse_vertical("čtvrtletí\tB-th\n", "čtvrtletí")
        assert ents[0].label == "DATE"

    def test_product_class_survives_as_misc_not_dropped(self):
        """`Octavie` (product; CNEC `o*`/`P*`) must NOT be silently dropped."""
        eng = _engine()
        ents = eng._parse_vertical("Octavie\tB-oa\n", "za Octavie")
        assert len(ents) == 1  # not dropped
        assert ents[0].label == "MISC"
        assert ents[0].text == "Octavie"

    def test_unknown_class_defaults_to_misc(self):
        eng = _engine()
        ents = eng._parse_vertical("Cosi\tB-zz\n", "Cosi")
        assert ents[0].label == "MISC"

    def test_raw_cnec_tag_preserved_for_downstream(self):
        """The fine CNEC tag is not lost — kept for the resolver's domain path."""
        eng = _engine()
        ents = eng._parse_vertical("pražských\tB-gu\n", "pražských")
        assert ents[0].normalized_value == "cnec:gu"

    def test_supports_only_ner(self):
        eng = _engine()
        assert eng.supports("cs", NlpOp.NER)
        assert not eng.supports("cs", NlpOp.LEMMATIZE)
