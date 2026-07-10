# Kadmos (NLP foundation) — multi-stage Dockerfile.
#
# Image-size / build-time strategy (the Metis/prophet "cached base layer"
# pattern — metis/architecture.md §2 container row): the NLP engine models
# (Stanza cs+en, spaCy en_core_web_md) dominate image size and build time, so
# they live in a dedicated `models` stage that depends ONLY on the dependency
# layer (pyproject + uv.lock). App-source changes invalidate only the final
# thin `runtime` COPY layers — never the heavy model download. NameTag /
# MorphoDiTa run against the remote UFAL endpoints (config.yaml), so no local
# model is bundled for them.
#
# Build from the repo root so the uv path-deps (../../shared/...) resolve:
#   docker build -t kadmos:dev -f services/kadmos/Dockerfile .   (== `just deploy-py services/kadmos`)

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

# Mirror the repo layout inside the image so that the lockfile's relative
# path dependencies (e.g. ../../shared/libs/python/otel-config) resolve
# *within* the workspace root. uv refuses to normalize paths that escape
# the project root, so the project must live at the same depth as in the
# source repo.
WORKDIR /build/services/kadmos

# Copy local path dependencies (shared libs) so uv can resolve them
COPY shared/libs/python/otel-config /build/shared/libs/python/otel-config
COPY shared/proto/build/python-package /build/shared/proto/build/python-package

COPY services/kadmos/pyproject.toml services/kadmos/uv.lock ./
RUN pip install --upgrade pip && pip install uv && uv sync --frozen

# ===== model download layer (the cached base — see header) =====
FROM deps AS models

# Use the uv-managed venv so `stanza`, `spacy`, etc. are importable.
ENV PATH="/build/services/kadmos/.venv/bin:${PATH}"

# Create model directory
RUN mkdir -p /opt/nlp-models/stanza

# Download Stanza models (cs and en) at build time
# This avoids slow first-call downloads in production
RUN python - <<'PY'
import os
import stanza

model_dir = "/opt/nlp-models/stanza"
for lang in ["cs", "en"]:
    print(f"Downloading Stanza {lang} model...")
    stanza.download(lang, model_dir=model_dir)
    print(f"Stanza {lang} model downloaded")
PY

# Download spaCy en_core_web_md model
RUN python - <<'PY'
import subprocess
print("Downloading spaCy en_core_web_md model...")
subprocess.run(["python", "-m", "spacy", "download", "en_core_web_md"], check=True, capture_output=True)
print("spaCy model downloaded")
PY

# ===== runtime layer (thin app layer) =====
FROM base AS runtime

# Create non-root user
RUN useradd -ms /bin/bash appuser
USER appuser
WORKDIR /app

# Copy installed site-packages and the project virtualenv from deps layer.
# The venv contains editable installs whose `.pth` files reference paths
# under /build/shared/..., so we must preserve that tree at runtime too.
USER root
COPY --from=deps /usr/local /usr/local
COPY --from=deps /build/services/kadmos/.venv /build/services/kadmos/.venv
COPY --from=deps /build/shared /build/shared

# Copy pre-downloaded models and hand them to appuser. Stanza's Pipeline()
# init writes lock + temp files inside the model_dir even when the models
# are already present (see stanza/utils/atomic_save.py), so root-owned
# /opt/nlp-models would 13-EACCES at runtime under appuser.
COPY --from=models /opt/nlp-models /opt/nlp-models
RUN chown -R appuser:appuser /opt/nlp-models

# Set stanza model directory
ENV STANZA_RESOURCES_DIR=/opt/nlp-models/stanza

USER appuser

# Copy app source
COPY --chown=appuser:appuser services/kadmos/pyproject.toml services/kadmos/README.md ./
COPY --chown=appuser:appuser services/kadmos/src ./src
COPY --chown=appuser:appuser services/kadmos/config.yaml ./

# Environment variables
# Prepend the uv-managed venv so its site-packages (which contain the
# editable installs of the shared libs) are picked up at runtime.
ENV PATH="/build/services/kadmos/.venv/bin:${PATH}"
ENV PYTHONPATH=/app/src
ENV CONFIG_FILE=/app/config.yaml
ENV KADMOS_SERVICE_OTEL_PROTOCOL=grpc

# Networking and health
EXPOSE ${UVICORN_PORT:-7270}

# Container-level HEALTHCHECK using /healthz
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD curl -fsS http://127.0.0.1:${UVICORN_PORT:-7270}/healthz || exit 1

# Uvicorn startup
ENV UVICORN_HOST=0.0.0.0
ENV UVICORN_PORT=7270
CMD ["sh", "-c", "exec uvicorn kadmos_service.api.routes:app --host ${UVICORN_HOST:-0.0.0.0} --port ${UVICORN_PORT:-7270}"]
