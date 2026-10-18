#!/usr/bin/env python3
"""Offline RAG evaluation. Standard library only; no HTTP, database or subprocess access."""

import argparse
from collections import Counter
from copy import deepcopy
import csv
from datetime import datetime
import hashlib
import json
import math
from pathlib import Path
import re
import sys

VERSION = "1.0.0"
POLICY = "human-qrels-v1"
CATEGORIES = {"accord", "note", "mood", "longevity", "projection", "season",
              "exclusion", "compound", "missing_information", "no_match"}
RETRIEVAL = ("precision", "recall", "reciprocal_rank", "ndcg", "hit_rate")
RECOMMENDATION = ("citation_binding", "evidence_integrity", "condition_satisfaction",
                  "selected_relevance", "human_question_relevance", "human_evidence_relevance",
                  "human_uncertainty_appropriate", "selected_rank_mean", "top1_selected", "rank_prefix_selected")
STATUSES = {"succeeded", "failed", "cancelled", "timed_out", "not_captured"}
MAX_BYTES = 20_000_000


class InvalidInput(ValueError):
    """Only fixed error codes cross the CLI boundary."""


def require(condition, code="INVALID_FORMAT"):
    if not condition:
        raise InvalidInput(code)


def fields(value, keys):
    require(isinstance(value, dict) and set(value) == set(keys), "INVALID_FIELDS")


def text(value, maximum=500):
    require(isinstance(value, str) and bool(value.strip()) and len(value) <= maximum, "INVALID_STRING")


def identifier(value):
    require(isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,127}", value) is not None, "INVALID_IDENTIFIER")


def integer(value, low=0, high=2**63 - 1):
    require(type(value) is int and low <= value <= high, "INVALID_INTEGER")


def array(value, maximum=1000):
    require(isinstance(value, list) and len(value) <= maximum, "INVALID_ARRAY")


def digest(value):
    require(isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value) is not None, "INVALID_SHA256")


def timestamp(value):
    text(value, 64)
    try:
        require(datetime.fromisoformat(value.replace("Z", "+00:00")).utcoffset() is not None, "INVALID_TIMESTAMP")
    except ValueError:
        raise InvalidInput("INVALID_TIMESTAMP") from None


def review(value):
    fields(value, ("status", "reviewer", "reviewed_at"))
    require(value["status"] in ("draft", "approved"), "INVALID_REVIEW")
    if value["reviewer"] is not None:
        text(value["reviewer"], 128)
    if value["reviewed_at"] is not None:
        timestamp(value["reviewed_at"])
    if value["status"] == "approved":
        require(value["reviewer"] is not None and value["reviewed_at"] is not None, "MISSING_REVIEW_PROVENANCE")


def canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8")


def sha(value):
    return hashlib.sha256(value.encode("utf-8") if isinstance(value, str) else canonical(value)).hexdigest()


def load_json(path):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, "DUPLICATE_JSON_KEY")
            result[key] = value
        return result

    def reject_constant(_):
        raise InvalidInput("NONFINITE_JSON_NUMBER")

    try:
        with Path(path).open("rb") as stream:
            raw = stream.read(MAX_BYTES + 1)
        require(len(raw) <= MAX_BYTES, "INPUT_TOO_LARGE")
        return json.loads(raw.decode("utf-8-sig"), object_pairs_hook=unique, parse_constant=reject_constant)
    except InvalidInput:
        raise
    except (OSError, UnicodeError, ValueError, RecursionError):
        raise InvalidInput("UNREADABLE_JSON") from None


