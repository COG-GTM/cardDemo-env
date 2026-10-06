#!/usr/bin/env python3
"""Live parity console: HTTP API + Server-Sent Events + static UI on one port."""

from __future__ import annotations

import argparse
import json
import queue
import threading
import traceback
from http import HTTPStatus
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

from java_client import JavaEngineClient
from session import ParitySession
from streams import GENERATED, list_streams

STATIC = Path(__file__).resolve().parent / "static"


class Hub:
    """Fan-out of run events to every connected browser; replays the current run on connect."""

    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.history: list[tuple[str, dict[str, Any]]] = []
        self.subscribers: list[queue.Queue[tuple[str, dict[str, Any]]]] = []
        self.session: ParitySession | None = None
        self.thread: threading.Thread | None = None
        self.java = JavaEngineClient()

    def emit(self, event: str, data: dict[str, Any]) -> None:
        with self.lock:
            if event == "phase" and data.get("reset"):
                self.history = []
            self.history.append((event, data))
            for subscriber in self.subscribers:
                subscriber.put((event, data))

    def subscribe(self) -> tuple[queue.Queue[tuple[str, dict[str, Any]]], list[tuple[str, dict[str, Any]]]]:
        with self.lock:
            subscriber: queue.Queue[tuple[str, dict[str, Any]]] = queue.Queue()
            self.subscribers.append(subscriber)
            return subscriber, list(self.history)

    def unsubscribe(self, subscriber: queue.Queue[tuple[str, dict[str, Any]]]) -> None:
        with self.lock:
            if subscriber in self.subscribers:
                self.subscribers.remove(subscriber)

    @property
    def running(self) -> bool:
        return self.thread is not None and self.thread.is_alive()

    def start(self, stream: str, pace_ms: int, count: int, seed: int) -> None:
        if self.running:
            raise RuntimeError("a run is already in progress")
        self.emit("phase", {"reset": True, "text": f"Starting stream {stream}"})
        self.session = ParitySession(self.emit, java=self.java)

        def target() -> None:
            try:
                assert self.session is not None
                self.session.run(stream, pace_ms, count, seed)
            except Exception as error:  # surfaced in the UI instead of dying silently
                traceback.print_exc()
                self.emit("error", {"text": f"{type(error).__name__}: {error}"})

        self.thread = threading.Thread(target=target, daemon=True)
        self.thread.start()

    def stop(self) -> None:
        if self.session is not None:
            self.session.stop()

    def set_break(self, on: bool) -> str:
        mode = self.java.set_rounding("HALF_EVEN" if on else "HALF_UP")
        self.emit("mode", {"rounding": mode, "breakJava": mode == "HALF_EVEN"})
        return mode


HUB = Hub()


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *args: Any, **kwargs: Any) -> None:
        super().__init__(*args, directory=str(STATIC), **kwargs)

    def log_message(self, format: str, *args: Any) -> None:
        if not self.path.startswith(("/api/events", "/api/health")):
            super().log_message(format, *args)

    def _json(self, payload: Any, status: HTTPStatus = HTTPStatus.OK) -> None:
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _body(self) -> dict[str, Any]:
        length = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(length) or b"{}")

    def do_GET(self) -> None:
        if self.path == "/api/health":
            self._json({"ok": True})
        elif self.path == "/api/config":
            try:
                java = HUB.java.info()
            except OSError as error:
                java = {"error": str(error)}
            self._json({"streams": list_streams(), "java": java, "running": HUB.running,
                        "generated": GENERATED})
        elif self.path == "/api/events":
            self._events()
        else:
            super().do_GET()

    def do_POST(self) -> None:
        try:
            body = self._body()
            if self.path == "/api/run":
                HUB.start(str(body.get("stream", "default")), int(body.get("paceMs", 600)),
                          int(body.get("count", 60)), int(body.get("seed", 1250)))
                self._json({"started": True})
            elif self.path == "/api/stop":
                HUB.stop()
                self._json({"stopping": True})
            elif self.path == "/api/break-java":
                self._json({"rounding": HUB.set_break(bool(body.get("on")))})
            else:
                self._json({"error": "not found"}, HTTPStatus.NOT_FOUND)
        except RuntimeError as error:
            self._json({"error": str(error)}, HTTPStatus.CONFLICT)

    def _events(self) -> None:
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        subscriber, history = HUB.subscribe()
        try:
            for event, data in history:
                self._send(event, data)
            while True:
                try:
                    event, data = subscriber.get(timeout=15)
                    self._send(event, data)
                except queue.Empty:
                    self.wfile.write(b": keep-alive\n\n")
                    self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass
        finally:
            HUB.unsubscribe(subscriber)

    def _send(self, event: str, data: dict[str, Any]) -> None:
        self.wfile.write(f"event: {event}\ndata: {json.dumps(data)}\n\n".encode())
        self.wfile.flush()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8090)
    args = parser.parse_args()
    HUB.java.wait_ready()
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    server.daemon_threads = True
    print(f"parity console listening on http://{args.host}:{args.port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
