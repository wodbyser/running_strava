"""Bearer-token authentication and caching."""

# Copyright 2026 Garmin Ltd. or its subsidiaries.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

from __future__ import annotations

import base64
import hashlib
import json
import logging
import os
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path
from time import time

import requests

from ._credentials import _require_nonempty_key, _warn_if_readable_by_others
from .constants import (
    BASE_URL_ENV_VAR,
    DEFAULT_BASE_URL,
    DEFAULT_BEARER_TOKEN_CACHE_FILE,
    DEFAULT_TIMEOUT_SECONDS,
    TOKEN_PATH,
)
from .errors import GarminPersonalApiError, HttpError

_BEARER_TOKEN_REFRESH_WINDOW_SECONDS = 60 * 60
_LOGGER = logging.getLogger(__name__)


@dataclass(frozen=True)
class BearerToken:
    """A bearer token and its expiration time in seconds since the epoch."""

    access_token: str = field(repr=False)
    expires_at: float


class PersonalApiAuthenticator:
    """Cache bearer tokens and exchange a Garmin personal API key when needed.

    Key discovery is separate from HTTP authentication. Pass :meth:`obtain_token`
    a key from :func:`garmin_personal_api.api_keys.resolve_api_key` or from
    another secret source.
    """

    def __init__(
        self,
        *,
        timeout: float = DEFAULT_TIMEOUT_SECONDS,
        session: requests.Session | None = None,
        bearer_token_cache_file: str | Path = DEFAULT_BEARER_TOKEN_CACHE_FILE,
        clock: Callable[[], float] = time,
    ) -> None:
        if timeout <= 0:
            raise GarminPersonalApiError("timeout must be greater than zero")
        self.base_url = os.environ.get(BASE_URL_ENV_VAR, DEFAULT_BASE_URL).rstrip("/")
        self.timeout = timeout
        self._session = session or requests.Session()
        self.bearer_token_cache_file = Path(bearer_token_cache_file).expanduser()
        self._clock = clock

    def close(self) -> None:
        """Close the underlying HTTP session."""
        self._session.close()

    def __enter__(self) -> PersonalApiAuthenticator:
        return self

    def __exit__(self, *exc_info: object) -> None:
        self.close()

    def __repr__(self) -> str:
        return f"{type(self).__name__}(base_url={self.base_url!r}, timeout={self.timeout!r})"

    def obtain_token(
        self,
        api_key: str,
        *,
        force_refresh: bool = False,
    ) -> BearerToken:
        """Return a cached bearer token or exchange the key for a new bearer token."""
        if not isinstance(api_key, str):
            raise GarminPersonalApiError(
                "An API key string is required; use resolve_api_key() to discover one."
            )
        key = _require_nonempty_key(api_key, "the explicit API key")
        # Cached tokens are only reused for the same API key and environment.
        fingerprint = hashlib.sha256(f"{self.base_url}\0{key}".encode()).hexdigest()
        if not force_refresh:
            cached_token = self._read_cached_token(fingerprint)
            if cached_token is not None:
                if cached_token.expires_at - self._clock() > _BEARER_TOKEN_REFRESH_WINDOW_SECONDS:
                    _LOGGER.info("Using cached bearer token")
                    return cached_token
                _LOGGER.info(
                    "Cached bearer token is expired or expires within the 1-hour refresh "
                    "window; requesting a new token"
                )

        url = f"{self.base_url}{TOKEN_PATH}"
        _LOGGER.info("POST %s", TOKEN_PATH)
        response = self._session.post(
            url,
            headers={"Accept": "*/*", "Authorization": f"ApiKey {key}"},
            timeout=self.timeout,
        )
        if not response.ok:
            raise HttpError(
                status_code=response.status_code,
                method="POST",
                url=url,
                response_text=response.text,
            )

        access_token = response.text.strip()
        token = BearerToken(access_token=access_token, expires_at=_jwt_expiration(access_token))
        self._write_cached_token(fingerprint, token)
        _LOGGER.info("Received and cached a new bearer token")
        return token

    def _read_cached_token(self, fingerprint: str) -> BearerToken | None:
        path = self.bearer_token_cache_file
        try:
            serialized = path.read_text(encoding="utf-8")
        except FileNotFoundError:
            _LOGGER.info(
                "No cached bearer token found; requesting a new token"
            )
            return None
        _warn_if_readable_by_others(path, "Bearer-token cache file")
        try:
            cache = json.loads(serialized)
            if cache["fingerprint"] != fingerprint:
                _LOGGER.info(
                    "Cached bearer token fingerprint does not match the current API key or "
                    "base URL; requesting a new token"
                )
                return None
            return BearerToken(
                access_token=cache["access_token"],
                expires_at=float(cache["expires_at"]),
            )
        except (KeyError, TypeError, ValueError):
            _LOGGER.warning("Ignoring invalid bearer-token cache file %s", path)
            return None

    def _write_cached_token(self, fingerprint: str, token: BearerToken) -> None:
        path = self.bearer_token_cache_file
        path.parent.mkdir(parents=True, exist_ok=True)
        descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as cache_file:
            json.dump(
                {
                    "fingerprint": fingerprint,
                    "access_token": token.access_token,
                    "expires_at": token.expires_at,
                },
                cache_file,
            )


def _jwt_expiration(access_token: str) -> float:
    """Return the ``exp`` claim of a JWT without verifying its signature."""
    try:
        encoded_payload = access_token.split(".")[1]
        payload = json.loads(
            base64.urlsafe_b64decode(encoded_payload + "=" * (-len(encoded_payload) % 4))
        )
        return float(payload["exp"])
    except (IndexError, KeyError, TypeError, ValueError) as exc:
        raise GarminPersonalApiError(
            "The token endpoint did not return a JWT with an exp claim."
        ) from exc