def validate_dataset(dataset):
    fields(dataset, ("schema_version", "dataset_id", "version", "data_kind", "corpus_snapshot", "queries"))
    integer(dataset["schema_version"], 1, 1)
    identifier(dataset["dataset_id"])
    identifier(dataset["version"])
    require(dataset["data_kind"] in ("real", "synthetic"), "INVALID_DATA_KIND")
    if dataset["corpus_snapshot"] is not None:
        identifier(dataset["corpus_snapshot"])
    array(dataset["queries"])
    require(dataset["queries"], "EMPTY_DATASET")
    seen = set()
    for query in dataset["queries"]:
        fields(query, ("id", "category", "query", "constraints", "judgment_scope", "answerability", "review", "qrels"))
        identifier(query["id"])
        require(query["id"] not in seen, "DUPLICATE_QUERY_ID")
        seen.add(query["id"])
        require(isinstance(query["category"], str) and query["category"] in CATEGORIES, "INVALID_CATEGORY")
        text(query["query"])
        array(query["constraints"], 10)
        for condition in query["constraints"]:
            text(condition)
        require(query["judgment_scope"] in ("unreviewed", "pool", "exhaustive"), "INVALID_JUDGMENT_SCOPE")
        require(query["answerability"] in ("unreviewed", "answerable", "insufficient_information", "no_match"), "INVALID_ANSWERABILITY")
        review(query["review"])
        array(query["qrels"], 100000)
        ids = set()
        for label in query["qrels"]:
            fields(label, ("perfume_id", "grade", "condition_satisfied"))
            integer(label["perfume_id"], 1)
            integer(label["grade"], 0, 3)
            require(label["condition_satisfied"] is None or type(label["condition_satisfied"]) is bool, "INVALID_CONDITION_LABEL")
            require(label["perfume_id"] not in ids, "DUPLICATE_QREL")
            ids.add(label["perfume_id"])
        if query["review"]["status"] == "approved":
            require(dataset["corpus_snapshot"] is not None, "MISSING_CORPUS_SNAPSHOT")
            require(query["judgment_scope"] != "unreviewed", "UNREVIEWED_APPROVAL")
            require(query["judgment_scope"] != "pool" or bool(query["qrels"]), "EMPTY_JUDGED_POOL")
            if query["answerability"] == "answerable":
                require(any(label["grade"] > 0 for label in query["qrels"]), "MISSING_POSITIVE_LABEL")
            if query["answerability"] == "no_match":
                require(query["judgment_scope"] == "exhaustive" and not any(label["grade"] > 0 for label in query["qrels"]), "CONTRADICTORY_NO_MATCH")
    return dataset


def hits_response(response, recommendation=False):
    fields(response, ("answer", "sources", "returnedCount") if recommendation else ("results", "returnedCount"))
    hits = response["sources" if recommendation else "results"]
    array(hits, 20)
    integer(response["returnedCount"], 0, 20)
    require(response["returnedCount"] == len(hits), "RETURNED_COUNT_MISMATCH")
    if recommendation:
        text(response["answer"], 100000)
    for hit in hits:
        fields(hit, ("perfumeId", "name", "brandName", "similarity"))
        integer(hit["perfumeId"], 1)
        text(hit["name"])
        text(hit["brandName"])
        number = hit["similarity"]
        require(type(number) in (int, float) and math.isfinite(number) and -1 <= number <= 1, "INVALID_SIMILARITY")
    return hits


def validate_reference(reference):
    fields(reference, ("review", "query_sha256", "config_sha256", "corpus_snapshot", "prefix_lines", "suffix_lines", "sources"))
    review(reference["review"])
    digest(reference["query_sha256"])
    digest(reference["config_sha256"])
    identifier(reference["corpus_snapshot"])
    for key in ("prefix_lines", "suffix_lines"):
        array(reference[key], 10)
        for line in reference[key]:
            text(line, 2000)
            require("\n" not in line and "\r" not in line, "MULTILINE_REFERENCE")
    array(reference["sources"], 2)
    seen = set()
    for source in reference["sources"]:
        fields(source, ("source", "perfume_id", "source_text", "source_sha256", "identity_line", "facts"))
        integer(source["source"], 1, 20)
        integer(source["perfume_id"], 1)
        require(source["source"] not in seen, "DUPLICATE_REFERENCE_SOURCE")
        seen.add(source["source"])
        digest(source["source_sha256"])
        text(source["source_text"], 65536)
        text(source["identity_line"], 2000)
        require("\n" not in source["identity_line"] and "\r" not in source["identity_line"], "MULTILINE_REFERENCE")
        array(source["facts"], 2)
        require(source["facts"], "EMPTY_REFERENCE_FACTS")
        fact_ids = set()
        for fact in source["facts"]:
            fields(fact, ("fact_id", "text", "kind"))
            integer(fact["fact_id"], 1, 2)
            require(fact["fact_id"] not in fact_ids, "DUPLICATE_REFERENCE_FACT")
            fact_ids.add(fact["fact_id"])
            text(fact["text"], 2000)
            require("\n" not in fact["text"] and "\r" not in fact["text"], "MULTILINE_REFERENCE")
            require(fact["kind"] in ("stored_fact", "uncertainty"), "INVALID_FACT_KIND")
        require(fact_ids == set(range(1, len(source["facts"]) + 1)), "NONCONTIGUOUS_FACT_IDS")


