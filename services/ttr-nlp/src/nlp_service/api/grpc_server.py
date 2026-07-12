# SPDX-License-Identifier: Apache-2.0
"""gRPC service binding for `org.tatrman.nlp.v1.NlpService` (RG-P1.S1).

gRPC is the service contract; the FastAPI app is a dev/health mirror. This
servicer translates proto ⇄ the orchestrator and stamps the S-1 `used[]` echo
on every response. Async (grpc.aio) so it co-hosts with uvicorn on one loop.
"""

from __future__ import annotations

import logging
from typing import Set

import grpc

from org.tatrman.common.v1 import response_message_pb2 as common_pb2
from org.tatrman.nlp.v1 import nlp_pb2, nlp_pb2_grpc

from nlp_service.config import AppConfig, load_config
from nlp_service.engines import EngineRegistry
from nlp_service.engines.base import EngineVersion, NlpOp
from nlp_service.pipeline.orchestrator import AnalyzeResponse, Orchestrator

logger = logging.getLogger(__name__)

_SEVERITY = {
    "INFO": common_pb2.INFO,
    "WARNING": common_pb2.WARNING,
    "ERROR": common_pb2.ERROR,
}
_MODE = {nlp_pb2.MODE_UNSPECIFIED: "NORMAL", nlp_pb2.NORMAL: "NORMAL", nlp_pb2.COMPARE: "COMPARE"}


def _op_from_proto(op_int: int) -> NlpOp | None:
    name = nlp_pb2.NlpOp.Name(op_int)
    if name == "NLP_OP_UNSPECIFIED":
        return None
    try:
        return NlpOp(name)
    except ValueError:
        return None


def _op_to_proto(name: str) -> int:
    return nlp_pb2.NlpOp.Value(name)


class NlpServicer(nlp_pb2_grpc.NlpServiceServicer):
    def __init__(self, config: AppConfig | None = None, registry: EngineRegistry | None = None):
        self._config = config or load_config()
        self._registry = registry or EngineRegistry(self._config)
        self._orchestrator = Orchestrator(self._config, self._registry)

    # ---- Analyze ----------------------------------------------------------

    async def Analyze(self, request, context):  # noqa: N802 (gRPC method name)
        ops: Set[NlpOp] = set()
        for op_int in request.ops:
            op = _op_from_proto(op_int)
            if op is not None:
                ops.add(op)
        if not ops:
            await context.abort(grpc.StatusCode.INVALID_ARGUMENT, "at least one op required")

        result = self._orchestrator.analyze(
            text=request.text,
            language=request.language,
            ops=ops,
            mode=_MODE.get(request.mode, "NORMAL"),
            engine_hints=dict(request.engine_hints),
        )
        return self._to_analyze_proto(result)

    def _to_analyze_proto(self, r: AnalyzeResponse) -> "nlp_pb2.AnalyzeResponse":
        resp = nlp_pb2.AnalyzeResponse(
            language=r.language,
            detected_language=r.language,
            language_confidence=r.language_confidence,
            engine_used=r.engine_used,
            trace_id=r.trace_id,
            elapsed_ms=r.elapsed_ms,
        )
        for t in r.tokens:
            resp.tokens.append(
                nlp_pb2.Token(
                    text=t.text, char_start=t.char_start, char_end=t.char_end,
                    lemma=t.lemma, upos=t.upos, xpos=t.xpos, feats=t.feats,
                    dep_head=t.dep_head, dep_relation=t.dep_relation,
                )
            )
        for s in r.sentences:
            resp.sentences.append(nlp_pb2.Span(char_start=s[0], char_end=s[1]))
        for p in r.paragraphs:
            resp.paragraphs.append(nlp_pb2.Span(char_start=p[0], char_end=p[1]))
        for e in r.entities:
            resp.entities.append(
                nlp_pb2.NerEntity(
                    text=e.text, label=e.label, char_start=e.char_start, char_end=e.char_end,
                    normalized_value=e.normalized_value, source_engine=e.source_engine,
                )
            )
        for ev in r.used:
            resp.used.append(_engine_version_proto(ev))
        for m in r.messages:
            resp.messages.append(
                common_pb2.ResponseMessage(
                    severity=_SEVERITY.get(m.get("severity", "INFO"), common_pb2.INFO),
                    code=m.get("code", ""),
                    human_message=m.get("message", ""),
                )
            )
        return resp

    # ---- BatchLemmatize ---------------------------------------------------

    async def BatchLemmatize(self, request, context):  # noqa: N802
        outcome = self._orchestrator.batch_lemmatize(list(request.texts), request.language)
        resp = nlp_pb2.BatchLemmatizeResponse()
        for lemmas in outcome.results:
            resp.results.append(nlp_pb2.LemmaList(lemmas=lemmas))
        for ev in outcome.used:
            resp.used.append(_engine_version_proto(ev))
        return resp

    # ---- GetStatus --------------------------------------------------------

    async def GetStatus(self, request, context):  # noqa: N802
        resp = nlp_pb2.StatusResponse(ready=self._registry.is_ready())
        for row in self._registry.capability_matrix():
            resp.capabilities.append(
                nlp_pb2.Capability(
                    language=row["language"],
                    op=_op_to_proto(row["op"].value),
                    engine=row["engine"],
                    model_version=row["model_version"],
                    tier=_tier_to_proto(row["tier"]),
                )
            )
        return resp


def _engine_version_proto(ev: EngineVersion) -> "nlp_pb2.EngineVersion":
    return nlp_pb2.EngineVersion(
        op=ev.op, engine=ev.engine, model=ev.model, model_version=ev.model_version
    )


def _tier_to_proto(tier: str) -> int:
    return {
        "SELF_HOSTED_PINNED": nlp_pb2.SELF_HOSTED_PINNED,
        "REMOTE_UNPINNED": nlp_pb2.REMOTE_UNPINNED,
    }.get(tier, nlp_pb2.TIER_UNSPECIFIED)


async def serve_grpc(config: AppConfig, registry: EngineRegistry | None = None) -> grpc.aio.Server:
    """Create + start the async gRPC server. Caller awaits termination."""
    server = grpc.aio.server()
    nlp_pb2_grpc.add_NlpServiceServicer_to_server(NlpServicer(config, registry), server)
    listen = f"{config.service.host}:{config.service.grpc_port}"
    server.add_insecure_port(listen)
    await server.start()
    logger.info("ttr-nlp gRPC listening on %s", listen)
    return server
