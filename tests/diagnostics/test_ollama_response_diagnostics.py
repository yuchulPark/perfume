"""Offline stdin fixtures only. No Ollama, HTTP, Docker or database calls."""
import importlib.util
import io
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("ollama_diagnostics", ROOT / "scripts/ollama-response-diagnostics.py")
DIAGNOSTICS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DIAGNOSTICS)


class Clock:
    def __init__(self):
        self.value = 0

    def __call__(self):
        self.value += 0.5
        return self.value


def final():
    return {"done": True, "done_reason": "stop", "message": {"content": ""}, "total_duration": 6000000000,
            "load_duration": 1000000000, "prompt_eval_duration": 2000000000, "eval_duration": 3000000000,
            "prompt_eval_count": 100, "eval_count": 6}


class OllamaResponseDiagnosticsTests(unittest.TestCase):
    def run_data(self, data, mode="stream"):
        events = []
        status = DIAGNOSTICS.process(io.BytesIO(data), mode, events.append, Clock())
        rendered = json.dumps(events)
        self.assertNotIn("private-", rendered)
        self.assertNotIn("Bergamot", rendered)
        return status, events

    def test_stream_reports_progress_and_final_rates_without_body_or_thinking_text(self):
        rows = [{"done": False, "message": {"thinking": "private-thinking"}},
                {"done": False, "message": {"content": "   "}},
                {"done": False, "message": {"content": "private-answer Bergamot"}}, final()]
        status, events = self.run_data(b"\n".join(json.dumps(row).encode() for row in rows))
        self.assertEqual(status, 0)
        self.assertIn("progress", [event["event"] for event in events])
        summary = events[-1]
        self.assertEqual(summary["event"], "complete")
        self.assertEqual(summary["chunks"], 4)
        self.assertEqual(summary["content_chunks"], 2)
        self.assertEqual(summary["thinking_chunks"], 1)
        self.assertEqual(summary["whitespace_chars"], 4)
        self.assertLess(summary["first_content_ms"], summary["first_non_whitespace_ms"])
        self.assertEqual(summary["generated_tokens_per_second"], 2)
        self.assertEqual(summary["prompt_tokens_per_second"], 50)
        self.assertTrue(summary["server_metrics_available"])

    def test_nonstream_pretty_json_has_no_fictional_per_token_progress(self):
        data = final(); data["message"]["content"] = "private-answer"
        status, events = self.run_data(json.dumps(data, indent=2).encode(), "non-stream")
        self.assertEqual(status, 0)
        self.assertEqual([event["event"] for event in events], ["started", "complete"])
        self.assertEqual(events[-1]["content_chunks"], 1)

    def test_incomplete_timeout_stream_retains_counts_but_has_no_final_server_metrics(self):
        status, events = self.run_data(b'{"message":{"content":"private-partial"},"done":false,"eval_count":999}\n')
        self.assertEqual(status, 2)
        self.assertEqual(events[-1]["event"], "incomplete")
        self.assertGreater(events[-1]["content_chars"], 0)
        self.assertFalse(events[-1]["server_metrics_available"])
        self.assertNotIn("eval_count", events[-1])

    def test_blank_and_empty_input_are_incomplete(self):
        for mode in ("stream", "non-stream"):
            with self.subTest(mode=mode):
                status, events = self.run_data(b"\n  \n", mode)
                self.assertEqual(status, 2)
                self.assertEqual(events[-1]["event"], "incomplete")

    def test_error_body_and_malformed_json_never_escape(self):
        for payload, code in [(b'{"error":"private-password"}\n', "OLLAMA_ERROR"),
                              (b"private-html-error", "INVALID_JSON"),
                              (b'{"message":{"content":"private-cut', "INVALID_JSON"),
                              (b"\xffprivate-body", "INVALID_JSON"),
                              (b"[]", "INVALID_RESPONSE"),
                              (b'{"done":"private-bad"}', "INVALID_RESPONSE")]:
            with self.subTest(code=code, size=len(payload)):
                status, events = self.run_data(payload)
                self.assertEqual(status, 2)
                self.assertEqual(events[-1]["code"], code)

    def test_missing_zero_and_untrusted_metrics_are_safe_and_not_infinite(self):
        for value in (None, -1, "private-value", 1.5, True, 10**40, float("nan")):
            with self.subTest(value_type=type(value).__name__):
                row = final(); row["eval_duration"] = value; row["done_reason"] = "private-body"
                status, events = self.run_data(json.dumps(row).encode())
                self.assertEqual(status, 0)
                self.assertIsNone(events[-1]["generated_tokens_per_second"])
                self.assertIsNone(events[-1]["eval_duration"])
                self.assertEqual(events[-1]["done_reason"], "unavailable")
                self.assertFalse(events[-1]["server_metrics_available"])
        row = final(); row["eval_duration"] = 0
        _, events = self.run_data(json.dumps(row).encode())
        self.assertIsNone(events[-1]["generated_tokens_per_second"])

    def test_missing_metrics_remain_unknown_after_done(self):
        status, events = self.run_data(b'{"done":true,"message":{"content":"private-answer"}}')
        self.assertEqual(status, 0)
        self.assertFalse(events[-1]["server_metrics_available"])
        self.assertIsNone(events[-1]["load_duration"])

    def test_split_reasoning_markers_are_only_reported_as_a_boolean(self):
        rows = [{"message": {"content": "<thi"}}, {"message": {"content": "nk>private-thought"}}, final()]
        _, events = self.run_data(b"\n".join(json.dumps(row).encode() for row in rows))
        self.assertTrue(events[-1]["reasoning_markers_seen"])

    def test_length_completion_remains_visible_without_saying_the_answer_is_valid(self):
        row = final(); row["done_reason"] = "length"; row["eval_count"] = 512
        _, events = self.run_data(json.dumps(row).encode())
        self.assertEqual(events[-1]["done_reason"], "length")
        self.assertEqual(events[-1]["eval_count"], 512)

    def test_bounds_stream_lines_nonstream_body_and_number_of_chunks(self):
        for mode in ("stream", "non-stream"):
            with self.subTest(mode=mode):
                status, events = self.run_data(b" " * (DIAGNOSTICS.MAX_LINE_BYTES + 1), mode)
                self.assertEqual(status, 2)
                self.assertEqual(events[-1]["code"], "RESPONSE_TOO_LARGE")
        original = DIAGNOSTICS.MAX_CHUNKS
        try:
            DIAGNOSTICS.MAX_CHUNKS = 2
            status, events = self.run_data(b"{}\n{}\n{}\n")
            self.assertEqual(status, 2)
            self.assertEqual(events[-1]["code"], "TOO_MANY_CHUNKS")
        finally:
            DIAGNOSTICS.MAX_CHUNKS = original

    def test_extra_data_after_final_is_not_silently_accepted(self):
        status, events = self.run_data(json.dumps(final()).encode() + b'\n{"private-extra":"data"}\n')
        self.assertEqual(status, 2)
        self.assertEqual(events[-1]["code"], "EXTRA_RESPONSE")

    def test_generate_endpoint_metadata_can_also_be_summarized(self):
        row = final(); del row["message"]; row["response"] = "private-generated-response"
        status, events = self.run_data(json.dumps(row).encode(), "non-stream")
        self.assertEqual(status, 0)
        self.assertEqual(events[-1]["content_chars"], len(row["response"]))


if __name__ == "__main__":
    unittest.main()
