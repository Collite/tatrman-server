# morph-studio — the LM editorial service (⚑LMP-D5).
#
# One instance per world (LM-5): `MORPH_WORLD` is required and the container
# refuses to start without it.
#
# Build from the repo root — the uv path dependencies (`ttr-morph`, `ttr-nlp`)
# resolve there and nowhere else:
#   docker build -t morph-studio:dev -f services/morph-studio/Dockerfile .
#
# ⚑ `ttr-morph` is NOT on PyPI (⚑LMP-D4) and neither is this image's ability to
# build outside the monorepo. That is deliberate: the studio proposes what the
# compiler will accept, which means running the compiler's own code.

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

WORKDIR /build/services/morph-studio
# The two workspace libraries this service is built on. `psycopg[binary]` means
# no libpq build stage — the wheel carries it.
COPY shared/libs/python/ttr-nlp /build/shared/libs/python/ttr-nlp
COPY shared/libs/python/ttr-morph /build/shared/libs/python/ttr-morph
COPY services/morph-studio/pyproject.toml services/morph-studio/uv.lock ./
RUN pip install --upgrade pip && pip install uv && uv sync --frozen

# ===== the FI-7 frontend (NLS-P9.3) =====
#
# Built here and served by the backend — one image, one process, one port. An
# nginx sidecar would buy a chart, a Service, an ingress rule and a CORS policy
# in order to serve files the API process can already read.
#
# `npm ci` needs the lockfile and nothing else from the repo, so this layer is
# cached across every backend change.
FROM node:24-slim AS frontend

WORKDIR /fe
COPY services/morph-studio/frontend/package.json services/morph-studio/frontend/package-lock.json ./
RUN npm ci
# ⚑ Named, not `COPY frontend/ ./`. The build context is the repo root and has
# no .dockerignore, so a wildcard would drag a developer's `node_modules` and
# `dist` into the image layer — hundreds of megabytes, and a `dist` from
# whenever somebody last ran the build locally.
COPY services/morph-studio/frontend/index.html services/morph-studio/frontend/env.d.ts ./
COPY services/morph-studio/frontend/vite.config.ts services/morph-studio/frontend/tsconfig*.json ./
COPY services/morph-studio/frontend/src ./src
RUN npm run build-only

# ===== runtime =====
FROM base AS runtime

RUN useradd -ms /bin/bash appuser
WORKDIR /app

COPY --from=deps /usr/local /usr/local
COPY --from=deps /build/services/morph-studio/.venv /build/services/morph-studio/.venv
COPY --from=deps /build/shared /build/shared

USER appuser
COPY --chown=appuser:appuser services/morph-studio/pyproject.toml \
                             services/morph-studio/README.md \
                             services/morph-studio/alembic.ini ./
COPY --chown=appuser:appuser services/morph-studio/src ./src
COPY --chown=appuser:appuser services/morph-studio/migrations ./migrations
COPY --from=frontend --chown=appuser:appuser /fe/dist ./frontend/dist

ENV PATH="/build/services/morph-studio/.venv/bin:${PATH}"
ENV PYTHONPATH=/app/src
ENV MORPH_STUDIO_HOST=0.0.0.0 \
    MORPH_STUDIO_PORT=7290 \
    MORPH_STUDIO_EXPORT_DIR=/var/lib/morph-studio/export \
    MORPH_STUDIO_STATIC_DIR=/app/frontend/dist

EXPOSE 7290

# ⚑ The schema is alembic's, not the service's: `alembic upgrade head` runs as a
# migration job (or an init container), and the app never creates tables. A
# service that quietly created them on boot would let a missing migration go
# unnoticed until the first environment that had an older database.
HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 \
  CMD curl -fsS http://127.0.0.1:${MORPH_STUDIO_PORT:-7290}/healthz || exit 1

CMD ["python", "src/main.py"]
