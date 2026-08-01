# SPDX-License-Identifier: Apache-2.0
"""RG-P1.S1.T1 — contract-shape tests for `org.tatrman.nlp.v1`.

Written test-first against the not-yet-authored service surface: the
`NlpService` gRPC binding + the `BatchLemmatize` / `GetStatus` additions +
the S-1 `used[]` echo (contracts §1). Asserts the proto messages round-trip
and that the S-1 invariant (`used[]` populated, no blank `model`) is
expressible and checkable at the contract layer.

The *route-level* S-1 assertion (every route names a non-empty model) is
S1.T3; the *response-stamping* enforcement is S1.T6. This file only pins the
wire shapes and the pure invariant helper.
"""

from __future__ import annotations

from org.tatrman.nlp.v1 import nlp_pb2, nlp_pb2_grpc


class TestMessageShapes:
    def test_analyze_request_roundtrip(self):
        req = nlp_pb2.AnalyzeRequest(
            text="Kolik jsme utržili za Octavie?",
            language="cs",
            ops=[nlp_pb2.TOKENIZE, nlp_pb2.LEMMATIZE, nlp_pb2.NER],
            mode=nlp_pb2.NORMAL,
        )
        parsed = nlp_pb2.AnalyzeRequest.FromString(req.SerializeToString())
        assert parsed.text == req.text
        assert parsed.language == "cs"
        assert list(parsed.ops) == [nlp_pb2.TOKENIZE, nlp_pb2.LEMMATIZE, nlp_pb2.NER]
        assert parsed.mode == nlp_pb2.NORMAL

    def test_analyze_response_carries_used_engine_versions(self):
        resp = nlp_pb2.AnalyzeResponse(
            detected_language="cs",
            tokens=[
                nlp_pb2.Token(text="Octavie", lemma="Octavia", char_start=22, char_end=29)
            ],
            used=[
                nlp_pb2.EngineVersion(
                    op="LEMMATIZE",
                    engine="morphodita",
                    model="czech-morfflex2.0-pdtc1.0-220710",
                    model_version="220710",
                )
            ],
        )
        parsed = nlp_pb2.AnalyzeResponse.FromString(resp.SerializeToString())
        assert parsed.detected_language == "cs"
        assert parsed.tokens[0].lemma == "Octavia"
        assert len(parsed.used) == 1
        assert parsed.used[0].engine == "morphodita"
        assert parsed.used[0].model  # S-1: never blank on the wire

    def test_batch_lemmatize_request_roundtrip(self):
        req = nlp_pb2.BatchLemmatizeRequest(texts=["Octavie", "pobočkách"], language="cs")
        parsed = nlp_pb2.BatchLemmatizeRequest.FromString(req.SerializeToString())
        assert list(parsed.texts) == ["Octavie", "pobočkách"]
        assert parsed.language == "cs"

    def test_batch_lemmatize_response_is_positional(self):
        resp = nlp_pb2.BatchLemmatizeResponse(
            results=[
                nlp_pb2.LemmaList(lemmas=["Octavia"]),
                nlp_pb2.LemmaList(lemmas=["pobočka"]),
            ],
            used=[
                nlp_pb2.EngineVersion(
                    op="LEMMATIZE",
                    engine="morphodita",
                    model="czech-morfflex2.0-pdtc1.0-220710",
                    model_version="220710",
                )
            ],
        )
        parsed = nlp_pb2.BatchLemmatizeResponse.FromString(resp.SerializeToString())
        assert [list(r.lemmas) for r in parsed.results] == [["Octavia"], ["pobočka"]]
        assert parsed.used[0].model  # S-1

    def test_status_response_capability_matrix(self):
        resp = nlp_pb2.StatusResponse(
            ready=True,
            capabilities=[
                nlp_pb2.Capability(
                    language="cs",
                    op=nlp_pb2.LEMMATIZE,
                    engine="morphodita",
                    model_version="220710",
                    tier=nlp_pb2.SELF_HOSTED_PINNED,
                ),
                nlp_pb2.Capability(
                    language="cs",
                    op=nlp_pb2.NER,
                    engine="nametag3",
                    model_version="240830",
                    tier=nlp_pb2.SELF_HOSTED_PINNED,
                ),
            ],
        )
        parsed = nlp_pb2.StatusResponse.FromString(resp.SerializeToString())
        assert parsed.ready is True
        assert len(parsed.capabilities) == 2
        assert parsed.capabilities[0].op == nlp_pb2.LEMMATIZE
        assert parsed.capabilities[0].tier == nlp_pb2.SELF_HOSTED_PINNED

    def test_tier_enum_distinguishes_pinned_from_remote(self):
        assert nlp_pb2.SELF_HOSTED_PINNED != nlp_pb2.REMOTE_UNPINNED


class TestServiceStub:
    def test_service_exposes_three_rpcs(self):
        assert hasattr(nlp_pb2_grpc, "NlpServiceStub")
        assert hasattr(nlp_pb2_grpc, "NlpServiceServicer")
        assert hasattr(nlp_pb2_grpc, "add_NlpServiceServicer_to_server")
        servicer = nlp_pb2_grpc.NlpServiceServicer
        for rpc in ("Analyze", "BatchLemmatize", "GetStatus"):
            assert hasattr(servicer, rpc)


class TestS1Invariant:
    """S-1 at the contract layer: `used[]` populated, no blank `model`."""

    def test_flags_blank_model(self):
        from nlp_service.contract import iter_s1_violations

        used = [
            nlp_pb2.EngineVersion(
                op="NER", engine="nametag3", model="", model_version="240830"
            )
        ]
        assert list(iter_s1_violations(used))  # blank model → violation

    def test_flags_empty_used(self):
        from nlp_service.contract import iter_s1_violations

        assert list(iter_s1_violations([]))  # no engine echoed → violation

    def test_accepts_fully_populated(self):
        from nlp_service.contract import iter_s1_violations

        used = [
            nlp_pb2.EngineVersion(
                op="LEMMATIZE",
                engine="morphodita",
                model="czech-morfflex2.0-pdtc1.0-220710",
                model_version="220710",
            )
        ]
        assert not list(iter_s1_violations(used))
