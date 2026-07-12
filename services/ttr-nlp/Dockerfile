# ttr-nlp engine-free FRONT — multi-stage Dockerfile.
#
# RG-P1: the front is the gRPC contract server (NlpService on :7271) + a REST
# dev/health mirror (:7270). It owns its generated org.tatrman.{nlp,common}.v1
# Python stubs (produced from the shared .proto by scripts/gen_proto.py at build
# — NOT the shared-proto package, which emits messages only and would collide).
#
# NOTE (S2): the model-bearing engines are now separate backend images (S2/S3);
# the front reaches them over HTTP. The Stanza/spaCy model bake below is dead
# weight the S3 engine-free-front task strips (with torch); kept here only so the
# image keeps building until then.
#
# Build from the repo root (uv path-deps + the proto tree resolve there):
#   docker build -t ttr-nlp:dev -f services/ttr-nlp/Dockerfile .

FROM python:3.13-slim-bookworm AS base

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# ===== dependencies layer =====
FROM base AS deps

WORKDIR /build/services/ttr-nlp
COPY shared/libs/python/otel-config /build/shared/libs/python/otel-config
COPY services/ttr-nlp/pyproject.toml services/ttr-nlp/uv.lock ./
RUN pip install --upgrade pip && pip install uv && uv sync --frozen

# ===== proto generation layer (org.tatrman.{nlp,common}.v1 stubs) =====
FROM deps AS protogen
ENV PATH="/build/services/ttr-nlp/.venv/bin:${PATH}"
# The canonical .proto source + the gen script.
COPY shared/proto/src/main/proto /build/shared/proto/src/main/proto
COPY services/ttr-nlp/scripts ./scripts
RUN python scripts/gen_proto.py   # → /build/services/ttr-nlp/generated/org/...

# ===== model download layer (dead weight until S3 strips it; see header) =====
FROM deps AS models
ENV PATH="/build/services/ttr-nlp/.venv/bin:${PATH}"
RUN mkdir -p /opt/nlp-models/stanza
RUN python - <<'PY'
import stanza
for lang in ["cs", "en"]:
    stanza.download(lang, model_dir="/opt/nlp-models/stanza")
PY
RUN python -m spacy download en_core_web_md

# ===== runtime layer (thin app layer) =====
FROM base AS runtime

RUN useradd -ms /bin/bash appuser
WORKDIR /app

COPY --from=deps /usr/local /usr/local
COPY --from=deps /build/services/ttr-nlp/.venv /build/services/ttr-nlp/.venv
COPY --from=deps /build/shared /build/shared
COPY --from=protogen /build/services/ttr-nlp/generated ./generated
COPY --from=models /opt/nlp-models /opt/nlp-models
RUN chown -R appuser:appuser /opt/nlp-models

USER appuser
COPY --chown=appuser:appuser services/ttr-nlp/pyproject.toml services/ttr-nlp/README.md ./
COPY --chown=appuser:appuser services/ttr-nlp/src ./src
COPY --chown=appuser:appuser services/ttr-nlp/config.yaml ./

ENV PATH="/build/services/ttr-nlp/.venv/bin:${PATH}"
# `generated/` carries the proto stubs; `src/` the app.
ENV PYTHONPATH=/app/src:/app/generated
ENV CONFIG_FILE=/app/config.yaml
ENV NLP_SERVICE_OTEL_PROTOCOL=grpc
ENV NLP_SERVICE_HOST=0.0.0.0 \
    NLP_SERVICE_PORT=7270 \
    NLP_SERVICE_GRPC_PORT=7271

EXPOSE 7270 7271

# Health via the REST mirror.
HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --retries=3 \
  CMD curl -fsS http://127.0.0.1:${NLP_SERVICE_PORT:-7270}/healthz || exit 1

# main.py runs BOTH servers on one loop: gRPC (:7271, the contract) + uvicorn
# REST mirror (:7270).
CMD ["python", "src/main.py"]
