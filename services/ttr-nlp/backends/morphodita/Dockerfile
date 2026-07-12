# RG-P1.S2.T2 — MorphoDiTa self-hosted backend (SELF_HOSTED_PINNED tier).
#
# Builds UFAL MorphoDiTa's `rest_server` (`morphodita_server`) from source and
# bakes the PINNED model `czech-morfflex2.0-pdtc1.0-220710` (= czech-morfflex2.0+pdtc,
# LINDAT hdl:11234/1-4794). The server is launched with an EXPLICIT model id
# (S-1 — no empty/default model). CPU-only, featherweight (~250 MB RSS; Q-10 §3).
#
# ARCH: build for the x86 cluster — `docker buildx build --platform linux/amd64`.
# MorphoDiTa v1.11.3's build hard-codes x86 SSE flags; targeting amd64 (native or
# QEMU) avoids the native-aarch64 patch the Q-10 spike needed for arm64 builds.
#
# Model redistribution (FI-4): the model is CC BY-NC-SA 4.0 — baking/running is
# fine; PUBLISHING this image to a public registry is the gated legal item.
#
#   docker buildx build --platform linux/amd64 \
#     -t ttr-nlp-morphodita:dev services/ttr-nlp/backends/morphodita

# ===== build stage: compile morphodita_server =====
FROM debian:bookworm-slim AS build

ARG MORPHODITA_VERSION=v1.11.3
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential git ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /src
RUN git clone --depth 1 --branch ${MORPHODITA_VERSION} https://github.com/ufal/morphodita .
# `make server` builds src/rest_server/morphodita_server (the same REST server
# that backs the LINDAT service — so the wire protocol matches the front's
# MorphoDiTa adapter and the Q-10 parity fixtures).
RUN cd src && make -j"$(nproc)" server

# ===== model stage: fetch + verify the pinned tagger =====
FROM debian:bookworm-slim AS model

ARG MODEL_ID=czech-morfflex2.0-pdtc1.0-220710
# LINDAT hdl:11234/1-4794. NOTE: LINDAT is now a DSpace-7 SPA — the legacy
# `/xmlui/bitstream/handle/...zip` URL returns an HTML page, NOT the file. Pass
# the resolved DSpace REST bitstream-content URL as a build-arg (the working URL
# is recorded in the ai-platform Q-10 harness, infra/nlp/spikes/q10):
#   --build-arg MODEL_URL='https://lindat.mff.cuni.cz/repository/server/api/core/bitstreams/<uuid>/content'
# Fill MODEL_SHA256 from the first verified download to pin it (digest-pin).
ARG MODEL_URL=REPLACE_WITH_DSPACE_BITSTREAM_URL
ARG MODEL_SHA256=REPLACE_WITH_VERIFIED_DIGEST

RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl unzip file \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /models
RUN test "${MODEL_URL}" != "REPLACE_WITH_DSPACE_BITSTREAM_URL" \
      || { echo "ERROR: pass --build-arg MODEL_URL=<DSpace bitstream-content URL> (see header)"; exit 1; } \
    && curl -fsSL "${MODEL_URL}" -o model.zip \
    # Fail LOUDLY if LINDAT handed back the SPA instead of the archive.
    && if ! file model.zip | grep -qi zip; then \
         echo "ERROR: downloaded file is not a zip ($(file -b model.zip)) — wrong MODEL_URL (DSpace SPA?)"; exit 1; fi \
    && if [ "${MODEL_SHA256}" != "REPLACE_WITH_VERIFIED_DIGEST" ]; then \
         echo "${MODEL_SHA256}  model.zip" | sha256sum -c -; \
       else echo "WARN: MODEL_SHA256 not pinned — set it after the first verified build"; fi \
    && unzip -q model.zip && rm model.zip \
    # Normalize to a stable path regardless of the archive's top dir.
    && TAGGER="$(find . -name '*.tagger' | head -1)" \
    && mkdir -p /opt/model && cp "${TAGGER}" "/opt/model/${MODEL_ID}.tagger"

# ===== runtime =====
FROM debian:bookworm-slim AS runtime

RUN apt-get update && apt-get install -y --no-install-recommends libgomp1 curl ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -ms /bin/bash appuser

ARG MODEL_ID=czech-morfflex2.0-pdtc1.0-220710
ENV MODEL_ID=${MODEL_ID}
ENV PORT=8080
# --max_request_size raised to 65536 KB so a full bulk-lemmatize call (Q-11 ≤100k
# strings) fits ONE /tag pass (Q-10 §4); default 1024 KB would 413.
ENV MAX_REQUEST_SIZE=65536
ENV CONNECTION_TIMEOUT=120

COPY --from=build /src/src/rest_server/morphodita_server /usr/local/bin/morphodita_server
COPY --from=model /opt/model /opt/model

USER appuser
EXPOSE 8080

# tcpSocket-style container health (the raw server exposes no GET path; k8s uses
# a tcpSocket readiness probe — see the chart).
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
  CMD curl -fsS -X POST "http://127.0.0.1:${PORT}/tag" --data-urlencode "data=Ahoj" \
      --data "output=vertical" --data "input=untokenized" >/dev/null || exit 1

# S-1: launch with the EXPLICIT model id (rest_id `czech` → tagger path). The
# `<ack>` acknowledgements arg is required by morphodita_server.
CMD ["sh", "-c", "exec morphodita_server \
  --connection_timeout=${CONNECTION_TIMEOUT} \
  --max_request_size=${MAX_REQUEST_SIZE} \
  ${PORT} czech /opt/model/${MODEL_ID}.tagger 'ttr-nlp self-hosted MorphoDiTa (CC BY-NC-SA)'"]
