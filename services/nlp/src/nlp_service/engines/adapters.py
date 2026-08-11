# SPDX-License-Identifier: Apache-2.0
"""The one place config becomes a backend spec (NLS-P3.3, ⚑NLS-D7).

`BackendConfig` is pydantic and lives here because it is validated service
configuration; `BackendSpec` is a plain dataclass and lives in the wheel because a
consumer that only has `ttr-nlp` should not need a config framework to name a URL.
One conversion, in one function, so the four adapters cannot disagree about which
fields carry across — the rate limit in particular, which applies only to the
remote (Lindat) dev tier and would silently throttle a cluster if it leaked into a
self-hosted spec.
"""

from __future__ import annotations

from ttrnlp.client.backends import BackendSpec

from nlp_service.config import BackendConfig


def backend_spec(backend: BackendConfig) -> BackendSpec:
    return BackendSpec(
        url=backend.url,
        model=backend.model,
        model_version=backend.model_version,
        timeout_seconds=backend.timeout_seconds,
        max_retries=backend.max_retries,
        rate_limit_per_minute=backend.rate_limit_per_minute,
    )


__all__ = ["backend_spec"]