def validate_run(run, dataset):
    fields(run, ("schema_version", "run_id", "data_kind", "dataset_sha256", "config", "records"))
    integer(run["schema_version"], 1, 1)
    identifier(run["run_id"])
    require(run["data_kind"] == dataset["data_kind"], "DATA_KIND_MISMATCH")
    digest(run["dataset_sha256"])
    require(run["dataset_sha256"] == sha(dataset), "DATASET_HASH_MISMATCH")
    config = run["config"]
    fields(config, ("corpus_snapshot", "embedding_snapshot", "embedding_model", "embedding_model_digest",
                    "llm_model", "llm_model_digest", "text_version", "prompt_revision", "formatter_revision",
                    "backend_revision", "ollama_version", "generation_options", "captured_at", "search_top_k", "max_evidence_candidates", "embedding_coverage"))
    for key in ("corpus_snapshot", "embedding_snapshot", "text_version", "prompt_revision", "formatter_revision", "backend_revision"):
        identifier(config[key])
    require(dataset["corpus_snapshot"] is None or config["corpus_snapshot"] == dataset["corpus_snapshot"], "CORPUS_SNAPSHOT_MISMATCH")
    for key in ("embedding_model", "llm_model"):
        text(config[key], 128)
    for key in ("embedding_model_digest", "llm_model_digest"):
        digest(config[key])
    timestamp(config["captured_at"])
    identifier(config["ollama_version"])
    generation = config["generation_options"]
    fields(generation, ("num_ctx", "num_predict", "temperature", "num_thread", "max_input_bytes"))
    integer(generation["num_ctx"], 1, 65536)
    integer(generation["num_predict"], 1, generation["num_ctx"])
    integer(generation["num_thread"], 1, 1024)
    integer(generation["max_input_bytes"], 1, 1000000)
    temperature = generation["temperature"]
    require(type(temperature) in (int, float) and math.isfinite(temperature) and 0 <= temperature <= 2, "INVALID_TEMPERATURE")
    integer(config["search_top_k"], 1, 20)
    integer(config["max_evidence_candidates"], 1, 2)
    fields(config["embedding_coverage"], ("total_perfumes", "embedded_perfumes"))
    total = config["embedding_coverage"]["total_perfumes"]
    integer(total)
    integer(config["embedding_coverage"]["embedded_perfumes"], 0, total)
    array(run["records"])
    queries = {query["id"]: query for query in dataset["queries"]}
    seen = set()
    for record in run["records"]:
        fields(record, ("query_id", "query_sha256", "search", "recommendation"))
        identifier(record["query_id"])
        require(record["query_id"] in queries and record["query_id"] not in seen, "UNKNOWN_OR_DUPLICATE_RUN_QUERY")
        seen.add(record["query_id"])
        digest(record["query_sha256"])
        require(record["query_sha256"] == sha(queries[record["query_id"]]["query"]), "QUERY_HASH_MISMATCH")
        for kind in ("search", "recommendation"):
            capture = record[kind]
            if capture is None:
                continue
            keys = ("status", "response", "error_code") if kind == "search" else ("status", "response", "error_code", "picks", "evidence_reference", "human_review")
            fields(capture, keys)
            require(isinstance(capture["status"], str) and capture["status"] in STATUSES, "INVALID_CAPTURE_STATUS")
            if capture["error_code"] is not None:
                require(isinstance(capture["error_code"], str) and re.fullmatch(r"[A-Z][A-Z0-9_]{0,79}", capture["error_code"]) is not None, "INVALID_ERROR_CODE")
            if capture["status"] != "succeeded":
                require(capture["response"] is None, "FAILED_CAPTURE_HAS_RESPONSE")
                if kind == "recommendation":
                    require(all(capture[key] is None for key in ("picks", "evidence_reference", "human_review")), "FAILED_CAPTURE_HAS_TRACE")
                continue
            require(capture["error_code"] is None, "SUCCESS_HAS_ERROR")
            hits = hits_response(capture["response"], kind == "recommendation")
            require(len(hits) <= config["search_top_k"], "CAPTURE_EXCEEDS_TOP_K")
            if kind == "recommendation":
                if capture["picks"] is not None:
                    array(capture["picks"], 20)
                    for pick in capture["picks"]:
                        fields(pick, ("source", "factId"))
                        integer(pick["source"], 1, 1000)
                        integer(pick["factId"], 1, 1000)
                if capture["evidence_reference"] is not None:
                    validate_reference(capture["evidence_reference"])
                if capture["human_review"] is not None:
                    judgment = capture["human_review"]
                    fields(judgment, ("review", "answer_sha256", "query_sha256", "config_sha256", "question_relevant", "evidence_relevant", "uncertainty_appropriate"))
                    review(judgment["review"])
                    digest(judgment["answer_sha256"])
                    digest(judgment["query_sha256"])
                    digest(judgment["config_sha256"])
                    for key in ("question_relevant", "evidence_relevant", "uncertainty_appropriate"):
                        require(judgment[key] is None or type(judgment[key]) is bool, "INVALID_HUMAN_LABEL")
    return run


