"""Tests for key discovery, token exchange, and the bearer-token cache."""

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
import json
import os
from pathlib import Path

import pytest
import requests

from garmin_personal_api import (
    BearerToken,
    GarminPersonalApiError,
    HttpError,
    PersonalApiAuthenticator,
    api_keys,
    resolve_api_key,
)
from garmin_personal_api.api_keys import resolve_api_key as module_resolve_api_key
from garmin_personal_api.constants import BASE_URL_ENV_VAR, TOKEN_PATH

from .helpers import StubSession, make_response

NOW = 1_800_000_000
HOUR = 60 * 60


def _jwt(payload: dict[str, object]) -> str:
    def encode(value: dict[str, object]) -> str:
        serialized = json.dumps(value, separators=(",", ":")).encode()
        return base64.urlsafe_b64encode(serialized).decode().rstrip("=")

    return f"{encode({'alg': 'none'})}.{encode(payload)}.signature"


def _token_response(access_token: str, *, status_code: int = 200) -> requests.Response:
    return make_response(None, raw_text=access_token, status_code=status_code)


def _authenticator(
    tmp_path: Path,
    session: StubSession,
    *,
    timeout: float = 30,
) -> PersonalApiAuthenticator:
    return PersonalApiAuthenticator(
        session=session,
        timeout=timeout,
        bearer_token_cache_file=tmp_path / "bearer-token-cache.json",
        clock=lambda: NOW,
    )


def test_key_resolver_remains_exported_from_the_package() -> None:
    assert resolve_api_key is module_resolve_api_key


