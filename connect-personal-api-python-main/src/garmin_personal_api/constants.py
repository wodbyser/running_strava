"""Public defaults for Garmin Personal API access."""

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

from pathlib import Path

DEFAULT_BASE_URL = "https://personal.connectapi.garmin.com"
DEFAULT_API_KEY_FILE = Path("~/.config/garmin/connect-personal-api-key")
DEFAULT_BEARER_TOKEN_CACHE_FILE = Path("~/.config/garmin/connect-personal-api-bearer-token.json")
DEFAULT_API_KEY_ENV_VAR = "GARMIN_PERSONAL_API_KEY"
BASE_URL_ENV_VAR = "GARMIN_PERSONAL_API_BASE_URL"
DEFAULT_TIMEOUT_SECONDS = 30.0
TOKEN_PATH = "/personalapitoken-service/personal-api/personal-api-key/token"