def metric(value=None, reason=None):
    return {"value": value, "reason": reason}


def unavailable(names, reason):
    return {name: metric(reason=reason) for name in names}


def retrieval_metrics(query, results, k, captured_k, threshold=1):
    if results is None:
        return unavailable(RETRIEVAL, "SEARCH_NOT_SUCCEEDED")
    if query["review"]["status"] != "approved":
        return unavailable(RETRIEVAL, "NO_APPROVED_JUDGMENTS")
    if k > captured_k:
        return unavailable(RETRIEVAL, "DEPTH_NOT_CAPTURED")
    labels = {label["perfume_id"]: label["grade"] for label in query["qrels"]}
    exhaustive = query["judgment_scope"] == "exhaustive"
    prefix = results[:k]
    if not exhaustive and any(pid not in labels for pid in prefix):
        return unavailable(RETRIEVAL, "UNJUDGED_RESULTS")
    seen = set()
    gains, relevant = [], []
    for rank, pid in enumerate(prefix, 1):
        grade = labels.get(pid, 0)
        if pid in seen:
            grade = 0  # A duplicate consumes its original slot and receives no second credit.
        seen.add(pid)
        gains.append((2**grade - 1) / math.log2(rank + 1))
        relevant.append(grade >= threshold)
    hits = sum(relevant)
    positives = sum(grade >= threshold for grade in labels.values())
    dcg = sum(gains)
    ideal = sorted(labels.values(), reverse=True)[:k]
    idcg = sum((2**grade - 1) / math.log2(rank + 1) for rank, grade in enumerate(ideal, 1))
    first = next((rank for rank, hit in enumerate(relevant, 1) if hit), None)
    values = {"precision": metric(hits / k), "hit_rate": metric(float(hits > 0)),
              "reciprocal_rank": metric(1 / first if first else 0.0),
              "recall": metric(hits / positives) if exhaustive and positives else metric(reason="NO_RELEVANT_LABELS" if exhaustive else "NONEXHAUSTIVE_JUDGMENTS"),
              "ndcg": metric(dcg / idcg) if exhaustive and idcg else metric(reason="NO_GAIN_LABELS" if exhaustive else "NONEXHAUSTIVE_JUDGMENTS")}
    if exhaustive and not positives:
        values["reciprocal_rank"] = metric(reason="NO_RELEVANT_LABELS")
    return values


