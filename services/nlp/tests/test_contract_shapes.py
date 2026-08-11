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

**NLS-P3.1.T2/T6 — this file is now the executable copy of the contract.**
`WIRE_SHAPES` below holds every message's fields as literals: name, number, and
type. Two jobs, and the first is the reason it exists.

*The additive gate.* NLS adds a pipeline surface to a proto three other services
already speak (Themis, Echo, kantheon). "Additive" is easy to claim and easy to
break by accident — renumber a field while inserting one above it, widen an
`int32`, rename a field whose JSON name someone depends on — and every one of
those breaks a running consumer silently, because protobuf's whole design is to
decode whatever it can. Freezing the numbers as literals means the diff has to
be *written down* to pass, which is exactly the review moment such a change
needs.

*The contract copy.* The new messages are asserted field-by-field against
contracts §2, so the document and the wire cannot drift apart without a test
saying so.
"""

from __future__ import annotations

import pytest
from google.protobuf.descriptor import FieldDescriptor

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
    def test_the_original_three_rpcs_are_still_bound(self):
        # NLS-P3.1 added three more (see TestServiceSurface). These three are the
        # ones Themis, Echo and kantheon are deployed against.
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


# ---------------------------------------------------------------------------
# NLS-P3.1.T2/T6 — the frozen wire shapes
# ---------------------------------------------------------------------------

#: Messages that existed BEFORE the NLS additions. Their field numbers are load
#: bearing for Themis, Echo and kantheon, all of which are deployed against them.
PRE_NLS_MESSAGES = (
    "Token",
    "Span",
    "NerEntity",
    "EngineResult",
    "AnalyzeRequest",
    "AnalyzeResponse",
    "EngineVersion",
    "BatchLemmatizeRequest",
    "LemmaList",
    "BatchLemmatizeResponse",
    "StatusRequest",
    "Capability",
    "StatusResponse",
)

#: Message -> [(field name, number, type)]. `repeated ` prefixes a repeated
#: field; a map field shows as `repeated <Name>Entry`, which is protobuf's own
#: representation of one, not an artefact of this test.
WIRE_SHAPES: dict[str, list[tuple[str, int, str]]] = {
    # ── pre-NLS: DO NOT EDIT except to add a field with a new number ─────────
    "Token": [
        ("text", 1, "string"),
        ("char_start", 2, "int32"),
        ("char_end", 3, "int32"),
        ("lemma", 4, "string"),
        ("upos", 5, "string"),
        ("xpos", 6, "string"),
        ("feats", 7, "repeated FeatsEntry"),
        ("dep_head", 8, "int32"),
        ("dep_relation", 9, "string"),
    ],
    "Span": [
        ("char_start", 1, "int32"),
        ("char_end", 2, "int32"),
    ],
    "NerEntity": [
        ("text", 1, "string"),
        ("label", 2, "string"),
        ("char_start", 3, "int32"),
        ("char_end", 4, "int32"),
        ("normalized_value", 5, "string"),
        ("source_engine", 6, "string"),
    ],
    "EngineResult": [
        ("tokens", 1, "repeated Token"),
        ("entities", 2, "repeated NerEntity"),
        ("sentences", 3, "repeated Span"),
        ("paragraphs", 4, "repeated Span"),
        ("error", 5, "string"),
    ],
    "AnalyzeRequest": [
        ("text", 1, "string"),
        ("language", 2, "string"),
        ("ops", 3, "repeated NlpOp"),
        ("mode", 4, "Mode"),
        ("engine_hints", 5, "repeated EngineHintsEntry"),
    ],
    "AnalyzeResponse": [
        ("language", 1, "string"),
        ("language_confidence", 2, "double"),
        ("engine_used", 3, "string"),
        ("tokens", 4, "repeated Token"),
        ("sentences", 5, "repeated Span"),
        ("paragraphs", 6, "repeated Span"),
        ("entities", 7, "repeated NerEntity"),
        ("by_engine", 8, "repeated ByEngineEntry"),
        ("trace_id", 9, "string"),
        ("elapsed_ms", 10, "int64"),
        ("detected_language", 11, "string"),
        ("used", 12, "repeated EngineVersion"),
        ("messages", 99, "repeated ResponseMessage"),
    ],
    "EngineVersion": [
        ("op", 1, "string"),
        ("engine", 2, "string"),
        ("model", 3, "string"),
        ("model_version", 4, "string"),
    ],
    "BatchLemmatizeRequest": [
        ("texts", 1, "repeated string"),
        ("language", 2, "string"),
    ],
    "LemmaList": [
        ("lemmas", 1, "repeated string"),
    ],
    "BatchLemmatizeResponse": [
        ("results", 1, "repeated LemmaList"),
        ("used", 2, "repeated EngineVersion"),
    ],
    "StatusRequest": [],
    "Capability": [
        ("language", 1, "string"),
        ("op", 2, "NlpOp"),
        ("engine", 3, "string"),
        ("model_version", 4, "string"),
        ("tier", 5, "Tier"),
    ],
    # Fields 1-2 are pre-NLS; 3-5 are the §2.4 additions.
    "StatusResponse": [
        ("ready", 1, "bool"),
        ("capabilities", 2, "repeated Capability"),
        ("lane", 3, "string"),
        ("pipelines", 4, "repeated PipelineInfo"),
        ("pack_state", 5, "PackState"),
    ],
    # ── NLS additions (contracts §2.1-§2.4) ─────────────────────────────────
    "FeatureValue": [
        ("string_value", 1, "string"),
        ("number_value", 2, "double"),
        ("bool_value", 3, "bool"),
        ("list_value", 4, "FeatureValueList"),
    ],
    "FeatureValueList": [
        ("items", 1, "repeated FeatureValue"),
    ],
    "Annotation": [
        ("id", 1, "uint64"),
        ("type", 2, "string"),
        ("char_start", 3, "int32"),
        ("char_end", 4, "int32"),
        ("features", 5, "repeated FeaturesEntry"),
    ],
    "AnnotationSet": [
        ("name", 1, "string"),
        ("annotations", 2, "repeated Annotation"),
    ],
    "AnnotatedDocument": [
        ("text", 1, "string"),
        ("annotation_sets", 2, "repeated AnnotationSet"),
        ("document_features", 3, "repeated DocumentFeaturesEntry"),
    ],
    "RunPipelineRequest": [
        ("text", 1, "string"),
        ("language", 2, "string"),
        ("pipeline", 3, "string"),
        ("include_sets", 4, "repeated string"),
        ("include_types", 5, "repeated string"),
    ],
    "RunPipelineResponse": [
        ("document", 1, "AnnotatedDocument"),
        ("language", 2, "string"),
        ("language_confidence", 3, "double"),
        ("used", 4, "repeated EngineVersion"),
        ("phases", 5, "repeated PhaseTrace"),
        ("trace_id", 6, "string"),
        ("elapsed_ms", 7, "int64"),
        ("messages", 99, "repeated ResponseMessage"),
    ],
    "PhaseTrace": [
        ("phase", 1, "string"),
        ("kind", 2, "string"),
        ("annotations_added", 3, "int32"),
        ("elapsed_ms", 4, "int64"),
    ],
    "ReloadPacksRequest": [],
    "ReloadPacksResponse": [
        ("applied", 1, "bool"),
        ("state_id", 2, "string"),
        ("diagnostics", 3, "repeated PackDiagnostic"),
    ],
    "PackDiagnostic": [
        ("source", 1, "string"),
        ("pack", 2, "string"),
        ("severity", 3, "string"),
        ("code", 4, "string"),
        ("message", 5, "string"),
    ],
    "PipelineInfo": [
        ("name", 1, "string"),
        ("steps", 2, "repeated string"),
    ],
    "PackState": [
        ("state_id", 1, "string"),
        ("packs_loaded", 2, "int32"),
        ("lists_loaded", 3, "int32"),
        ("diagnostics", 4, "repeated PackDiagnostic"),
    ],
    # ── LM (cz-lemma contracts §6, riding this window per ⚑LMP-D3) ──────────
    "ReportTokenRequest": [
        ("world", 1, "string"),
        ("token", 2, "string"),
        ("verdict", 3, "string"),
        ("context_span", 4, "string"),
        ("language", 5, "string"),
    ],
    "ReportTokenResponse": [
        ("accepted", 1, "bool"),
    ],
}

RPCS = (
    "Analyze",
    "BatchLemmatize",
    "GetStatus",
    "RunPipeline",
    "ReloadPacks",
    "ReportToken",
)

_SCALARS = {
    getattr(FieldDescriptor, name): name.removeprefix("TYPE_").lower()
    for name in dir(FieldDescriptor)
    if name.startswith("TYPE_")
}


def shape_of(message_name: str) -> list[tuple[str, int, str]]:
    """A message's fields as (name, number, type) — read from the descriptor."""
    descriptor = getattr(nlp_pb2, message_name).DESCRIPTOR
    shape = []
    for field in descriptor.fields:
        if field.type == FieldDescriptor.TYPE_MESSAGE:
            type_name = field.message_type.name
        elif field.type == FieldDescriptor.TYPE_ENUM:
            type_name = field.enum_type.name
        else:
            type_name = _SCALARS[field.type]
        # `is_repeated` rather than `label ==`: protobuf deprecated `label`, and a
        # freeze test that emits a hundred DeprecationWarnings trains people to
        # ignore this file's output.
        if field.is_repeated:
            type_name = f"repeated {type_name}"
        shape.append((field.name, field.number, type_name))
    return shape


