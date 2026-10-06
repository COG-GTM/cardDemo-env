"""HTTP client for the Spring Boot engine (parity-console/java-live)."""

from __future__ import annotations

import json
import os
import time
import urllib.request
from decimal import Decimal
from typing import Any

JAVA_URL = os.environ.get("JAVA_LIVE_URL", "http://localhost:8091")


class _Encoder(json.JSONEncoder):
    def default(self, value: Any) -> Any:
        if isinstance(value, Decimal):
            return str(value)
        return super().default(value)


class JavaEngineClient:
    def __init__(self, base_url: str = JAVA_URL):
        self.base_url = base_url.rstrip("/")

    def _call(self, method: str, path: str, body: Any = None, timeout: float = 30) -> Any:
        data = None if body is None else json.dumps(body, cls=_Encoder).encode()
        request = urllib.request.Request(
            self.base_url + path, data=data, method=method,
            headers={"Content-Type": "application/json"},
        )
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.loads(response.read(), parse_float=Decimal)

    def wait_ready(self, seconds: float = 120) -> dict[str, Any]:
        deadline = time.time() + seconds
        while True:
            try:
                return self.info()
            except OSError:
                if time.time() > deadline:
                    raise
                time.sleep(1)

    def info(self) -> dict[str, Any]:
        return self._call("GET", "/api/engine/info", timeout=5)

    def reset(self, seed: dict[str, Any]) -> dict[str, Any]:
        return self._call("POST", "/api/engine/reset", seed)

    def process(self, transaction: dict[str, Any]) -> dict[str, Any]:
        return self._call("POST", "/api/engine/transactions", transaction)

    def state(self) -> dict[str, Any]:
        return self._call("GET", "/api/engine/state")

    def rounding(self) -> str:
        return self._call("GET", "/api/engine/rounding")["mode"]

    def set_rounding(self, mode: str) -> str:
        return self._call("PUT", "/api/engine/rounding", {"mode": mode})["mode"]