def evidence_integrity(query, capture, config, selected):
    reference, picks = capture["evidence_reference"], capture["picks"]
    if reference is None or picks is None:
        return metric(reason="EVIDENCE_TRACE_NOT_CAPTURED"), []
    if reference["review"]["status"] != "approved":
        return metric(reason="REFERENCE_NOT_APPROVED"), []
    issues = []
    if reference["corpus_snapshot"] != config["corpus_snapshot"] or reference["query_sha256"] != sha(query["query"]) or reference["config_sha256"] != sha(config):
        issues.append("REFERENCE_CONTEXT_MISMATCH")
    hits = capture["response"]["sources"]
    sources = {source["source"]: source for source in reference["sources"]}
    if len(sources) > config["max_evidence_candidates"]:
        issues.append("REFERENCE_CANDIDATE_LIMIT_MISMATCH")
    for number, source in sources.items():
        if sha(source["source_text"]) != source["source_sha256"]:
            issues.append("SOURCE_HASH_MISMATCH")
        if number > len(hits) or source["perfume_id"] != hits[number - 1]["perfumeId"]:
            issues.append("REFERENCE_SOURCE_MISMATCH")
    numbers = [pick["source"] for pick in picks]
    if not numbers or len(numbers) > config["max_evidence_candidates"] or numbers != sorted(set(numbers)):
        issues.append("INVALID_PICK_ORDER_OR_COUNT")
    if numbers != selected:
        issues.append("TRACE_CITATION_MISMATCH")
    lines = list(reference["prefix_lines"])
    for pick in picks:
        source = sources.get(pick["source"])
        fact = next((fact for fact in source["facts"] if fact["fact_id"] == pick["factId"]), None) if source else None
        if fact is None:
            issues.append("UNKNOWN_SOURCE_FACT_PAIR")
        else:
            lines.extend((source["identity_line"], fact["text"]))
    lines.extend(reference["suffix_lines"])
    if "\n".join(lines) != capture["response"]["answer"]:
        issues.append("RENDERED_ANSWER_MISMATCH")
    return metric(float(not issues)), sorted(set(issues))


def recommendation_metrics(query, capture, config, search_ids, threshold=1):
    status = "not_captured" if capture is None else capture["status"]
    result = {"status": status, "error_code": capture["error_code"] if capture else None,
              "metrics": unavailable(RECOMMENDATION, "RECOMMENDATION_NOT_SUCCEEDED"),
              "selected_perfume_ids": [], "selected_source_ranks": [], "issues": [], "grounding_scope": "not_evaluated",
              "exposed_candidate_count": None,
              "rank_choice_constrained_by_cap": config["max_evidence_candidates"] == 1}
    if status != "succeeded":
        return result
    response = capture["response"]
    hits = response["sources"]
    ids = [hit["perfumeId"] for hit in hits]
    citations = re.findall(r"\[([0-9]+)\]", response["answer"])
    numbers = list(dict.fromkeys(int(number) if len(number) <= 4 else -1 for number in citations))
    valid = all(1 <= number <= len(ids) for number in numbers) and len(ids) == len(set(ids))
    valid = valid and numbers == sorted(numbers) and len(numbers) <= config["max_evidence_candidates"]
    selected = [ids[number - 1] for number in numbers if 1 <= number <= len(ids)]
    values = result["metrics"]
    values["citation_binding"] = metric(float(valid)) if numbers or not ids else metric(reason="NO_SELECTED_CITATIONS")
    if not valid:
        result["issues"].append("INVALID_CITATION_BINDING")
    if search_ids is not None and ids != search_ids:
        result["issues"].append("SEARCH_RECOMMENDATION_RANKING_DIFFER")
    result["selected_perfume_ids"], result["selected_source_ranks"] = selected, numbers
    if valid and numbers:
        values["selected_rank_mean"] = metric(sum(numbers) / len(numbers))
        values["top1_selected"] = metric(float(1 in numbers))
        values["rank_prefix_selected"] = metric(float(numbers == list(range(1, len(numbers) + 1))))
        labels = {label["perfume_id"]: label for label in query["qrels"]}
        approved = query["review"]["status"] == "approved"
        if approved and all(pid in labels and labels[pid]["condition_satisfied"] is not None for pid in selected):
            values["condition_satisfaction"] = metric(sum(labels[pid]["condition_satisfied"] for pid in selected) / len(selected))
        else:
            values["condition_satisfaction"] = metric(reason="MISSING_CONDITION_LABELS")
        if approved and (query["judgment_scope"] == "exhaustive" or all(pid in labels for pid in selected)):
            values["selected_relevance"] = metric(sum(labels.get(pid, {"grade": 0})["grade"] >= threshold for pid in selected) / len(selected))
        else:
            values["selected_relevance"] = metric(reason="MISSING_RELEVANCE_LABELS")
    else:
        for name in ("selected_rank_mean", "top1_selected", "rank_prefix_selected", "condition_satisfaction", "selected_relevance"):
            values[name] = metric(reason="NO_VALID_SELECTED_SOURCES")
    values["evidence_integrity"], issues = evidence_integrity(query, capture, config, numbers)
    result["issues"].extend(issues)
    if values["evidence_integrity"]["value"] is not None:
        result["grounding_scope"] = "human_reviewed_request_snapshot"
    if values["evidence_integrity"]["value"] == 1:
        result["exposed_candidate_count"] = len(capture["evidence_reference"]["sources"])
    human = capture["human_review"]
    reason = "NO_APPROVED_RESPONSE_REVIEW"
    approved_human = human is not None and human["review"]["status"] == "approved"
    if approved_human and (human["answer_sha256"] != sha(response["answer"]) or human["query_sha256"] != sha(query["query"]) or human["config_sha256"] != sha(config)):
        reason, approved_human = "STALE_RESPONSE_REVIEW", False
    for output, key in (("human_question_relevance", "question_relevant"), ("human_evidence_relevance", "evidence_relevant"), ("human_uncertainty_appropriate", "uncertainty_appropriate")):
        value = human[key] if approved_human else None
        values[output] = metric(float(value)) if value is not None else metric(reason=reason if not approved_human else "HUMAN_ITEM_UNLABELED")
    return result


