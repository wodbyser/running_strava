"""Shared credential validation and file-permission checks."""

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

import logging
import os
from pathlib import Path

from .errors import GarminPersonalApiError

_LOGGER = logging.getLogger(__name__)


def _require_nonempty_key(value: str, source: str) -> str:
    key = value.strip()
    if not key:
        raise GarminPersonalApiError(f"The value from {source} is empty.")
    return key


def _warn_if_readable_by_others(path: Path, description: str) -> None:
    if os.name == "posix" and path.stat().st_mode & 0o077:
        _LOGGER.warning(
            "%s %s is readable by group or other users; restrict it with chmod 600",
            description,
            path,
        )
