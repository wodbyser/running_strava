"""Shared HTTP test doubles."""

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

import json
from collections.abc import Mapping
from typing import Any

import requests


class StubSession:
    """Minimal requests.Session replacement with optional URL-specific responses."""

    def __init__(
        self,
        response: requests.Response | None = None,
        *,
        responses: Mapping[str, requests.Response] | None = None,
    ) -> None:
        self.response = response
        self.responses = dict(responses or {})
        self.calls: list[tuple[str, str, dict[str, Any]]] = []
        self.closed = False

    def post(self, url: str, **kwargs: Any) -> requests.Response:
        self.calls.append(("POST", url, kwargs))
        return self._response_for(url)

    def get(self, url: str, **kwargs: Any) -> requests.Response:
        self.calls.append(("GET", url, kwargs))
        return self._response_for(url)

    def _response_for(self, url: str) -> requests.Response:
        if url in self.responses:
            return self.responses[url]
        if self.response is not None:
            return self.response
        raise AssertionError(f"No stub response configured for {url}")

    def close(self) -> None:
        self.closed = True


def make_response(
    payload: Any,
    *,
    status_code: int = 200,
    url: str = "https://personal.connectapitest.garmin.com/example",
    raw_text: str | None = None,
) -> requests.Response:
    response = requests.Response()
    response.status_code = status_code
    response.url = url
    response.headers["Content-Type"] = "application/json"
    text = raw_text if raw_text is not None else json.dumps(payload)
    response._content = text.encode("utf-8")
    response.encoding = "utf-8"
    return response
