"""Personal API key discovery."""

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

import os
from collections.abc import Mapping
from pathlib import Path

from ._credentials import _require_nonempty_key, _warn_if_readable_by_others
from .constants import DEFAULT_API_KEY_ENV_VAR, DEFAULT_API_KEY_FILE
from .errors import GarminPersonalApiError


def resolve_api_key(
    api_key: str | None = None,
    *,
    env: Mapping[str, str] | None = None,
    env_var: str = DEFAULT_API_KEY_ENV_VAR,
    key_file: str | Path | None = None,
) -> str:
    """Resolve an explicit key, explicit file, environment, then default file.

    Pass the result to ``PersonalApiAuthenticator.obtain_token()`` to opt into
    discovery. An explicitly supplied ``key_file`` overrides the environment,
    even when it names the default file. Invalid explicit sources do not fall
    back to other sources.
    """
    if api_key is not None:
        return _require_nonempty_key(api_key, "the explicit API key")

    if key_file is None:
        environment = os.environ if env is None else env
        environment_value = environment.get(env_var)
        if environment_value is not None:
            return _require_nonempty_key(environment_value, f"environment variable {env_var}")

    path = Path(DEFAULT_API_KEY_FILE if key_file is None else key_file).expanduser()
    try:
        file_value = path.read_text(encoding="utf-8")
    except FileNotFoundError as exc:
        if key_file is not None:
            raise GarminPersonalApiError(
                f"Personal API key file {path} does not exist."
            ) from exc
        raise GarminPersonalApiError(
            "No personal API key was provided. Pass --api-key/api_key, set "
            f"{env_var}, or create {path}."
        ) from exc
    _warn_if_readable_by_others(path, "Personal API key file")
    return _require_nonempty_key(file_value, f"personal API key file {path}")
