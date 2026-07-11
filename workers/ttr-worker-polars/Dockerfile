FROM python:3.13-slim

ENV UV_PROJECT_ENVIRONMENT=/app/.venv
ENV PATH=/app/.venv/bin:$PATH

WORKDIR /app

RUN pip install --no-cache-dir uv==0.5.*

COPY workers/ttr-worker-polars/pyproject.toml workers/ttr-worker-polars/uv.lock workers/ttr-worker-polars/README.md /app/workers/ttr-worker-polars/
COPY shared /app/shared

WORKDIR /app/workers/ttr-worker-polars

RUN uv sync --frozen --no-dev

COPY workers/ttr-worker-polars/src /app/workers/ttr-worker-polars/src
COPY workers/ttr-worker-polars/conf /app/workers/ttr-worker-polars/conf

EXPOSE 7300 7301

CMD ["uv", "run", "python", "-m", "workers_polars.main"]
