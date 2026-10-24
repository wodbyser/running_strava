"""Obtain a Garmin Personal API bearer token."""

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

from datetime import datetime

from garmin_personal_api import PersonalApiAuthenticator, resolve_api_key


def main() -> None:
    with PersonalApiAuthenticator() as authenticator:
        token = authenticator.obtain_token(resolve_api_key())
    expires_at = datetime.fromtimestamp(token.expires_at).astimezone().isoformat()
    print(f"Obtained a bearer token that expires at {expires_at}.")


if __name__ == "__main__":
    main()
