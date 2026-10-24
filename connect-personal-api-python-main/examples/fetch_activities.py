"""Search summarized activities with the Garmin Personal API client."""

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

import argparse
import json
from datetime import date, timedelta

from garmin_personal_api import (
    GarminPersonalApiClient,
    PersonalApiAuthenticator,
    resolve_api_key,
)


def main() -> None:
    today = date.today()
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--start-date",
        metavar="DATE",
        default=(today - timedelta(days=30)).isoformat(),
    )
    parser.add_argument("--end-date", metavar="DATE", default=today.isoformat())
    parser.add_argument("--limit", type=int, default=20, metavar="COUNT")
    args = parser.parse_args()

    with PersonalApiAuthenticator() as authenticator:
        token = authenticator.obtain_token(resolve_api_key())
    with GarminPersonalApiClient(token.access_token) as client:
        activities = client.search_activities(
            start_date=args.start_date,
            end_date=args.end_date,
            limit=args.limit,
        )
    print(json.dumps(activities, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
