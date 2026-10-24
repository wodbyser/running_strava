"""File output shared by direct and interactive downloads."""

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

import os
import tempfile
from pathlib import Path

from .errors import GarminPersonalApiError


def save_download(content: bytes, destination: Path, *, overwrite: bool = False) -> Path:
    """Publish a complete file, without replacing an existing file unless requested."""
    destination = destination.expanduser()
    temporary: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            dir=destination.parent, prefix=f".{destination.name}.", delete=False
        ) as output:
            temporary = Path(output.name)
            output.write(content)
        if overwrite:
            temporary.replace(destination)
        else:
            # Linking publishes atomically and refuses even a dangling destination symlink.
            os.link(temporary, destination)
    except FileExistsError as exc:
        raise GarminPersonalApiError(
            f"File already exists: {destination}. Choose another path or allow overwrite."
        ) from exc
    except OSError as exc:
        raise GarminPersonalApiError(f"Cannot save {destination}: {exc}") from exc
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
    return destination
