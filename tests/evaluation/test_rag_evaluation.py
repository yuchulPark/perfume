"""Small offline tests. All perfume IDs, labels and responses here are synthetic."""
import ast
import contextlib
import copy
import importlib.util
import io
import json
import math
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("rag_evaluation", ROOT / "scripts/rag-evaluation.py")
evaluation = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(evaluation)


class RagEvaluationTests(unittest.TestCase):
    def setUp(self):
        self.dataset = evaluation.load_json(ROOT / "evaluation/examples/synthetic-dataset.json")
        self.run = evaluation.load_json(ROOT / "evaluation/examples/synthetic-run.json")

    def report(self, ks=(1, 3, 5)):
        return evaluation.evaluate(self.dataset, self.run, ks)

    def query(self, name="syn-ranked"):
        return next(query for query in self.dataset["queries"] if query["id"] == name)

    def record(self, name="syn-ranked"):
        return next(row for row in self.run["records"] if row["query_id"] == name)

    def row(self, name="syn-ranked", report=None):
        return next(row for row in (report or self.report())["queries"] if row["query_id"] == name)

    def rebind(self):
        self.run["dataset_sha256"] = evaluation.sha(self.dataset)

    def test_precision_recall_hit_rate_and_reciprocal_rank(self):
        values = self.row()["retrieval"]
        self.assertEqual(1, values["1"]["precision"]["value"])
        self.assertEqual(0.5, values["1"]["recall"]["value"])
        self.assertAlmostEqual(2 / 3, values["3"]["precision"]["value"])
        self.assertEqual(1, values["3"]["recall"]["value"])
        self.assertEqual(1, values["3"]["reciprocal_rank"]["value"])
        self.assertEqual(1, values["3"]["hit_rate"]["value"])

    def test_first_relevant_rank_and_graded_ndcg(self):
        hits = self.record()["search"]["response"]["results"]
        hits[0], hits[1] = hits[1], hits[0]
        values = self.row()["retrieval"]["3"]
        self.assertEqual(0.5, values["reciprocal_rank"]["value"])
        expected = (7 / math.log2(3) + 1 / math.log2(4)) / (7 + 1 / math.log2(3))
        self.assertAlmostEqual(expected, values["ndcg"]["value"])

    def test_precision_denominator_is_k_even_if_fewer_results_exist(self):
        self.assertEqual(0.4, self.row()["retrieval"]["5"]["precision"]["value"])

    def test_no_approved_labels_are_not_assumed_from_search_results(self):
        values = self.row("syn-unlabeled")["retrieval"]["3"]
        self.assertTrue(all(value["value"] is None for value in values.values()))
        self.assertEqual({"NO_APPROVED_JUDGMENTS"}, {value["reason"] for value in values.values()})

    def test_draft_labels_do_not_count_as_ground_truth(self):
        self.query()["review"] = {"status": "draft", "reviewer": None, "reviewed_at": None}
        self.rebind()
        report = self.report()
        self.assertIsNone(self.row(report=report)["retrieval"]["1"]["precision"]["value"])
        self.assertEqual(2, report["summary"]["questions_with_qrels"])

    def test_pooled_unjudged_hits_are_unknown_not_negative(self):
        values = self.row("syn-pool")["retrieval"]["3"]
        self.assertTrue(all(value["value"] is None for value in values.values()))
        self.assertEqual({"UNJUDGED_RESULTS"}, {value["reason"] for value in values.values()})

    def test_judged_pool_supports_precision_but_not_global_recall_or_ndcg(self):
        record = self.record("syn-pool")
        record["search"]["response"]["results"] = [record["search"]["response"]["results"][1]]
        record["search"]["response"]["returnedCount"] = 1
        values = self.row("syn-pool")["retrieval"]["1"]
        self.assertEqual(1, values["precision"]["value"])
        self.assertEqual(1, values["reciprocal_rank"]["value"])
        self.assertEqual("NONEXHAUSTIVE_JUDGMENTS", values["recall"]["reason"])
        self.assertEqual("NONEXHAUSTIVE_JUDGMENTS", values["ndcg"]["reason"])

    def test_duplicates_consume_original_slots_without_second_credit(self):
        response = self.record()["search"]["response"]
        a, _, b = response["results"]
        response["results"] = [a, copy.deepcopy(a), b]
        row = self.row(report=self.report((2, 3)))
        values = row["retrieval"]["2"]
        self.assertEqual(0.5, values["precision"]["value"])
        self.assertEqual(0.5, values["recall"]["value"])
        self.assertEqual(1, row["search_duplicate_count"])
        self.assertIn("DUPLICATE_SEARCH_RESULTS", row["review_flags"])
        self.assertAlmostEqual(7 / (7 + 1 / math.log2(3)), values["ndcg"]["value"])

    def test_empty_search_with_known_relevant_perfumes_scores_zero(self):
        self.record()["search"]["response"] = {"results": [], "returnedCount": 0}
        values = self.row()["retrieval"]["3"]
        self.assertEqual([0.0] * 5, [value["value"] for value in values.values()])

    def test_confirmed_no_match_has_explicit_undefined_metrics(self):
        values = self.row("syn-no-match")["retrieval"]["3"]
        self.assertEqual(0, values["precision"]["value"])
        self.assertEqual(0, values["hit_rate"]["value"])
        for name in ("recall", "ndcg", "reciprocal_rank"):
            self.assertIsNone(values[name]["value"])
        self.assertEqual(1, self.row("syn-no-match")["recommendation"]["metrics"]["human_uncertainty_appropriate"]["value"])

    def test_uncaptured_depth_is_not_zero_padded_as_if_observed(self):
        values = self.row(report=self.report((10,)))["retrieval"]["10"]
        self.assertEqual({"DEPTH_NOT_CAPTURED"}, {value["reason"] for value in values.values()})

    def test_failed_search_and_absent_records_remain_visible_in_coverage(self):
        self.run["records"] = [record for record in self.run["records"] if record["query_id"] != "syn-ranked"]
        report = self.report()
        self.assertEqual(1, report["summary"]["search_status_counts"]["failed"])
        self.assertEqual(1, report["summary"]["search_status_counts"]["not_captured"])
        self.assertEqual(1, report["summary"]["recommendation_status_counts"]["timed_out"])
        self.assertIsNone(self.row("syn-failed", report)["retrieval"]["1"]["precision"]["value"])

    def test_macro_means_have_metric_specific_denominators_and_include_zero(self):
        summary = self.report()["summary"]
        precision = summary["retrieval"]["3"]["precision"]
        self.assertAlmostEqual(1 / 3, precision["mean"])
        self.assertEqual(2, precision["eligible_questions"])
        self.assertEqual(3, precision["ineligible_questions"])
        self.assertEqual(1, summary["retrieval"]["3"]["recall"]["eligible_questions"])
        self.assertEqual(3, summary["retrieval_unevaluable_questions"])

    def test_categories_and_cases_needing_review_are_reported(self):
        report = self.report()
        self.assertEqual(1, report["by_category"]["accord"]["questions"])
        case = next(row for row in report["cases_needing_review"] if row["query_id"] == "syn-ranked")
        self.assertIn("REVIEW_CONDITION_SATISFACTION", case["reasons"])

    def test_fact_integrity_and_question_accuracy_are_independent(self):
        metrics = self.row()["recommendation"]["metrics"]
        self.assertEqual(1, metrics["evidence_integrity"]["value"])
        self.assertEqual(1, metrics["citation_binding"]["value"])
        self.assertEqual(0, metrics["condition_satisfaction"]["value"])
        self.assertEqual(0, metrics["selected_relevance"]["value"])
        self.assertEqual(0, metrics["human_question_relevance"]["value"])
        self.assertEqual(2, metrics["selected_rank_mean"]["value"])
        self.assertEqual(0, metrics["rank_prefix_selected"]["value"])

    def test_current_public_response_alone_cannot_prove_fact_id_or_database_grounding(self):
        capture = self.record()["recommendation"]
        capture["picks"] = capture["evidence_reference"] = None
        result = self.row()["recommendation"]
        self.assertEqual("EVIDENCE_TRACE_NOT_CAPTURED", result["metrics"]["evidence_integrity"]["reason"])
        self.assertEqual("not_evaluated", result["grounding_scope"])
        self.assertEqual(1, result["metrics"]["citation_binding"]["value"])

    def test_source_and_local_fact_id_cannot_be_resolved_against_another_source(self):
        self.record()["recommendation"]["picks"] = [{"source": 1, "factId": 1}]
        result = self.row()["recommendation"]
        self.assertEqual(0, result["metrics"]["evidence_integrity"]["value"])
        self.assertIn("RENDERED_ANSWER_MISMATCH", result["issues"])
        self.assertIn("TRACE_CITATION_MISMATCH", result["issues"])

    def test_unknown_fact_id_is_an_integrity_failure(self):
        self.record()["recommendation"]["picks"][0]["factId"] = 999
        result = self.row()["recommendation"]
        self.assertIn("UNKNOWN_SOURCE_FACT_PAIR", result["issues"])
        self.assertEqual(0, result["metrics"]["evidence_integrity"]["value"])

    def test_reference_source_hash_detects_changed_exported_text(self):
        source = self.record()["recommendation"]["evidence_reference"]["sources"][1]
        source["source_text"] += "\nMODIFIED SYNTHETIC DATA"
        self.assertIn("SOURCE_HASH_MISMATCH", self.row()["recommendation"]["issues"])

    def test_reference_from_different_prompt_config_cannot_validate_current_picks(self):
        self.run["config"]["prompt_revision"] = "synthetic-different-prompt"
        row = self.row()["recommendation"]
        self.assertIn("REFERENCE_CONTEXT_MISMATCH", row["issues"])
        self.assertIsNone(row["exposed_candidate_count"])
        self.assertEqual("STALE_RESPONSE_REVIEW", row["metrics"]["human_question_relevance"]["reason"])

    def test_matching_snapshot_records_actual_exposed_candidates_separately_from_cap(self):
        row = self.row()["recommendation"]
        self.assertEqual(2, row["exposed_candidate_count"])
        self.assertFalse(row["rank_choice_constrained_by_cap"])

    def test_same_answer_under_a_different_query_does_not_reuse_human_review(self):
        self.record()["recommendation"]["human_review"]["query_sha256"] = "f" * 64
        self.assertEqual("STALE_RESPONSE_REVIEW", self.row()["recommendation"]["metrics"]["human_question_relevance"]["reason"])

    def test_reference_source_must_match_the_actual_ranked_perfume(self):
        self.record()["recommendation"]["evidence_reference"]["sources"][1]["perfume_id"] = 900001
        self.assertIn("REFERENCE_SOURCE_MISMATCH", self.row()["recommendation"]["issues"])

    def test_invented_extra_answer_text_fails_render_integrity(self):
        self.record()["recommendation"]["response"]["answer"] += "\n가상으로 추가한 무근거 주장"
        self.assertIn("RENDERED_ANSWER_MISMATCH", self.row()["recommendation"]["issues"])

    def test_unapproved_reference_is_not_treated_as_a_grounding_oracle(self):
        self.record()["recommendation"]["evidence_reference"]["review"]["status"] = "draft"
        self.assertEqual("REFERENCE_NOT_APPROVED", self.row()["recommendation"]["metrics"]["evidence_integrity"]["reason"])

    def test_out_of_range_citations_do_not_select_a_perfume_by_guess(self):
        self.record()["recommendation"]["response"]["answer"] = "가상 응답 [99]"
        result = self.row()["recommendation"]
        self.assertEqual([], result["selected_perfume_ids"])
        self.assertEqual(0, result["metrics"]["citation_binding"]["value"])
        self.assertIsNone(result["metrics"]["condition_satisfaction"]["value"])

    def test_missing_condition_labels_are_not_inferred_from_similarity_or_grade(self):
        self.query()["qrels"][2]["condition_satisfied"] = None
        self.rebind()
        self.assertEqual("MISSING_CONDITION_LABELS", self.row()["recommendation"]["metrics"]["condition_satisfaction"]["reason"])

    def test_stale_human_review_cannot_judge_a_different_answer(self):
        self.record()["recommendation"]["response"]["answer"] += " "
        self.assertEqual("STALE_RESPONSE_REVIEW", self.row()["recommendation"]["metrics"]["human_question_relevance"]["reason"])

    def test_relevance_threshold_is_recorded_and_used_consistently(self):
        self.query()["qrels"][2]["grade"] = 1
        self.rebind()
        report = evaluation.evaluate(self.dataset, self.run, (1,), threshold=2)
        self.assertEqual(2, report["metadata"]["relevant_grade_threshold"])
        self.assertEqual(0, self.row(report=report)["recommendation"]["metrics"]["selected_relevance"]["value"])

    def test_invalid_labels_are_rejected_instead_of_coerced(self):
        for grade in (True, -1, 4, 1.5, "3", None):
            with self.subTest(grade=grade):
                data = copy.deepcopy(self.dataset)
                data["queries"][0]["qrels"][0]["grade"] = grade
                with self.assertRaises(evaluation.InvalidInput):
                    evaluation.validate_dataset(data)

    def test_duplicate_qrels_and_contradictory_no_match_are_rejected(self):
        self.query()["qrels"].append(copy.deepcopy(self.query()["qrels"][0]))
        with self.assertRaisesRegex(evaluation.InvalidInput, "DUPLICATE_QREL"):
            evaluation.validate_dataset(self.dataset)
        self.query()["qrels"].pop()
        self.query()["answerability"] = "no_match"
        with self.assertRaisesRegex(evaluation.InvalidInput, "CONTRADICTORY_NO_MATCH"):
            evaluation.validate_dataset(self.dataset)

    def test_invalid_dataset_shapes_and_duplicate_query_ids_are_rejected(self):
        variants = [[], {**self.dataset, "unexpected": True}]
        duplicate = copy.deepcopy(self.dataset)
        duplicate["queries"].append(copy.deepcopy(duplicate["queries"][0]))
        variants.append(duplicate)
        bad_category = copy.deepcopy(self.dataset)
        bad_category["queries"][0]["category"] = []
        variants.append(bad_category)
        for data in variants:
            with self.subTest(data_type=type(data).__name__):
                with self.assertRaises(evaluation.InvalidInput):
                    evaluation.validate_dataset(data)

    def test_dataset_and_query_hash_mismatches_are_rejected(self):
        for field in ("dataset_sha256", "query_sha256"):
            with self.subTest(field=field):
                run = copy.deepcopy(self.run)
                target = run if field == "dataset_sha256" else run["records"][0]
                target[field] = "f" * 64
                with self.assertRaises(evaluation.InvalidInput):
                    evaluation.evaluate(self.dataset, run)

    def test_synthetic_data_cannot_be_presented_as_a_real_run(self):
        self.run["data_kind"] = "real"
        with self.assertRaisesRegex(evaluation.InvalidInput, "DATA_KIND_MISMATCH"):
            self.report()

    def test_bad_capture_count_unknown_query_duplicate_record_and_nonfinite_similarity(self):
        for kind in ("count", "unknown", "duplicate", "nan", "boolean", "status"):
            with self.subTest(kind=kind):
                run = copy.deepcopy(self.run)
                record = run["records"][0]
                if kind == "count": record["search"]["response"]["returnedCount"] = 4
                if kind == "unknown": record["query_id"] = "unknown"
                if kind == "duplicate": run["records"].append(copy.deepcopy(record))
                if kind == "nan": record["search"]["response"]["results"][0]["similarity"] = float("nan")
                if kind == "boolean": record["search"]["response"]["results"][0]["perfumeId"] = True
                if kind == "status": record["search"]["status"] = []
                with self.assertRaises(evaluation.InvalidInput):
                    evaluation.evaluate(self.dataset, run)

    def test_json_duplicate_keys_nonfinite_constants_and_invalid_encoding_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "input.json"
            for data in (b'{"key":1,"key":2}', b'{"key":NaN}', b'{"key":Infinity}', b'\xffnot-json'):
                path.write_bytes(data)
                with self.assertRaises(evaluation.InvalidInput):
                    evaluation.load_json(path)

    def test_repeated_evaluation_is_identical_and_does_not_modify_inputs(self):
        original = copy.deepcopy((self.dataset, self.run))
        first, second = self.report(), self.report()
        self.assertEqual(evaluation.canonical(first), evaluation.canonical(second))
        self.assertEqual(original, (self.dataset, self.run))
        with tempfile.TemporaryDirectory() as directory:
            a, b = Path(directory) / "a", Path(directory) / "b"
            evaluation.write_output(a, first)
            evaluation.write_output(b, second)
            for name in ("report.json", "cases.csv"):
                self.assertEqual((a / name).read_bytes(), (b / name).read_bytes())

    def test_reports_omit_questions_answers_evidence_and_names(self):
        rendered = evaluation.canonical(self.report()).decode("utf-8")
        for private in (self.query()["query"], self.record()["recommendation"]["response"]["answer"],
                        self.record()["recommendation"]["response"]["sources"][0]["name"], "가상 시트러스»"):
            self.assertNotIn(private, rendered)

    def test_existing_output_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "report"
            path.mkdir()
            sentinel = path / "state.json"
            sentinel.write_text("DO NOT CHANGE", encoding="utf-8")
            with self.assertRaises(FileExistsError):
                evaluation.write_output(path, self.report())
            self.assertEqual("DO NOT CHANGE", sentinel.read_text(encoding="utf-8"))

    def test_comparison_uses_only_paired_eligible_queries_and_absolute_delta(self):
        before = self.report()
        response = self.record()["search"]["response"]
        response["results"] = [response["results"][1], response["results"][0], response["results"][2]]
        self.run["run_id"] = "synthetic-after"
        after = self.report()
        comparison = evaluation.compare_reports(before, after)
        metric = comparison["retrieval"]["1"]["precision"]
        self.assertEqual(2, metric["paired_questions"])
        self.assertEqual(3, metric["excluded_questions"])
        self.assertEqual(-0.5, metric["mean_score_delta"])
        self.assertNotIn("improvement_percent", evaluation.canonical(comparison).decode())

    def test_different_dataset_or_metric_policy_cannot_be_compared(self):
        report = self.report()
        for key, value in (("dataset_sha256", "f" * 64), ("relevant_grade_threshold", 2), ("k_values", [5]), ("tool_sha256", "f" * 64)):
            with self.subTest(key=key):
                changed = copy.deepcopy(report)
                changed["metadata"][key] = value
                with self.assertRaisesRegex(evaluation.InvalidInput, "INCOMPARABLE_REPORTS"):
                    evaluation.compare_reports(report, changed)

    def test_changed_corpus_and_query_sets_cannot_be_compared(self):
        report = self.report()
        changed = copy.deepcopy(report)
        changed["metadata"]["config"]["corpus_snapshot"] = "different-corpus"
        with self.assertRaisesRegex(evaluation.InvalidInput, "INCOMPARABLE_CORPUS"):
            evaluation.compare_reports(report, changed)
        changed = copy.deepcopy(report)
        changed["queries"].pop()
        with self.assertRaisesRegex(evaluation.InvalidInput, "INCOMPARABLE_QUERY_SETS"):
            evaluation.compare_reports(report, changed)

    def test_invalid_k_values_and_thresholds_are_rejected(self):
        for ks in ([], [0], [21], [True], [1.5]):
            with self.subTest(ks=ks), self.assertRaises(evaluation.InvalidInput):
                evaluation.evaluate(self.dataset, self.run, ks)
        for threshold in (0, 4, True):
            with self.subTest(threshold=threshold), self.assertRaises(evaluation.InvalidInput):
                evaluation.evaluate(self.dataset, self.run, threshold=threshold)

    def test_invalid_generation_settings_cannot_be_recorded_as_reproducible_metadata(self):
        for temperature in (True, "0.2", float("nan"), -1):
            with self.subTest(temperature=temperature):
                run = copy.deepcopy(self.run)
                run["config"]["generation_options"]["temperature"] = temperature
                with self.assertRaises(evaluation.InvalidInput):
                    evaluation.evaluate(self.dataset, run)

    def test_report_does_not_mutate_input_config_when_preparing_comparison(self):
        before = self.report()
        baseline_config = copy.deepcopy(before["metadata"]["config"])
        self.run["config"]["search_top_k"] = 20
        evaluation.evaluate(self.dataset, self.run)
        self.assertEqual(baseline_config, before["metadata"]["config"])

    def test_comparison_keeps_uncaptured_top_k_out_of_paired_denominators(self):
        before = self.report((5, 10, 20))
        self.run["config"]["search_top_k"] = 20
        after = self.report((5, 10, 20))
        comparison = evaluation.compare_reports(before, after)
        self.assertEqual(0, comparison["retrieval"]["10"]["precision"]["paired_questions"])
        self.assertIsNone(comparison["retrieval"]["10"]["precision"]["mean_score_delta"])

    def test_cli_safe_success_and_failure_messages(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "report"
            with contextlib.redirect_stdout(io.StringIO()) as stdout:
                code = evaluation.main(["evaluate", "--dataset", str(ROOT / "evaluation/examples/synthetic-dataset.json"),
                    "--run", str(ROOT / "evaluation/examples/synthetic-run.json"), "--output-dir", str(output)])
            self.assertEqual(0, code)
            self.assertEqual("EVALUATION_WRITTEN\n", stdout.getvalue())
            secret = Path(directory) / "SECRET-INPUT.json"
            secret.write_text('{"SECRET-PASSWORD": NaN}', encoding="utf-8")
            with contextlib.redirect_stderr(io.StringIO()) as stderr:
                code = evaluation.main(["validate-dataset", "--dataset", str(secret)])
            self.assertEqual(2, code)
            self.assertNotIn("SECRET", stderr.getvalue())

    def test_twenty_five_real_question_templates_have_no_fabricated_answers(self):
        dataset = evaluation.validate_dataset(evaluation.load_json(ROOT / "evaluation/datasets/scent-archive-ko-v1.json"))
        self.assertEqual(25, len(dataset["queries"]))
        self.assertIsNone(dataset["corpus_snapshot"])
        self.assertEqual(evaluation.CATEGORIES, {query["category"] for query in dataset["queries"]})
        self.assertTrue(all(query["qrels"] == [] and query["review"]["status"] == "draft" for query in dataset["queries"]))

    def test_fingerprints_include_no_query_text(self):
        with contextlib.redirect_stdout(io.StringIO()) as stdout:
            code = evaluation.main(["validate-dataset", "--dataset", str(ROOT / "evaluation/examples/synthetic-dataset.json"), "--fingerprints"])
        self.assertEqual(0, code)
        fingerprints = json.loads(stdout.getvalue())
        self.assertEqual(evaluation.sha(self.dataset), fingerprints["dataset_sha256"])
        self.assertEqual(evaluation.sha(self.query()["query"]), fingerprints["queries"]["syn-ranked"])
        self.assertNotIn(self.query()["query"], stdout.getvalue())

    def test_cli_comparison_writes_json_and_csv_from_saved_reports(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            evaluation.write_output(root / "first", self.report())
            evaluation.write_output(root / "second", self.report())
            with contextlib.redirect_stdout(io.StringIO()):
                code = evaluation.main(["compare", "--before", str(root / "first/report.json"),
                    "--after", str(root / "second/report.json"), "--output-dir", str(root / "comparison")])
            self.assertEqual(0, code)
            self.assertTrue((root / "comparison/comparison.csv").is_file())
            self.assertTrue((root / "comparison/comparison.json").is_file())

    def test_empty_approved_pool_and_unstamped_approval_are_invalid(self):
        self.query("syn-pool")["qrels"] = []
        with self.assertRaisesRegex(evaluation.InvalidInput, "EMPTY_JUDGED_POOL"):
            evaluation.validate_dataset(self.dataset)
        self.query("syn-pool")["qrels"] = [{"perfume_id": 900001, "grade": 3, "condition_satisfied": True}]
        self.query()["review"]["reviewer"] = None
        with self.assertRaisesRegex(evaluation.InvalidInput, "MISSING_REVIEW_PROVENANCE"):
            evaluation.validate_dataset(self.dataset)

    def test_tool_has_no_network_database_process_or_application_integration(self):
        tree = ast.parse((ROOT / "scripts/rag-evaluation.py").read_text(encoding="utf-8"))
        imports = {node.names[0].name.split(".")[0] for node in ast.walk(tree) if isinstance(node, ast.Import)}
        imports |= {node.module.split(".")[0] for node in ast.walk(tree) if isinstance(node, ast.ImportFrom)}
        self.assertTrue(imports <= {"argparse", "collections", "copy", "csv", "datetime", "hashlib", "json", "math", "pathlib", "re", "sys"})
        with patch("socket.socket", side_effect=AssertionError("network forbidden")), patch("subprocess.Popen", side_effect=AssertionError("process forbidden")):
            self.report()


if __name__ == "__main__":
    unittest.main()