def test_key_precedence_is_explicit_key_then_explicit_file_then_env_then_default(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    default_file = tmp_path / "default-key"
    default_file.write_text("default-key\n", encoding="utf-8")
    monkeypatch.setattr(api_keys, "DEFAULT_API_KEY_FILE", default_file)
    key_file = tmp_path / "key"
    key_file.write_text("file-key\n", encoding="utf-8")
    env = {"GARMIN_PERSONAL_API_KEY": " env-key "}

    assert resolve_api_key(" explicit-key ", env=env, key_file=key_file) == "explicit-key"
    assert resolve_api_key(env=env, key_file=key_file) == "file-key"
    assert resolve_api_key(env=env, key_file=default_file) == "default-key"
    assert resolve_api_key(env=env) == "env-key"
    assert resolve_api_key(env={}) == "default-key"
    assert resolve_api_key(env={}, key_file=key_file) == "file-key"


def test_explicit_key_file_overrides_environment(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    key_file = tmp_path / "custom-key"
    key_file.write_text(" file-key\n", encoding="utf-8")
    monkeypatch.setenv("GARMIN_PERSONAL_API_KEY", "env-key")

    assert resolve_api_key(key_file=key_file) == "file-key"


@pytest.mark.parametrize("environment", [{}, {"GARMIN_PERSONAL_API_KEY": "  "}])
def test_empty_file_or_environment_key_is_rejected(
    tmp_path: Path,
    environment: dict[str, str],
) -> None:
    key_file = tmp_path / "key"
    key_file.write_text(" \n", encoding="utf-8")

    with pytest.raises(GarminPersonalApiError, match="empty"):
        resolve_api_key(env=environment, key_file=key_file)


def test_empty_environment_key_does_not_fall_back_to_default_file(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    key_file = tmp_path / "key"
    key_file.write_text("file-key", encoding="utf-8")
    monkeypatch.setattr(api_keys, "DEFAULT_API_KEY_FILE", key_file)

    with pytest.raises(GarminPersonalApiError, match="environment variable.*empty"):
        resolve_api_key(env={"GARMIN_PERSONAL_API_KEY": ""})


@pytest.mark.parametrize(("api_key", "message"), [(None, "--api-key"), ("  ", "empty")])
def test_missing_or_empty_key_is_rejected(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    api_key: str | None,
    message: str,
) -> None:
    monkeypatch.setattr(api_keys, "DEFAULT_API_KEY_FILE", tmp_path / "missing")
    with pytest.raises(GarminPersonalApiError, match=message):
        resolve_api_key(api_key, env={})


@pytest.mark.parametrize("file_state", ["missing", "empty", "directory"])
def test_invalid_explicit_file_does_not_fall_back(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    file_state: str,
) -> None:
    default_file = tmp_path / "default-key"
    default_file.write_text("default-key", encoding="utf-8")
    monkeypatch.setattr(api_keys, "DEFAULT_API_KEY_FILE", default_file)
    key_file = tmp_path / "explicit-key"
    if file_state == "empty":
        key_file.write_text(" \n", encoding="utf-8")
    elif file_state == "directory":
        key_file.mkdir()

    expected_error = OSError if file_state == "directory" else GarminPersonalApiError
    with pytest.raises(expected_error):
        resolve_api_key(env={"GARMIN_PERSONAL_API_KEY": "env-key"}, key_file=key_file)


def test_explicit_key_overrides_invalid_file_and_environment(tmp_path: Path) -> None:
    assert resolve_api_key(
        "explicit-key",
        env={"GARMIN_PERSONAL_API_KEY": ""},
        key_file=tmp_path / "missing",
    ) == "explicit-key"


def test_empty_explicit_key_does_not_fall_back_to_file(tmp_path: Path) -> None:
    key_file = tmp_path / "key"
    key_file.write_text("file-key", encoding="utf-8")

    with pytest.raises(GarminPersonalApiError, match="explicit API key.*empty"):
        resolve_api_key(" ", env={"GARMIN_PERSONAL_API_KEY": "env-key"}, key_file=key_file)


@pytest.mark.skipif(os.name != "posix", reason="POSIX file mode test")
def test_key_file_warns_and_continues_for_insecure_permissions(
    tmp_path: Path,
    caplog: pytest.LogCaptureFixture,
) -> None:
    key_file = tmp_path / "key"
    key_file.write_text("file-key\n", encoding="utf-8")
    key_file.chmod(0o644)

    assert resolve_api_key(env={}, key_file=key_file) == "file-key"
    assert "Personal API key file" in caplog.text
    assert "chmod 600" in caplog.text


def test_token_exchange_returns_the_jwt_and_its_expiration(tmp_path: Path) -> None:
    access_token = _jwt({"exp": NOW + 2 * HOUR})
    session = StubSession(_token_response(f"{access_token}\n"))

    token = _authenticator(tmp_path, session, timeout=12).obtain_token(" secret-key ")

    assert token == BearerToken(access_token=access_token, expires_at=NOW + 2 * HOUR)
    method, url, kwargs = session.calls[0]
    assert method == "POST"
    assert url.endswith(TOKEN_PATH)
    assert kwargs["headers"] == {"Accept": "*/*", "Authorization": "ApiKey secret-key"}
    assert kwargs["timeout"] == 12


@pytest.mark.parametrize(("api_key", "message"), [(None, "resolve_api_key"), ("  ", "empty")])
def test_obtain_token_requires_a_key_and_does_not_discover_one(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    api_key: str | None,
    message: str,
) -> None:
    monkeypatch.setenv("GARMIN_PERSONAL_API_KEY", "env-key")
    session = StubSession()

    with pytest.raises(GarminPersonalApiError, match=message):
        _authenticator(tmp_path, session).obtain_token(api_key)  # type: ignore[arg-type]

    assert session.calls == []


@pytest.mark.parametrize("access_token", ["opaque-token", _jwt({"sub": "user"})])
def test_token_without_a_jwt_exp_claim_is_rejected(tmp_path: Path, access_token: str) -> None:
    session = StubSession(_token_response(access_token))

    with pytest.raises(GarminPersonalApiError, match="exp claim"):
        _authenticator(tmp_path, session).obtain_token("key")


def test_token_endpoint_errors_preserve_response_bodies(tmp_path: Path) -> None:
    session = StubSession(_token_response('{"message": "invalid credentials"}', status_code=401))

    with pytest.raises(HttpError) as raised:
        _authenticator(tmp_path, session).obtain_token("api-key")

    assert raised.value.status_code == 401
    assert raised.value.response_text == '{"message": "invalid credentials"}'
    assert "invalid credentials" in str(raised.value)


def test_context_manager_closes_session(tmp_path: Path) -> None:
    session = StubSession()
    with _authenticator(tmp_path, session):
        pass
    assert session.closed is True


def test_bearer_token_repr_does_not_expose_token() -> None:
    assert "secret-token" not in repr(BearerToken("secret-token", expires_at=NOW))


def test_cache_stores_the_token_but_not_the_api_key(tmp_path: Path) -> None:
    api_key = "do-not-store-this-key"
    access_token = _jwt({"exp": NOW + 2 * HOUR})
    authenticator = _authenticator(tmp_path, StubSession(_token_response(access_token)))

    authenticator.obtain_token(api_key)

    serialized_cache = authenticator.bearer_token_cache_file.read_text(encoding="utf-8")
    assert access_token in serialized_cache
    assert api_key not in serialized_cache
    if os.name == "posix":
        assert authenticator.bearer_token_cache_file.stat().st_mode & 0o777 == 0o600


@pytest.mark.parametrize(
    ("cached_lifetime", "force_refresh", "expect_request"),
    [
        pytest.param(2 * HOUR, False, False, id="reused"),
        pytest.param(HOUR, False, True, id="refreshed-within-one-hour"),
        pytest.param(-HOUR, False, True, id="refreshed-after-expiration"),
        pytest.param(2 * HOUR, True, True, id="force-refresh"),
    ],
)
def test_cached_token_is_reused_until_the_refresh_window(
    tmp_path: Path,
    cached_lifetime: int,
    force_refresh: bool,
    expect_request: bool,
) -> None:
    cached_token = _jwt({"exp": NOW + cached_lifetime})
    new_token = _jwt({"exp": NOW + 24 * HOUR})
    _authenticator(tmp_path, StubSession(_token_response(cached_token))).obtain_token("key")
    session = StubSession(_token_response(new_token))

    token = _authenticator(tmp_path, session).obtain_token("key", force_refresh=force_refresh)

    assert token.access_token == (new_token if expect_request else cached_token)
    assert len(session.calls) == (1 if expect_request else 0)


@pytest.mark.parametrize(
    ("second_key", "second_base_url"),
    [
        pytest.param("other-key", "https://one.example", id="different-key"),
        pytest.param("key", "https://two.example", id="different-base-url"),
    ],
)
def test_cache_is_bound_to_key_and_base_url(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    second_key: str,
    second_base_url: str,
) -> None:
    first_token = _jwt({"exp": NOW + 2 * HOUR})
    second_token = _jwt({"exp": NOW + 3 * HOUR})
    monkeypatch.setenv(BASE_URL_ENV_VAR, "https://one.example")
    _authenticator(tmp_path, StubSession(_token_response(first_token))).obtain_token("key")
    monkeypatch.setenv(BASE_URL_ENV_VAR, second_base_url)
    session = StubSession(_token_response(second_token))

    token = _authenticator(tmp_path, session).obtain_token(second_key)

    assert token.access_token == second_token
    assert len(session.calls) == 1


def test_invalid_cache_is_replaced(tmp_path: Path) -> None:
    access_token = _jwt({"exp": NOW + 2 * HOUR})
    authenticator = _authenticator(tmp_path, StubSession(_token_response(access_token)))
    authenticator.bearer_token_cache_file.write_text("not json", encoding="utf-8")
    authenticator.bearer_token_cache_file.chmod(0o600)

    assert authenticator.obtain_token("key").access_token == access_token
    cache = json.loads(authenticator.bearer_token_cache_file.read_text(encoding="utf-8"))
    assert cache["access_token"] == access_token


@pytest.mark.skipif(os.name != "posix", reason="POSIX file mode test")
def test_cache_warns_and_continues_for_insecure_permissions(
    tmp_path: Path,
    caplog: pytest.LogCaptureFixture,
) -> None:
    access_token = _jwt({"exp": NOW + 2 * HOUR})
    authenticator = _authenticator(tmp_path, StubSession(_token_response(access_token)))
    authenticator.obtain_token("key")
    authenticator.bearer_token_cache_file.chmod(0o644)
    session = StubSession()

    assert _authenticator(tmp_path, session).obtain_token("key").access_token == access_token
    assert session.calls == []
    assert "Bearer-token cache file" in caplog.text
    assert "chmod 600" in caplog.text