class TestFrozenWireShapes:
    """T2 — the additive gate. Nothing here may change without editing a literal."""

    @pytest.mark.parametrize("message", sorted(WIRE_SHAPES))
    def test_shape_matches_the_frozen_literal(self, message):
        assert shape_of(message) == WIRE_SHAPES[message], (
            f"{message}'s wire shape changed. If this is an intentional ADDITIVE "
            "change, add the new field to WIRE_SHAPES with its new number. If a "
            "number, name or type of an EXISTING field moved, stop: Themis, Echo "
            "and kantheon are deployed against those."
        )

    def test_every_message_in_the_proto_is_frozen(self):
        """A message added later must be added here too, or the gate quietly
        stops covering the newest thing in the file — which is always the thing
        most likely to be wrong."""
        declared = {
            name
            for name, value in vars(nlp_pb2).items()
            if hasattr(value, "DESCRIPTOR") and hasattr(value.DESCRIPTOR, "fields")
        }
        assert declared == set(WIRE_SHAPES)

    def test_the_pre_nls_messages_are_all_still_there(self):
        """Belt to the braces above: a *deleted* message would satisfy
        "every message is frozen" by simply not being in either set."""
        assert set(PRE_NLS_MESSAGES) <= set(WIRE_SHAPES)
        for message in PRE_NLS_MESSAGES:
            assert hasattr(nlp_pb2, message)

    def test_the_status_response_additions_did_not_disturb_what_was_there(self):
        """The one pre-existing message NLS extends. contracts §1 promises
        Analyze/BatchLemmatize/GetStatus stay byte-untouched, and this is where
        that promise is most easily broken by hand."""
        assert shape_of("StatusResponse")[:2] == [
            ("ready", 1, "bool"),
            ("capabilities", 2, "repeated Capability"),
        ]

    def test_the_rule_6_message_slot_stayed_at_99(self):
        """Rule 6 puts ResponseMessage at 99 on every response that has one, so a
        reader can find it without consulting the file. RunPipelineResponse is new
        and had to choose; it chose the same."""
        for response in ("AnalyzeResponse", "RunPipelineResponse"):
            numbers = {name: number for name, number, _ in WIRE_SHAPES[response]}
            assert numbers["messages"] == 99