def average(values, total):
    available = [value["value"] for value in values if value["value"] is not None]
    return {"mean": sum(available) / len(available) if available else None,
            "eligible_questions": len(available), "ineligible_questions": total - len(available)}


def summarize(rows, ks):
    return {"questions": len(rows), "questions_with_approved_judgments": sum(row["judgments_approved"] for row in rows),
            "questions_with_qrels": sum(row["has_qrels"] and row["judgments_approved"] for row in rows),
            "retrieval_unevaluable_questions": sum(all(value["value"] is None for metrics in row["retrieval"].values() for value in metrics.values()) for row in rows),
            "unevaluable_reasons": dict(sorted(Counter(reason for row in rows for reason in {
                value["reason"] for metrics in row["retrieval"].values() for value in metrics.values() if value["reason"] is not None}).items())),
            "evidence_integrity_unevaluable_questions": sum(row["recommendation"]["metrics"]["evidence_integrity"]["value"] is None for row in rows),
            "recommendation_unevaluable_reasons": dict(sorted(Counter(reason for row in rows for reason in {
                value["reason"] for value in row["recommendation"]["metrics"].values() if value["reason"] is not None}).items())),
            "search_status_counts": dict(sorted(Counter(row["search_status"] for row in rows).items())),
            "recommendation_status_counts": dict(sorted(Counter(row["recommendation"]["status"] for row in rows).items())),
            "retrieval": {str(k): {name: average([row["retrieval"][str(k)][name] for row in rows], len(rows)) for name in RETRIEVAL} for k in ks},
            "recommendation": {name: average([row["recommendation"]["metrics"][name] for row in rows], len(rows)) for name in RECOMMENDATION}}


