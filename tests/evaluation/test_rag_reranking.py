"""Rule/reranking tests use synthetic snapshots only; never call the application."""
import ast
from copy import deepcopy
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("rag_reranking", ROOT / "scripts/rag-reranking.py")
rr = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(rr)
ev = rr.evaluation


class RagRerankingTests(unittest.TestCase):
    def setUp(self):
        self.dataset, self.run, self.evidence = [ev.load_json(ROOT / "evaluation/examples" / ("synthetic-reranking-" + name + ".json"))
                                                 for name in ("dataset", "run", "evidence")]
        self.analyzer = rr.QueryConditionAnalyzer()

    def parsed(self, query):
        return [(row["kind"], row["value"], row["operator"]) for row in self.analyzer.analyze(query)]

    def assess(self, query, pid=910003):
        return rr.CandidateSuitabilityAssessor(self.evidence).assess(self.analyzer.analyze(query), pid)

    def candidate(self, pid=910003):
        return next(row for row in self.evidence["candidates"] if row["perfume_id"] == pid)

    def refresh(self):
        self.run["dataset_sha256"] = ev.sha(self.dataset)
        queries = {query["id"]: query for query in self.dataset["queries"]}
        for row in self.run["records"]:
            row["query_sha256"] = ev.sha(queries[row["query_id"]]["query"])
        self.evidence["run_sha256"], self.evidence["config_sha256"] = ev.sha(self.run), ev.sha(self.run["config"])
        for candidate in self.evidence["candidates"]:
            candidate["snapshot_sha256"] = ev.sha({key: candidate[key] for key in ("perfume_id", "notes", "accords", "metrics")})

    def experiment(self, enabled=True, depths=(5,), ks=(1, 5)):
        return rr.experiment(self.dataset, self.run, self.evidence, depths, ks, enabled)

    def test_single_included_note(self):
        self.assertEqual([("note", "vanilla", "include")], self.parsed("바닐라 향수 추천해 줘"))
        self.assertEqual("MATCH", self.assess("바닐라 향수 추천해 줘")["status"])

    def test_single_excluded_note_is_explicit_violation_when_present(self):
        self.assertEqual([("note", "vanilla", "exclude")], self.parsed("바닐라는 싫어"))
        self.assertEqual("MISMATCH", self.assess("바닐라는 싫어")["status"])

    def test_inclusion_exclusion_and_season_example_have_scoped_polarity(self):
        query = "여름에 사용하기 좋은 시트러스 향수인데 바닐라는 싫어"
        self.assertEqual([("season", "summer", "include"), ("accord", "citrus", "include"), ("note", "vanilla", "exclude")], self.parsed(query))
        assessment = self.assess(query)
        self.assertEqual(2, assessment["match_count"])
        self.assertEqual(1, assessment["mismatch_count"])

    def test_absent_excluded_note_is_unknown_even_with_nonempty_notes(self):
        assessment = self.assess("바닐라는 싫어", 910002)
        self.assertEqual("UNKNOWN", assessment["status"])
        self.assertEqual("ABSENCE_NOT_PROVEN", assessment["conditions"][0]["reason"])

    def test_absent_preferred_note_is_not_automatically_a_mismatch(self):
        self.assertEqual("UNKNOWN", self.assess("장미 향수 추천해 줘")["status"])

    def test_missing_note_data_is_unknown(self):
        self.assertEqual("UNKNOWN", self.assess("바닐라 향수 추천해 줘", 910004)["status"])
        self.assertEqual("SOURCE_FIELD_MISSING", self.assess("바닐라 향수 추천해 줘", 910004)["conditions"][0]["reason"])

    def test_korean_and_english_literal_aliases(self):
        for query in ("with vanilla perfume", "바닐라 향수 추천해 줘", "WITH VANILLA PERFUME"):
            with self.subTest(query=query):
                self.assertEqual("MATCH", self.assess(query)["status"])
        self.assertEqual([("note", "rose", "exclude")], self.parsed("without rose"))

    def test_negation_does_not_leak_between_terms(self):
        self.assertEqual([("note", "vanilla", "include"), ("note", "rose", "exclude")], self.parsed("with vanilla without rose"))
        self.assertEqual([("note", "vanilla", "include"), ("note", "rose", "exclude")], self.parsed("vanilla perfume but without rose"))
        self.assertEqual([("accord", "citrus", "include"), ("note", "vanilla", "exclude")], self.parsed("시트러스가 좋고 바닐라는 싫어"))

    def test_coordinated_exclusions_apply_to_both_terms(self):
        self.assertEqual([("note", "vanilla", "exclude"), ("note", "rose", "exclude")], self.parsed("바닐라와 장미는 싫어"))
        self.assertEqual([("note", "vanilla", "exclude"), ("note", "rose", "exclude")], self.parsed("without vanilla and rose"))

    def test_double_negation_and_optional_preferences_are_unknown(self):
        for query in ("바닐라는 싫지 않아", "바닐라가 없어도 괜찮아", "바닐라가 없는 향수는 싫어", "장미를 제외하지 마", "not without vanilla", "vanilla is not required", "I don't dislike vanilla", "do not exclude rose"):
            with self.subTest(query=query):
                self.assertTrue(all(condition["operator"] == "unknown" for condition in self.analyzer.analyze(query)))

    def test_english_double_negation_does_not_turn_into_exclusion(self):
        for query in ("rose is not disliked", "rose is not bad", "not rose-free perfume", "non-vanilla perfume", "no rose is fine"):
            with self.subTest(query=query):
                self.assertTrue(all(condition["operator"] == "unknown" for condition in self.analyzer.analyze(query)))

    def test_explicit_prefix_preferences_are_not_reversed_by_suffix_perfume(self):
        for query in ("제외할 바닐라 향수", "싫어하는 바닐라 향수", "원하지 않는 바닐라 향수", "I don't want vanilla perfume"):
            with self.subTest(query=query):
                self.assertIn(("note", "vanilla", "exclude"), self.parsed(query))
                self.assertNotIn(("note", "vanilla", "include"), self.parsed(query))

    def test_unsupported_or_and_conditional_logic_is_not_treated_as_and(self):
        for query in ("rose or vanilla perfume", "장미 또는 바닐라 향수", "vanilla perfume if summer"):
            with self.subTest(query=query):
                self.assertTrue(all(condition["operator"] == "unknown" for condition in self.analyzer.analyze(query)))

    def test_contradictory_conditions_are_unknown(self):
        self.assertTrue(all(condition["operator"] == "unknown" for condition in self.analyzer.analyze("with vanilla but without vanilla")))

    def test_substrings_and_unverified_synonyms_are_not_normalized(self):
        for value in ("rosemary", "rosewood", "로즈마리", "로즈힙", "grapefruit", "amber"):
            with self.subTest(value=value):
                self.assertIsNone(rr.canonical_term("note", value))
        self.assertNotIn(("note", "rose", "include"), self.parsed("rosemary perfume"))
        self.assertIsNone(rr.canonical_term("accord", "bergamot"))
        self.assertIsNone(rr.canonical_term("note", "citrus"))

    def test_accords_are_not_inferred_from_notes_or_description(self):
        candidate = self.candidate(910002)
        candidate["accords"] = []
        self.assertEqual("UNKNOWN", self.assess("시트러스 향수 추천해 줘", 910002)["status"])

    def test_explicit_citrus_note_is_not_silently_changed_to_citrus_accord(self):
        self.assertEqual("unknown", self.analyzer.analyze("시트러스 노트 향수 추천해 줘")[0]["operator"])

    def test_repeated_same_condition_does_not_increase_its_weight(self):
        values = self.analyzer.analyze("with vanilla perfume but with vanilla perfume")
        self.assertEqual(1, len(values))

    def test_unparsed_mood_remains_unknown_alongside_known_note(self):
        values = self.parsed("차분한 바닐라 향수 추천해 줘")
        self.assertIn(("note", "vanilla", "include"), values)
        self.assertIn(("unknown", None, "unknown"), values)
        self.assertEqual("UNKNOWN", self.assess("차분한 바닐라 향수 추천해 줘")["status"])

    def test_explicit_high_low_metric_conditions_are_extracted(self):
        self.assertEqual([("longevity", "high", "require")], self.parsed("지속력이 좋은 향수 추천해 줘"))
        self.assertEqual([("projection", "low", "require")], self.parsed("발향력이 약한 향수 추천해 줘"))
        self.assertEqual([("longevity", "high", "require")], self.parsed("long-lasting perfume"))
        self.assertEqual([("projection", "low", "require")], self.parsed("weak projection perfume"))

    def test_negated_metric_and_numeric_thresholds_are_not_guessed(self):
        for query in ("not long-lasting perfume", "발향력이 강하지 않은 향수", "10시간 이상 지속되는 향수"):
            with self.subTest(query=query):
                self.assertEqual("UNKNOWN", self.assess(query)["status"])

    def test_sillage_is_not_silently_equated_to_projection(self):
        self.assertTrue(all(condition["operator"] == "unknown" for condition in self.analyzer.analyze("low sillage perfume")))

    def test_reviewed_category_contract_can_match_or_mismatch(self):
        self.assertEqual("MATCH", self.assess("지속력이 좋은 향수 추천해 줘", 910003)["status"])
        self.assertEqual("MISMATCH", self.assess("지속력이 좋은 향수 추천해 줘", 910001)["status"])

    def test_large_numeric_score_with_unknown_scale_is_unknown(self):
        result = self.assess("지속력이 좋은 향수 추천해 줘", 910002)
        self.assertEqual("UNKNOWN", result["status"])
        self.assertEqual("UNKNOWN_METRIC_SCALE", result["conditions"][0]["reason"])

    def test_no_contract_means_no_numeric_or_category_inference(self):
        self.evidence["metric_contracts"] = []
        self.assertEqual("UNKNOWN", self.assess("지속력이 좋은 향수 추천해 줘")["status"])

    def test_unreviewed_contract_is_not_used(self):
        self.evidence["metric_contracts"][0]["review"]["status"] = "draft"
        self.assertEqual("UNKNOWN", self.assess("지속력이 좋은 향수 추천해 줘")["status"])

    def test_record_count_and_reliability_are_gates_not_scores(self):
        for field, value in (("records", 0), ("records", None), ("reliability", "low"), ("reliability", None), ("derived_from", "unverified_formula")):
            with self.subTest(field=field, value=value):
                original = deepcopy(self.evidence)
                self.candidate()["metrics"][0][field] = value
                self.assertEqual("UNKNOWN", self.assess("지속력이 좋은 향수 추천해 줘")["status"])
                self.evidence = original

    def test_conflicting_metric_evidence_is_unknown(self):
        contract = deepcopy(self.evidence["metric_contracts"][0])
        contract["id"], contract["metric_key"] = "synthetic-second", "another.longevity"
        self.evidence["metric_contracts"].append(contract)
        metric = deepcopy(self.candidate()["metrics"][0])
        metric["key"], metric["category"] = "another.longevity", "short_lasting"
        self.candidate()["metrics"].append(metric)
        result = self.assess("지속력이 좋은 향수 추천해 줘")
        self.assertEqual("CONFLICTING_METRIC_EVIDENCE", result["conditions"][0]["reason"])

    def test_season_is_not_inferred_from_citrus_or_notes(self):
        self.candidate(910002)["metrics"] = []
        self.assertEqual("UNKNOWN", self.assess("여름에 좋은 향수 추천해 줘", 910002)["status"])

    def test_season_with_approved_scale_category_and_observations_can_match(self):
        self.assertEqual("MATCH", self.assess("여름에 좋은 향수 추천해 줘", 910002)["status"])
        self.assertEqual("MISMATCH", self.assess("여름에 좋은 향수 추천해 줘", 910001)["status"])

    def test_unapproved_export_and_missing_candidate_are_unknown(self):
        self.assertEqual("UNKNOWN", self.assess("바닐라 향수 추천해 줘", 999999)["status"])
        self.evidence["review"]["status"] = "draft"
        self.assertEqual("UNKNOWN", self.assess("바닐라 향수 추천해 줘")["status"])

    def test_default_reranker_is_disabled_and_keeps_original_order(self):
        hits = self.run["records"][0]["search"]["response"]["results"]
        assessments = {hit["perfumeId"]: self.assess("바닐라 향수 추천해 줘", hit["perfumeId"]) for hit in hits}
        result = rr.CandidateReranker().rerank(hits, assessments)
        self.assertEqual(hits, result["hits"])
        self.assertEqual("DISABLED", result["decision"])
        self.assertEqual([], result["eligible_perfume_ids"])

    def test_confirmed_matches_precede_unknowns_and_keep_original_candidate_set(self):
        manifest, _ = self.experiment()
        case = next(case for case in manifest["depths"]["5"]["cases"] if case["query_id"] == "rr-include")
        self.assertEqual([910001, 910003, 910002, 910004], case["after_perfume_ids"])
        self.assertEqual(set(case["before_perfume_ids"]), set(case["after_perfume_ids"]))

    def test_exclusion_violations_follow_unknowns_and_do_not_become_eligible(self):
        manifest, _ = self.experiment()
        case = next(case for case in manifest["depths"]["5"]["cases"] if case["query_id"] == "rr-exclude")
        self.assertEqual([910002, 910004, 910001, 910003], case["after_perfume_ids"])
        self.assertEqual("INSUFFICIENT_EVIDENCE_ABSTAIN", case["decision"])
        self.assertEqual([], case["eligible_perfume_ids"])

    def test_all_mismatches_abstain_without_removing_candidates(self):
        hits = self.run["records"][0]["search"]["response"]["results"]
        hits = [hit for hit in hits if hit["perfumeId"] in (910001, 910003)]
        assessments = {hit["perfumeId"]: self.assess("바닐라는 싫어", hit["perfumeId"]) for hit in hits}
        result = rr.CandidateReranker().rerank(hits, assessments, True)
        self.assertEqual("ALL_CANDIDATES_MISMATCH", result["decision"])
        self.assertEqual(len(hits), len(result["hits"]))
        self.assertEqual([], result["eligible_perfume_ids"])

    def test_all_unknowns_keep_original_order_even_if_similarities_are_not_sorted(self):
        hits = deepcopy(self.run["records"][0]["search"]["response"]["results"])
        hits[0]["similarity"], hits[1]["similarity"] = 0.1, 0.99
        assessments = {hit["perfumeId"]: self.assess("차분한 향수", hit["perfumeId"]) for hit in hits}
        result = rr.CandidateReranker().rerank(hits, assessments, True)
        self.assertEqual(hits, result["hits"])
        self.assertEqual("INSUFFICIENT_EVIDENCE_ABSTAIN", result["decision"])

    def test_same_condition_and_same_similarity_preserve_original_rank(self):
        hits = [{"perfumeId": pid, "similarity": 0.5} for pid in (910003, 910001)]
        assessments = {hit["perfumeId"]: self.assess("바닐라 향수 추천해 줘", hit["perfumeId"]) for hit in hits}
        self.assertEqual(hits, rr.CandidateReranker().rerank(hits, assessments, True)["hits"])

    def test_similarity_is_used_after_equal_condition_counts(self):
        hits = [{"perfumeId": 910003, "similarity": 0.1}, {"perfumeId": 910001, "similarity": 0.9}]
        assessments = {hit["perfumeId"]: self.assess("바닐라 향수 추천해 줘", hit["perfumeId"]) for hit in hits}
        self.assertEqual(910001, rr.CandidateReranker().rerank(hits, assessments, True)["hits"][0]["perfumeId"])

    def test_empty_search_has_no_forced_recommendation(self):
        result = rr.CandidateReranker().rerank([], {}, True)
        self.assertEqual("NO_SEARCH_RESULTS", result["decision"])
        self.assertEqual([], result["eligible_perfume_ids"])

    def test_duplicate_search_candidates_are_rejected(self):
        with self.assertRaisesRegex(ev.InvalidInput, "DUPLICATE_SEARCH_CANDIDATES"):
            rr.CandidateReranker().rerank([{"perfumeId": 1}, {"perfumeId": 1}], {1: {}}, True)

    def test_a_b_evaluation_uses_same_ground_truth_and_candidate_set(self):
        manifest, artifacts = self.experiment()
        a, b, comparison = artifacts["5"]
        self.assertEqual(a["metadata"]["dataset_sha256"], b["metadata"]["dataset_sha256"])
        self.assertEqual(7, a["summary"]["questions"])
        self.assertEqual("synthetic", manifest["metadata"]["data_kind"])
        self.assertEqual("paired_queries_only_no_relative_improvement_claim", comparison["comparison_scope"])

    def test_no_labels_means_no_fabricated_retrieval_improvement(self):
        for query in self.dataset["queries"]:
            query["review"] = {"status": "draft", "reviewer": None, "reviewed_at": None}
            query["qrels"] = []
        self.refresh()
        _, artifacts = self.experiment()
        comparison = artifacts["5"][2]
        for values in comparison["retrieval"].values():
            for value in values.values():
                self.assertIsNone(value["mean_score_delta"])
                self.assertEqual(0, value["paired_questions"])

    def test_rule_rates_are_partitioned_diagnostics_not_human_accuracy(self):
        manifest, _ = self.experiment()
        self.assertTrue(manifest["metadata"]["condition_rates_are_rule_diagnostics_not_human_accuracy"])
        for case in manifest["depths"]["5"]["cases"]:
            for values in case["rates"].values():
                for rates in values.values():
                    defined = [rate["value"] for rate in rates.values() if rate["value"] is not None]
                    if defined:
                        self.assertAlmostEqual(1, sum(defined))

    def test_unobserved_candidate_depth_is_explicit(self):
        self.run["config"]["search_top_k"] = 5
        self.refresh()
        manifest, artifacts = self.experiment(depths=(5, 10, 20))
        self.assertEqual("DEPTH_NOT_CAPTURED", manifest["depths"]["10"]["status"])
        self.assertEqual({"5"}, set(artifacts))

    def test_missing_run_questions_count_as_unevaluable_in_rule_summaries(self):
        self.run["records"] = self.run["records"][:1]
        self.refresh()
        manifest, _ = self.experiment()
        self.assertEqual(7, len(manifest["depths"]["5"]["cases"]))
        metric = manifest["depths"]["5"]["rule_status_rates"]["1"]["a"]["MATCH"]
        self.assertEqual(6, metric["ineligible_questions"])

    def test_top_5_10_20_pool_boundaries_and_permutation_invariant_precision(self):
        self.run["records"] = self.run["records"][:1]
        hits = [{"perfumeId": pid, "name": "가상 향수", "brandName": "가상", "similarity": 1 - pid / 100} for pid in range(1, 21)]
        self.run["records"][0]["search"]["response"] = {"results": hits, "returnedCount": 20}
        self.run["config"]["embedding_coverage"] = {"total_perfumes": 20, "embedded_perfumes": 20}
        self.dataset["queries"][0]["qrels"] = [{"perfume_id": pid, "grade": 3 if pid == 20 else 0, "condition_satisfied": pid == 20} for pid in range(1, 21)]
        self.evidence["candidates"] = [{"perfume_id": pid, "notes": ["Vanilla"] if pid == 20 else ["Bergamot"], "accords": [], "metrics": [], "snapshot_sha256": "0" * 64} for pid in range(1, 21)]
        self.refresh()
        manifest, artifacts = self.experiment(depths=(5, 10, 20), ks=(1, 5, 10, 20))
        for depth in (5, 10, 20):
            case = next(row for row in manifest["depths"][str(depth)]["cases"] if row["query_id"] == "rr-include")
            self.assertEqual(set(range(1, depth + 1)), set(case["after_perfume_ids"]))
            a, b, _ = artifacts[str(depth)]
            self.assertEqual(a["queries"][0]["retrieval"][str(depth)]["precision"], b["queries"][0]["retrieval"][str(depth)]["precision"])
        case = next(row for row in manifest["depths"]["20"]["cases"] if row["query_id"] == "rr-include")
        self.assertEqual(20, case["after_perfume_ids"][0])

    def test_inputs_and_original_recommendation_response_are_never_modified(self):
        dataset = ev.load_json(ROOT / "evaluation/examples/synthetic-dataset.json")
        run = ev.load_json(ROOT / "evaluation/examples/synthetic-run.json")
        evidence = deepcopy(self.evidence)
        evidence.update(data_kind=dataset["data_kind"], run_sha256=ev.sha(run), config_sha256=ev.sha(run["config"]),
                        corpus_snapshot=run["config"]["corpus_snapshot"], embedding_snapshot=run["config"]["embedding_snapshot"])
        before = deepcopy((dataset, run, evidence))
        _, artifacts = rr.experiment(dataset, run, evidence, (5,), (1, 5), True)
        self.assertEqual(before, (dataset, run, evidence))
        self.assertEqual({"not_captured": 5}, artifacts["5"][0]["summary"]["recommendation_status_counts"])
        self.assertEqual({"not_captured": 5}, artifacts["5"][1]["summary"]["recommendation_status_counts"])
        self.assertIsNotNone(run["records"][0]["recommendation"]["response"])

    def test_all_twenty_five_existing_templates_are_reused_without_label_changes(self):
        dataset = ev.load_json(ROOT / "evaluation/datasets/scent-archive-ko-v1.json")
        before = ev.sha(dataset)
        for query in dataset["queries"]:
            self.assertTrue(self.analyzer.analyze(query["query"]))
            self.assertEqual([], query["qrels"])
        self.assertEqual(before, ev.sha(dataset))

    def test_snapshot_and_model_config_bindings_are_required(self):
        for key in ("run_sha256", "config_sha256", "corpus_snapshot", "embedding_snapshot"):
            with self.subTest(key=key):
                evidence = deepcopy(self.evidence)
                evidence[key] = "different"
                with self.assertRaises(ev.InvalidInput):
                    rr.validate_evidence(evidence, self.dataset, self.run)

    def test_modified_snapshot_without_hash_refresh_is_rejected(self):
        self.candidate()["notes"] = ["Rose"]
        with self.assertRaisesRegex(ev.InvalidInput, "CANDIDATE_HASH_MISMATCH"):
            self.experiment()

    def test_synthetic_evidence_cannot_be_used_as_real_evidence(self):
        self.evidence["data_kind"] = "real"
        with self.assertRaisesRegex(ev.InvalidInput, "EVIDENCE_DATA_KIND_MISMATCH"):
            self.experiment()

    def test_contract_cannot_map_one_category_to_both_states(self):
        self.evidence["metric_contracts"][0]["categories"]["low"] = ["long_lasting"]
        with self.assertRaisesRegex(ev.InvalidInput, "CONFLICTING_CONTRACT_CATEGORIES"):
            self.experiment()

    def test_reproducible_json_csv_and_output_overwrite_protection(self):
        first, artifacts = self.experiment()
        second, _ = self.experiment()
        self.assertEqual(ev.canonical(first), ev.canonical(second))
        with tempfile.TemporaryDirectory() as directory:
            one, two = Path(directory) / "one", Path(directory) / "two"
            rr.write_experiment(one, first, artifacts)
            rr.write_experiment(two, second, artifacts)
            self.assertEqual((one / "experiment.json").read_bytes(), (two / "experiment.json").read_bytes())
            self.assertEqual((one / "condition-rates.csv").read_bytes(), (two / "condition-rates.csv").read_bytes())
            with self.assertRaises(FileExistsError):
                rr.write_experiment(one, first, artifacts)

    def test_cli_defaults_to_disabled_and_has_safe_errors(self):
        paths = [str(ROOT / "evaluation/examples" / ("synthetic-reranking-" + name + ".json")) for name in ("dataset", "run", "evidence")]
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "result"
            args = ["--dataset", paths[0], "--run", paths[1], "--evidence", paths[2], "--output-dir", str(output)]
            with contextlib.redirect_stdout(io.StringIO()) as stream:
                self.assertEqual(0, rr.main(args))
            self.assertEqual("OFFLINE_RERANKING_WRITTEN\n", stream.getvalue())
            report = json.loads((output / "experiment.json").read_text(encoding="utf-8"))
            self.assertFalse(report["metadata"]["experimental_enabled"])
            with contextlib.redirect_stderr(io.StringIO()) as error:
                self.assertEqual(2, rr.main(args))
            self.assertEqual("RERANKING_REJECTED: OUTPUT_EXISTS\n", error.getvalue())

    def test_manifest_omits_original_query_candidate_names_and_raw_metric_values(self):
        manifest, _ = self.experiment()
        rendered = ev.canonical(manifest).decode("utf-8")
        self.assertNotIn(self.dataset["queries"][2]["query"], rendered)
        self.assertNotIn("가상 향수", rendered)
        self.assertNotIn('"score":', rendered)

    def test_no_network_database_process_or_application_imports(self):
        tree = ast.parse((ROOT / "scripts/rag-reranking.py").read_text(encoding="utf-8"))
        imports = {node.names[0].name.split(".")[0] for node in ast.walk(tree) if isinstance(node, ast.Import)}
        imports |= {node.module.split(".")[0] for node in ast.walk(tree) if isinstance(node, ast.ImportFrom)}
        self.assertTrue(imports <= {"argparse", "collections", "copy", "csv", "importlib", "json", "pathlib", "re", "sys", "unicodedata"})
        with patch("socket.socket", side_effect=AssertionError("network forbidden")), patch("subprocess.Popen", side_effect=AssertionError("process forbidden")):
            self.experiment()


if __name__ == "__main__":
    unittest.main()