class TestPipelineSurfaceShapes:
    """T6 — the new messages, exercised rather than only described."""

    def test_an_annotated_document_round_trips_with_nested_features(self):
        doc = nlp_pb2.AnnotatedDocument(
            text="Zobraz všechny faktury od zákazníka Microsoft",
            annotation_sets=[
                nlp_pb2.AnnotationSet(
                    name="",
                    annotations=[
                        nlp_pb2.Annotation(
                            id=7,
                            type="Lookup",
                            char_start=15,
                            char_end=22,
                            features={
                                "entity": nlp_pb2.FeatureValue(string_value="faktura"),
                                "confidence_free": nlp_pb2.FeatureValue(bool_value=True),
                                "refs": nlp_pb2.FeatureValue(
                                    list_value=nlp_pb2.FeatureValueList(
                                        items=[
                                            nlp_pb2.FeatureValue(string_value="a"),
                                            nlp_pb2.FeatureValue(number_value=2.5),
                                        ]
                                    )
                                ),
                            },
                        )
                    ],
                )
            ],
            document_features={"language": nlp_pb2.FeatureValue(string_value="cs")},
        )
        parsed = nlp_pb2.AnnotatedDocument.FromString(doc.SerializeToString())

        (annset,) = parsed.annotation_sets
        assert annset.name == ""  # the default set survives being the empty string
        (annotation,) = annset.annotations
        assert (annotation.id, annotation.type) == (7, "Lookup")
        assert (annotation.char_start, annotation.char_end) == (15, 22)
        assert annotation.features["entity"].string_value == "faktura"
        assert annotation.features["confidence_free"].bool_value is True
        nested = annotation.features["refs"].list_value.items
        assert [nested[0].string_value, nested[1].number_value] == ["a", 2.5]
        assert parsed.document_features["language"].string_value == "cs"

    def test_a_feature_value_holds_exactly_one_kind(self):
        """The oneof is the point: features are data with a small closed domain
        (P-2), not a Struct that can carry anything."""
        value = nlp_pb2.FeatureValue(string_value="x")
        assert value.WhichOneof("kind") == "string_value"
        value.number_value = 1.0
        assert value.WhichOneof("kind") == "number_value"
        assert value.string_value == ""  # setting one clears the other

    def test_run_pipeline_request_round_trips_with_its_filters(self):
        req = nlp_pb2.RunPipelineRequest(
            text="Najdi roli obchodní zástupce",
            language="cs",
            pipeline="query-patterns",
            include_sets=[""],
            include_types=["QueryPattern", "Lookup"],
        )
        parsed = nlp_pb2.RunPipelineRequest.FromString(req.SerializeToString())
        assert parsed.pipeline == "query-patterns"
        assert list(parsed.include_types) == ["QueryPattern", "Lookup"]
        assert list(parsed.include_sets) == [""]

    def test_run_pipeline_response_carries_traces_and_the_rule_6_slot(self):
        resp = nlp_pb2.RunPipelineResponse(
            document=nlp_pb2.AnnotatedDocument(text="x"),
            language="cs",
            phases=[
                nlp_pb2.PhaseTrace(
                    phase="TOKENIZE", kind="engine", annotations_added=6, elapsed_ms=12
                ),
                nlp_pb2.PhaseTrace(
                    phase="dfp-entity-aliases",
                    kind="gazetteer",
                    annotations_added=2,
                    elapsed_ms=1,
                ),
                nlp_pb2.PhaseTrace(
                    phase="query-match", kind="rules", annotations_added=1, elapsed_ms=3
                ),
            ],
            used=[
                nlp_pb2.EngineVersion(
                    op="TOKENIZE",
                    engine="stanza",
                    model="stanza-cs",
                    model_version="1.10.0",
                )
            ],
        )
        parsed = nlp_pb2.RunPipelineResponse.FromString(resp.SerializeToString())
        assert [p.kind for p in parsed.phases] == ["engine", "gazetteer", "rules"]
        assert parsed.used[0].model  # S-1 holds on the new response too
        assert parsed.document.text == "x"

    def test_reload_packs_response_carries_diagnostics_in_the_wheel_s_shape(self):
        """Field-for-field `ttrnlp.packs.diag.Diagnostic`. A pack author reading a
        CLI error and an operator reading this response see the same text."""
        resp = nlp_pb2.ReloadPacksResponse(
            applied=False,
            state_id="0123456789abcdef",
            diagnostics=[
                nlp_pb2.PackDiagnostic(
                    source="/etc/nlp/packs/dfp.pack.yaml",
                    pack="dfp-query-patterns",
                    severity="ERROR",
                    code="NLS-PACK-002",
                    message="$.phases[query-match]: matches `Lookup`, which is "
                    "not in `input:`",
                )
            ],
        )
        parsed = nlp_pb2.ReloadPacksResponse.FromString(resp.SerializeToString())
        assert parsed.applied is False
        assert parsed.state_id == "0123456789abcdef"
        assert parsed.diagnostics[0].code == "NLS-PACK-002"

    def test_reload_packs_takes_no_arguments(self):
        """NL-15: sources come from config, never from the request. A reload that
        could be pointed anywhere is a remote code-load with extra steps."""
        assert WIRE_SHAPES["ReloadPacksRequest"] == []

    def test_status_response_reports_lane_pipelines_and_pack_state(self):
        resp = nlp_pb2.StatusResponse(
            ready=True,
            lane="option",
            pipelines=[
                nlp_pb2.PipelineInfo(
                    name="query-patterns",
                    steps=["TOKENIZE", "LEMMATIZE", "dfp-entity-aliases", "query-match"],
                )
            ],
            pack_state=nlp_pb2.PackState(
                state_id="0123456789abcdef", packs_loaded=2, lists_loaded=1
            ),
        )
        parsed = nlp_pb2.StatusResponse.FromString(resp.SerializeToString())
        assert parsed.lane == "option"
        assert parsed.pipelines[0].name == "query-patterns"
        assert (parsed.pack_state.packs_loaded, parsed.pack_state.lists_loaded) == (2, 1)
        assert parsed.pack_state.diagnostics == []  # empty unless the boot load failed

    def test_report_token_round_trips(self):
        """LM's addition, riding this window (⚑LMP-D3). The servicer lands at
        NLS-P9; the shape is frozen now so there is one proto diff, not two."""
        req = nlp_pb2.ReportTokenRequest(
            world="dfp", token="fakturami", verdict="miss", language="cs"
        )
        parsed = nlp_pb2.ReportTokenRequest.FromString(req.SerializeToString())
        assert (parsed.world, parsed.token, parsed.verdict) == (
            "dfp",
            "fakturami",
            "miss",
        )
        # Opt-in per world config; absent by default, and absent is the empty
        # string rather than a sentinel.
        assert parsed.context_span == ""
        assert nlp_pb2.ReportTokenResponse(accepted=False).accepted is False


class TestServiceSurface:
    def test_the_service_exposes_all_six_rpcs(self):
        servicer = nlp_pb2_grpc.NlpServiceServicer
        for rpc in RPCS:
            assert hasattr(servicer, rpc), f"{rpc} missing from the servicer"
            assert hasattr(nlp_pb2_grpc.NlpServiceStub, "__init__")

    def test_no_rpc_was_removed_or_renamed(self):
        """The three original rpcs are contract for deployed consumers; the three
        new ones are contract from here on."""
        methods = {
            name
            for name in dir(nlp_pb2_grpc.NlpServiceServicer)
            if not name.startswith("_")
        }
        assert methods == set(RPCS)