def evaluate(dataset, run, ks=(5, 10, 20), threshold=1):
    validate_dataset(dataset)
    validate_run(run, dataset)
    require(isinstance(ks, (tuple, list)) and bool(ks), "INVALID_K_VALUES")
    for k in ks:
        integer(k, 1, 20)
    ks = sorted(set(ks))
    integer(threshold, 1, 3)
    records = {record["query_id"]: record for record in run["records"]}
    rows = []
    for query in sorted(dataset["queries"], key=lambda item: item["id"]):
        record = records.get(query["id"], {})
        search = record.get("search")
        status = search["status"] if search else "not_captured"
        ids = [hit["perfumeId"] for hit in search["response"]["results"]] if status == "succeeded" else None
        ranking = {str(k): retrieval_metrics(query, ids, k, run["config"]["search_top_k"], threshold) for k in ks}
        recommendation = recommendation_metrics(query, record.get("recommendation"), run["config"], ids, threshold)
        flags = set(recommendation["issues"])
        if ids is not None and len(ids) != len(set(ids)):
            flags.add("DUPLICATE_SEARCH_RESULTS")
        for metrics in ranking.values():
            if metrics["precision"]["value"] is not None and metrics["precision"]["value"] < 0.5:
                flags.add("LOW_SEARCH_PRECISION")
            flags.update(value["reason"] for value in metrics.values() if value["reason"] is not None)
        for name in ("evidence_integrity", "condition_satisfaction", "selected_relevance", "human_question_relevance", "human_evidence_relevance", "human_uncertainty_appropriate"):
            value = recommendation["metrics"][name]
            if value["value"] is not None and value["value"] < 1:
                flags.add("REVIEW_" + name.upper())
            if value["reason"] is not None:
                flags.add(value["reason"])
        if status != "succeeded":
            flags.add("SEARCH_" + status.upper())
        if recommendation["status"] != "succeeded":
            flags.add("RECOMMENDATION_" + recommendation["status"].upper())
        rows.append({"query_id": query["id"], "category": query["category"], "answerability": query["answerability"],
                     "judgments_approved": query["review"]["status"] == "approved", "has_qrels": bool(query["qrels"]),
                     "search_status": status, "search_error_code": search["error_code"] if search else None,
                     "search_duplicate_count": len(ids) - len(set(ids)) if ids is not None else 0,
                     "retrieval": ranking, "recommendation": recommendation, "review_flags": sorted(flags)})
    return {"schema_version": 1, "metadata": {"tool_version": VERSION, "metric_policy": POLICY,
            "tool_sha256": sha(Path(__file__).read_text(encoding="utf-8")), "dataset_id": dataset["dataset_id"],
            "dataset_version": dataset["version"], "dataset_sha256": sha(dataset), "data_kind": dataset["data_kind"],
            "run_id": run["run_id"], "run_sha256": sha(run), "k_values": ks, "relevant_grade_threshold": threshold,
            "config": deepcopy(run["config"])}, "summary": summarize(rows, ks),
            "by_category": {category: summarize([row for row in rows if row["category"] == category], ks) for category in sorted({row["category"] for row in rows})},
            "by_answerability": {answerability: summarize([row for row in rows if row["answerability"] == answerability], ks) for answerability in sorted({row["answerability"] for row in rows})},
            "cases_needing_review": [{"query_id": row["query_id"], "category": row["category"], "reasons": row["review_flags"]} for row in rows if row["review_flags"]],
            "queries": rows}


def compare_reports(before, after):
    try:
        for report in (before, after):
            require(report["schema_version"] == 1, "INVALID_REPORT")
            for row in report["queries"]:
                identifier(row["query_id"])
                for values in list(row["retrieval"].values()) + [row["recommendation"]["metrics"]]:
                    for value in values.values():
                        require(value["value"] is None or (type(value["value"]) in (int, float) and math.isfinite(value["value"]) and value["value"] >= 0), "INVALID_REPORT_METRIC")
        left, right = before["metadata"], after["metadata"]
        for key in ("dataset_sha256", "data_kind", "metric_policy", "tool_version", "tool_sha256", "k_values", "relevant_grade_threshold"):
            require(left[key] == right[key], "INCOMPARABLE_REPORTS")
        require(left["config"]["corpus_snapshot"] == right["config"]["corpus_snapshot"], "INCOMPARABLE_CORPUS")
        old = {row["query_id"]: row for row in before["queries"]}
        new = {row["query_id"]: row for row in after["queries"]}
        require(len(old) == len(before["queries"]) and len(new) == len(after["queries"]) and old.keys() == new.keys(), "INCOMPARABLE_QUERY_SETS")
        def paired(extract):
            values = [(extract(old[qid])["value"], extract(new[qid])["value"]) for qid in sorted(old)]
            both = [(a, b) for a, b in values if a is not None and b is not None]
            return {"before_mean": sum(a for a, _ in both) / len(both) if both else None,
                    "after_mean": sum(b for _, b in both) / len(both) if both else None,
                    "mean_score_delta": sum(b - a for a, b in both) / len(both) if both else None,
                    "paired_questions": len(both), "excluded_questions": len(values) - len(both)}
        return {"schema_version": 1, "comparison_scope": "paired_queries_only_no_relative_improvement_claim",
                "before": deepcopy(left), "after": deepcopy(right),
                "before_coverage": before["summary"]["search_status_counts"], "after_coverage": after["summary"]["search_status_counts"],
                "before_recommendation_coverage": before["summary"]["recommendation_status_counts"],
                "after_recommendation_coverage": after["summary"]["recommendation_status_counts"],
                "retrieval": {str(k): {name: paired(lambda row, k=str(k), name=name: row["retrieval"][k][name]) for name in RETRIEVAL} for k in left["k_values"]},
                "recommendation": {name: paired(lambda row, name=name: row["recommendation"]["metrics"][name]) for name in RECOMMENDATION}}
    except (KeyError, TypeError, AttributeError):
        raise InvalidInput("INVALID_REPORT") from None


