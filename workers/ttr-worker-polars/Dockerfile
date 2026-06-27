FROM python:3.13-slim

ENV UV_PROJECT_ENVIRONMENT=/app/.venv
ENV PATH=/app/.venv/bin:$PATH

WORKDIR /app

RUN pip install --no-cache-dir uv==0.5.*

COPY workers/steropes/pyproject.toml workers/steropes/uv.lock workers/steropes/README.md /app/workers/steropes/
COPY shared /app/shared

WORKDIR /app/workers/steropes

RUN uv sync --frozen --no-dev

COPY workers/steropes/src /app/workers/steropes/src
COPY workers/steropes/conf /app/workers/steropes/conf

EXPOSE 7300 7301

CMD ["uv", "run", "python", "-m", "workers_steropes.main"]
