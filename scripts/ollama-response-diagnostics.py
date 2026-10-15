#!/usr/bin/env python3
"""Summarize stdin only. No HTTP, Docker, DB, request body, model download or response text output."""

import argparse
import json
import sys
import time

MAX_LINE_BYTES = 65536
MAX_STREAM_BYTES = 2 * 1024 * 1024
MAX_CHUNKS = 10000
MAX_INTEGER = (1 << 63) - 1
METRICS = ("total_duration", "load_duration", "prompt_eval_duration", "eval_duration", "prompt_eval_count", "eval_count")


def number(value):
    return value if type(value) is int and 0 <= value <= MAX_INTEGER else None


def metrics(response):
    result = {field: number(response.get(field)) for field in METRICS}
    result["server_metrics_available"] = all(result[field] is not None for field in METRICS[1:])
    for prefix, count in (("prompt", "prompt_eval_count"), ("generated", "eval_count")):
        duration = result["prompt_eval_duration" if prefix == "prompt" else "eval_duration"]
        result[prefix + "_tokens_per_second"] = (
            round(result[count] * 1_000_000_000 / duration, 3)
            if result[count] is not None and duration is not None and duration > 0 else None
        )
    reason = response.get("done_reason")
    result["done_reason"] = reason if isinstance(reason, str) and reason in ("stop", "length", "load", "unload") else "unavailable"
    return result


class InvalidInput(Exception):
    pass


class Measurements:
    def __init__(self, mode, emit, clock):
        self.mode, self.emit, self.clock = mode, emit, clock
        self.started = clock()
        self.last_report = self.started
        self.chunks = self.content_chunks = self.thinking_chunks = 0
        self.content_chars = self.whitespace_chars = self.thinking_chars = 0
        self.first_chunk_ms = self.first_content_ms = self.first_non_whitespace_ms = self.first_thinking_ms = None
        self.reasoning_markers_seen = False
        self.marker_tail = ""
        self.final = None

    def report(self, event, **extra):
        self.emit({"event": event, "mode": self.mode, "elapsed_ms": round(max(0, self.clock() - self.started) * 1000, 3),
                   "chunks": self.chunks, "content_chunks": self.content_chunks, "thinking_chunks": self.thinking_chunks,
                   "content_chars": self.content_chars, "whitespace_chars": self.whitespace_chars, "thinking_chars": self.thinking_chars,
                   "first_chunk_ms": self.first_chunk_ms, "first_content_ms": self.first_content_ms,
                   "first_non_whitespace_ms": self.first_non_whitespace_ms, "first_thinking_ms": self.first_thinking_ms,
                   "reasoning_markers_seen": self.reasoning_markers_seen, **extra})

    def consume(self, response):
        if not isinstance(response, dict):
            raise InvalidInput("INVALID_RESPONSE")
        if "error" in response:
            raise InvalidInput("OLLAMA_ERROR")
        if self.final is not None:
            raise InvalidInput("EXTRA_RESPONSE")
        if "done" in response and type(response["done"]) is not bool:
            raise InvalidInput("INVALID_RESPONSE")
        message = response.get("message", {})
        if not isinstance(message, dict):
            raise InvalidInput("INVALID_RESPONSE")
        content = message.get("content", response.get("response", ""))
        thinking = message.get("thinking", response.get("thinking", ""))
        if not isinstance(content, str) or not isinstance(thinking, str):
            raise InvalidInput("INVALID_RESPONSE")
        now = self.clock()
        elapsed = round(max(0, now - self.started) * 1000, 3)
        self.chunks += 1
        if self.chunks > MAX_CHUNKS:
            raise InvalidInput("TOO_MANY_CHUNKS")
        if self.first_chunk_ms is None:
            self.first_chunk_ms = elapsed
        if content:
            self.content_chunks += 1
            self.content_chars += len(content)
            self.whitespace_chars += sum(char.isspace() for char in content)
            if self.first_content_ms is None:
                self.first_content_ms = elapsed
            if not content.isspace() and self.first_non_whitespace_ms is None:
                self.first_non_whitespace_ms = elapsed
            scan = self.marker_tail + content
            self.reasoning_markers_seen |= "<think>" in scan or "</think>" in scan
            self.marker_tail = scan[-8:]
        if thinking:
            self.thinking_chunks += 1
            self.thinking_chars += len(thinking)
            if self.first_thinking_ms is None:
                self.first_thinking_ms = elapsed
        if response.get("done") is True:
            self.final = response
        if self.mode == "stream" and (self.chunks == 1 or now - self.last_report >= 1):
            self.report("progress", final_response_received=self.final is not None)
            self.last_report = now


def parse(payload):
    try:
        return json.loads(payload)
    except (ValueError, UnicodeError, RecursionError):
        raise InvalidInput("INVALID_JSON") from None


def process(source, mode, emit, clock=time.monotonic):
    state = Measurements(mode, emit, clock)
    state.report("started")
    try:
        if mode == "non-stream":
            payload = source.read(MAX_LINE_BYTES + 1)
            if len(payload) > MAX_LINE_BYTES:
                raise InvalidInput("RESPONSE_TOO_LARGE")
            if payload.strip():
                state.consume(parse(payload))
        else:
            total = 0
            while True:
                line = source.readline(MAX_LINE_BYTES + 1)
                if not line:
                    break
                total += len(line)
                if len(line) > MAX_LINE_BYTES or total > MAX_STREAM_BYTES:
                    raise InvalidInput("RESPONSE_TOO_LARGE")
                if line.strip():
                    state.consume(parse(line))
        if state.final is None:
            state.report("incomplete", final_response_received=False, server_metrics_available=False)
            return 2
        state.report("complete", final_response_received=True, **metrics(state.final))
        return 0
    except InvalidInput as error:
        state.report("error", code=error.args[0], final_response_received=False, server_metrics_available=False)
        return 2
    except OSError:
        state.report("error", code="INPUT_IO_ERROR", final_response_received=False, server_metrics_available=False)
        return 2


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("stream", "non-stream"), required=True)
    args = parser.parse_args()

    def emit(value):
        print(json.dumps(value, allow_nan=False), flush=True)

    try:
        return process(sys.stdin.buffer, args.mode, emit)
    except KeyboardInterrupt:
        emit({"event": "error", "code": "INTERRUPTED", "server_metrics_available": False})
        return 130
    except Exception:
        # Even unexpected parser failures must not turn provider text into a traceback.
        emit({"event": "error", "code": "DIAGNOSTIC_ERROR", "server_metrics_available": False})
        return 2


if __name__ == "__main__":
    sys.exit(main())
