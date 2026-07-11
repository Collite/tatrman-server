"""Main entry point for the Nlp NLP service.

Logging root config lives in `nlp_service/__init__.py` so it also fires
when uvicorn imports `nlp_service.api.routes:app` directly (the Dockerfile
entrypoint). Importing `nlp_service.api.routes` here triggers the package
init first, so the same setup applies.
"""

from nlp_service.api.routes import create_app

app = create_app()

if __name__ == "__main__":
    import uvicorn

    from nlp_service.config import load_config

    config = load_config()
    uvicorn.run(
        "nlp_service.api.routes:app",
        host=config.service.host,
        port=config.service.port,
        reload=False,
    )