def csv_cases(report):
    names = ["query_id", "category", "k", "search_status", "recommendation_status", "search_duplicate_count"]
    names += [part for name in RETRIEVAL + RECOMMENDATION for part in (name, name + "_reason")]
    names += ["review_flags"]
    rows = []
    for query in report["queries"]:
        for k, values in query["retrieval"].items():
            row = {key: query[key] for key in ("query_id", "category", "search_status", "search_duplicate_count")}
            row.update(k=k, recommendation_status=query["recommendation"]["status"], review_flags=";".join(query["review_flags"]))
            for name, value in {**values, **query["recommendation"]["metrics"]}.items():
                row[name], row[name + "_reason"] = value["value"], value["reason"]
            rows.append(row)
    return names, rows


def write_output(directory, report, comparison=False):
    destination = Path(directory)
    # Never replace existing data. Only an explicitly named, new report directory is created.
    destination.mkdir(parents=True, exist_ok=False)
    json_name = "comparison.json" if comparison else "report.json"
    with (destination / json_name).open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(report, stream, ensure_ascii=False, sort_keys=True, indent=2, allow_nan=False)
        stream.write("\n")
    if comparison:
        names = ("section", "k", "metric", "before_mean", "after_mean", "mean_score_delta", "paired_questions", "excluded_questions")
        rows = [{"section": "retrieval", "k": k, "metric": name, **value} for k, metrics in report["retrieval"].items() for name, value in metrics.items()]
        rows += [{"section": "recommendation", "k": "", "metric": name, **value} for name, value in report["recommendation"].items()]
    else:
        names, rows = csv_cases(report)
    with (destination / ("comparison.csv" if comparison else "cases.csv")).open("x", encoding="utf-8-sig", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=names)
        writer.writeheader()
        writer.writerows(rows)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    validate = sub.add_parser("validate-dataset", help="Validate labels and templates without collecting data")
    validate.add_argument("--dataset", required=True)
    validate.add_argument("--fingerprints", action="store_true", help="Print only canonical dataset and query hashes")
    evaluate_parser = sub.add_parser("evaluate", help="Evaluate previously exported local JSON only")
    evaluate_parser.add_argument("--dataset", required=True)
    evaluate_parser.add_argument("--run", required=True)
    evaluate_parser.add_argument("--ks", default="5,10,20")
    evaluate_parser.add_argument("--relevant-grade", type=int, default=1)
    evaluate_parser.add_argument("--output-dir", required=True)
    compare = sub.add_parser("compare", help="Compare two reports using paired eligible queries")
    compare.add_argument("--before", required=True)
    compare.add_argument("--after", required=True)
    compare.add_argument("--output-dir", required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == "validate-dataset":
            dataset = validate_dataset(load_json(args.dataset))
            if args.fingerprints:
                print(json.dumps({"dataset_sha256": sha(dataset), "queries": {query["id"]: sha(query["query"]) for query in dataset["queries"]}}, sort_keys=True))
            else:
                print("DATASET_VALID")
        elif args.command == "evaluate":
            require(re.fullmatch(r"[0-9]+(?:,[0-9]+)*", args.ks) is not None, "INVALID_K_VALUES")
            report = evaluate(load_json(args.dataset), load_json(args.run), [int(k) for k in args.ks.split(",")], args.relevant_grade)
            write_output(args.output_dir, report)
            print("EVALUATION_WRITTEN")
        else:
            write_output(args.output_dir, compare_reports(load_json(args.before), load_json(args.after)), comparison=True)
            print("COMPARISON_WRITTEN")
        return 0
    except InvalidInput as failure:
        print("EVALUATION_REJECTED: " + str(failure), file=sys.stderr)
        return 2
    except FileExistsError:
        print("EVALUATION_REJECTED: OUTPUT_EXISTS", file=sys.stderr)
        return 2
    except (OSError, ValueError, RecursionError):
        print("EVALUATION_REJECTED: LOCAL_IO_OR_FORMAT_ERROR", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
