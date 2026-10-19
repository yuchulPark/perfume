#!/usr/bin/env python3
"""Disabled-by-default offline condition analysis/reranking; no API, DB or LLM client."""

import argparse
from collections import Counter
from copy import deepcopy
import csv
import importlib.util
import json
from pathlib import Path
import re
import sys
import unicodedata

_SPEC = importlib.util.spec_from_file_location("archive_evaluation", Path(__file__).with_name("rag-evaluation.py"))
evaluation = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(evaluation)

VERSION = "experimental-1"
RULE_VERSION = "explicit-ko-en-1"
SORT_POLICY = "violation-match-unknown-similarity-stable-1"
# Literal bilingual spellings only. No scent-family inference or provider category inference.
LEXICON = (
    ("note", "vanilla", ("바닐라", "vanilla")),
    ("note", "rose", ("장미", "로즈", "rose", "roses")),
    ("note", "bergamot", ("베르가모트", "bergamot")),
    ("note", "iris", ("아이리스", "iris")),
    ("note", "lavender", ("라벤더", "lavender")),
    ("note", "jasmine", ("재스민", "jasmine")),
    ("accord", "citrus", ("시트러스", "citrus")),
    ("accord", "floral", ("플로럴", "floral")),
    ("accord", "woody", ("우디", "woody")),
    ("season", "summer", ("여름", "summer")),
    ("season", "winter", ("겨울", "winter")),
    ("season", "spring", ("봄", "spring")),
    ("season", "autumn", ("가을", "autumn", "fall")),
)
METRIC_PATTERNS = (
    ("longevity", "high", r"(?:지속력(?:이|은|는|가)?\s*(?:좋은|높은|긴|좋고|높고)|오래\s*(?:가는|남는|지속되는)|long[- ]lasting|(?:high|long)\s+longevity|longevity\s+(?:high|long))"),
    ("longevity", "low", r"(?:지속력(?:이|은|는|가)?\s*(?:낮은|짧은|낮고|짧고)|short[- ]lasting|(?:low|short)\s+longevity|longevity\s+(?:low|short))"),
    ("projection", "high", r"(?:(?:발향력|발향)(?:이|은|는|가)?\s*(?:강한|높은|강하고)|(?:high|strong)\s+projection|projection\s+(?:high|strong))"),
    ("projection", "low", r"(?:(?:발향력|발향)(?:이|은|는|가)?\s*(?:약한|낮은|약하고)|(?:low|weak)\s+projection|projection\s+(?:low|weak))"),
)
AMBIGUOUS = re.compile(r"싫지\s*않|싫어하지\s*않|안\s*싫|없지\s*않|아니어도|상관\s*없|괜찮|제외하지|빼지|없(?:는|이).{0,20}(?:싫|원하지)|not\s+(?:only|required|necessary|without|exclude|avoid|bad|disliked|unwanted|excluded|hated)|not\s+\w+[- ]free\b|\bnon[- ]|\b(?:okay|fine|optional)\b|don['’]?t\s+(?:dislike|exclude|avoid|hate)|do\s+not\s+(?:dislike|exclude|avoid|hate)|isn['’]?t\s+bad", re.I)
NEGATIVE = re.compile(r"싫(?:어|다|은|음|고)|안\s*좋아|(?:없(?:는|이)|빼고|제외|아닌)|피하고\s*싶|원하지\s*않|don['’]?t\s+want|(?:^|\s)(?:without|no|exclude|excluding|avoid|hate|not)(?:\s|$)|(?:^|[\s-])free\b", re.I)
PREFIX_CUE = re.compile(r"\b(?:(?:don['’]?t|do\s+not)\s+want|with|include|including|want|i\s+like|i\s+love|without|no|exclude|excluding|avoid|hate|not|싫어하는|제외할|피하고\s*싶은|원하지\s*않는|좋아하는|원하는)\s*$", re.I)
POSITIVE = re.compile(r"(?:향수|향을|향이|향조|어코드|노트|계열)|좋(?:아|은|다|고)|포함|있(?:는|고)|원해|찾|추천|(?:^|\s)(?:with|include|including|want|i\s+like|i\s+love|perfume|fragrance|scent)(?:\s|$)", re.I)
COORDINATOR = re.compile(r"\s*(?:(?:와|과|랑|및|하고|그리고|and|&)\s*|,\s*)+", re.I)
CLAUSE_BREAK = re.compile(r"하지만|그런데|인데|지만|[.!?;]|\bbut\b", re.I)
LOGICAL_UNKNOWN = re.compile(r"또는|거나|아니면|\b(?:or|unless|if)\b", re.I)
# Only grammar/request boilerplate is discarded from coverage, never unknown scent/mood words.
BOILERPLATE = re.compile(
    r"추천해\s*줘|추천해줘|추천해|추천|알려\s*줘|알려줘|찾고\s*있어|찾아\s*줘|찾아줘|것을|것|중에서|중|"
    r"사용하기|사용할|쓰기에|쓰기|쓰는|어울리는|적합한|향수를|향수|향조|어코드|계열|노트|향을|향이|향|"
    r"포함한|포함된|포함|좋아|좋은|좋고|좋다|싫어|싫다|싫은|싫음|없는|없이|아닌|빼고|제외|원해|원하는|있는|있고|"
    r"그리고|하지만|그런데|인데|지만|및|하고|한|은|는|이|가|을|를|도|와|과|랑|에|의|인|"
    r"\b(?:i|a|the|some|please|recommend|me|for|to|use|wear|in|perfumes?|fragrances?|scent|with|without|no|not|"
    r"include|including|exclude|excluding|avoid|hate|want|like|love|and|but|suitable|good)\b", re.I)


def normalized(value):
    return " ".join(unicodedata.normalize("NFKC", value).casefold().split())


def alias_pattern(alias):
    if re.search("[가-힣]", alias):
        return re.compile(r"(?<![a-z가-힣])" + re.escape(alias)
                          + r"(?=$|[^a-z가-힣]|은|는|이|가|을|를|도|와|과|랑|향|노트|어코드|계열|없|한|에|용)", re.I)
    return re.compile(r"(?<![a-z0-9_])" + re.escape(alias) + r"(?![a-z0-9_])", re.I)


class QueryConditionAnalyzer:
    """Small explicit grammar. Unconsumed content remains an unknown condition."""

    def analyze(self, query):
        evaluation.text(query)
        query = normalized(query)
        conditions = []
        residual = list(query)
        def cover(start, end):
            residual[start:end] = " " * (end - start)
        def add(kind, value, operator, reason):
            item = {"kind": kind, "value": value, "operator": operator, "reason": reason}
            if item not in conditions:
                conditions.append(item)
        position = 0
        for split in list(CLAUSE_BREAK.finditer(query)) + [None]:
            end = split.start() if split else len(query)
            clause = query[position:end]
            mentions = []
            metric_spans = []
            for kind, value, pattern in METRIC_PATTERNS:
                for match in re.finditer(r"(?<![a-z가-힣])" + pattern + r"(?![a-z])", clause):
                    tail = clause[match.end():]
                    ambiguous = bool(AMBIGUOUS.search(clause) or re.search(r"싫|않|not\b|avoid\b", tail)
                                     or re.search(r"\b(?:not|no|without|avoid)\s*$", clause[:match.start()]))
                    add(kind, value, "unknown" if ambiguous else "require", "AMBIGUOUS_METRIC_POLARITY" if ambiguous else "EXPLICIT_METRIC_PREFERENCE")
                    cover(position + match.start(), position + match.end())
                    metric_spans.append((match.start(), match.end()))
            for kind, value, aliases in LEXICON:
                for alias in aliases:
                    for match in alias_pattern(alias).finditer(clause):
                        mentions.append((match.start(), match.end(), kind, value))
            mentions.sort(key=lambda item: (item[0], -(item[1] - item[0])))
            distinct = []
            for mention in mentions:
                if not distinct or mention[0] >= distinct[-1][1]:
                    distinct.append(mention)
            groups = []
            for mention in distinct:
                if groups and COORDINATOR.fullmatch(clause[groups[-1][-1][1]:mention[0]]):
                    groups[-1].append(mention)
                else:
                    groups.append([mention])
            for index, group in enumerate(groups):
                left = groups[index - 1][-1][1] if index else 0
                right = groups[index + 1][0][0] if index + 1 < len(groups) else len(clause)
                prefix_text = clause[left:group[0][0]]
                prefix = PREFIX_CUE.search(prefix_text)
                suffix = clause[group[-1][1]:right]
                if index + 1 < len(groups):
                    suffix = PREFIX_CUE.sub(" ", suffix)
                context = (prefix.group() if prefix else "") + " " + suffix
                # A separate parsed metric phrase cannot supply polarity to a scent term.
                for start, finish in metric_spans:
                    context = context.replace(clause[start:finish], " ")
                if AMBIGUOUS.search(clause):
                    operator, reason = "unknown", "AMBIGUOUS_POLARITY"
                elif NEGATIVE.search(context):
                    operator, reason = "exclude", "EXPLICIT_EXCLUSION"
                elif POSITIVE.search(context):
                    operator, reason = "include", "EXPLICIT_INCLUSION"
                else:
                    operator, reason = "unknown", "NO_EXPLICIT_POLARITY"
                for start, finish, kind, value in group:
                    # Generic 'citrus note' is not silently converted to a citrus accord.
                    actual = operator
                    if kind == "season" and operator == "unknown" and re.match(r"\s*용", clause[finish:]):
                        actual, reason = "include", "EXPLICIT_SEASON_USE"
                    if kind == "accord" and re.match(r"\s*(?:노트|notes?\b)", clause[finish:]):
                        actual, reason = "unknown", "AMBIGUOUS_NOTE_ACCORD_KIND"
                    add(kind, value, actual, reason)
                    cover(position + start, position + finish)
            position = split.end() if split else len(query)
        if LOGICAL_UNKNOWN.search(query):
            # Unsupported OR/conditional logic must not become conjunctive mandatory filters.
            for condition in conditions:
                condition["operator"], condition["reason"] = "unknown", "UNSUPPORTED_LOGIC"
        for condition in conditions:
            same = [item for item in conditions if item["kind"] == condition["kind"] and item["value"] == condition["value"]]
            if {item["operator"] for item in same} >= {"include", "exclude"}:
                for item in same:
                    item["operator"], item["reason"] = "unknown", "CONTRADICTORY_REQUIREMENTS"
        remainder = BOILERPLATE.sub(" ", "".join(residual))
        if re.search(r"[a-z가-힣0-9]", remainder):
            add("unknown", None, "unknown", "UNPARSED_QUERY_CONTENT")
        if not conditions:
            add("unknown", None, "unknown", "NO_SUPPORTED_CONDITION")
        unique = {}
        for item in conditions:
            unique.setdefault((item["kind"], item["value"], item["operator"]), item)
        conditions = list(unique.values())
        return [{"id": index + 1, **item} for index, item in enumerate(conditions)]


def canonical_term(kind, value):
    value = normalized(value)
    return next((canonical for item_kind, canonical, aliases in LEXICON
                 if item_kind == kind and value in {normalized(alias) for alias in aliases}), None)


def validate_evidence(evidence, dataset, run):
    evaluation.fields(evidence, ("schema_version", "data_kind", "run_sha256", "config_sha256", "corpus_snapshot",
                                 "embedding_snapshot", "evidence_basis", "review", "metric_contracts", "candidates"))
    evaluation.integer(evidence["schema_version"], 1, 1)
    evaluation.require(evidence["data_kind"] == dataset["data_kind"], "EVIDENCE_DATA_KIND_MISMATCH")
    evaluation.require(evidence["run_sha256"] == evaluation.sha(run), "EVIDENCE_RUN_MISMATCH")
    evaluation.require(evidence["config_sha256"] == evaluation.sha(run["config"]), "EVIDENCE_CONFIG_MISMATCH")
    for key in ("corpus_snapshot", "embedding_snapshot"):
        evaluation.require(evidence[key] == run["config"][key], "EVIDENCE_SNAPSHOT_MISMATCH")
    evaluation.require(evidence["evidence_basis"] == "catalog_snapshot", "INVALID_EVIDENCE_BASIS")
    evaluation.review(evidence["review"])
    evaluation.array(evidence["metric_contracts"], 100)
    ids = set()
    for contract in evidence["metric_contracts"]:
        evaluation.fields(contract, ("id", "kind", "value", "metric_key", "scale", "categories", "accepted_reliability", "allowed_derived_from", "review", "semantic_reference"))
        evaluation.identifier(contract["id"])
        evaluation.require(contract["id"] not in ids, "DUPLICATE_CONTRACT")
        ids.add(contract["id"])
        evaluation.require(contract["kind"] in ("longevity", "projection", "season"), "INVALID_CONTRACT_KIND")
        evaluation.require(contract["value"] in (("summer", "winter", "spring", "autumn") if contract["kind"] == "season" else (None,)), "INVALID_CONTRACT_VALUE")
        for key in ("metric_key", "scale", "semantic_reference"):
            evaluation.text(contract[key], 255)
        evaluation.review(contract["review"])
        evaluation.fields(contract["categories"], ("high", "low") if contract["kind"] != "season" else ("suitable", "unsuitable"))
        labels = []
        for categories in contract["categories"].values():
            evaluation.array(categories, 30)
            for category in categories:
                evaluation.text(category, 128)
                labels.append(category)
        evaluation.require(len(labels) == len(set(labels)), "CONFLICTING_CONTRACT_CATEGORIES")
        for key in ("accepted_reliability", "allowed_derived_from"):
            evaluation.array(contract[key], 30)
            for value in contract[key]:
                if value is not None:
                    evaluation.text(value, 128)
            if key == "accepted_reliability":
                evaluation.require(all(value is not None for value in contract[key]), "NULL_ACCEPTED_RELIABILITY")
    evaluation.array(evidence["candidates"], 100000)
    ids = set()
    for candidate in evidence["candidates"]:
        evaluation.fields(candidate, ("perfume_id", "notes", "accords", "metrics", "snapshot_sha256"))
        evaluation.integer(candidate["perfume_id"], 1)
        evaluation.require(candidate["perfume_id"] not in ids, "DUPLICATE_CANDIDATE_EVIDENCE")
        ids.add(candidate["perfume_id"])
        evaluation.digest(candidate["snapshot_sha256"])
        content = {key: candidate[key] for key in ("perfume_id", "notes", "accords", "metrics")}
        evaluation.require(candidate["snapshot_sha256"] == evaluation.sha(content), "CANDIDATE_HASH_MISMATCH")
        for key in ("notes", "accords"):
            if candidate[key] is not None:
                evaluation.array(candidate[key], 1000)
                for value in candidate[key]:
                    evaluation.text(value, 255)
        evaluation.array(candidate["metrics"], 1000)
        seen_keys = set()
        for metric in candidate["metrics"]:
            evaluation.fields(metric, ("key", "score", "category", "records", "reliability", "scale", "derived_from"))
            evaluation.text(metric["key"], 255)
            evaluation.require(metric["key"] not in seen_keys, "DUPLICATE_METRIC_KEY")
            seen_keys.add(metric["key"])
            for key in ("category", "reliability", "scale", "derived_from"):
                if metric[key] is not None:
                    evaluation.text(metric[key], 255)
            if metric["records"] is not None:
                evaluation.integer(metric["records"])
            if metric["score"] is not None:
                evaluation.require(type(metric["score"]) in (int, float), "INVALID_METRIC_SCORE")
                # Reject NaN/Infinity without defining any numeric interpretation or threshold.
                evaluation.require(abs(metric["score"]) <= sys.float_info.max, "NONFINITE_METRIC_SCORE")
    return evidence


class CandidateSuitabilityAssessor:
    def __init__(self, evidence):
        self.evidence = evidence
        self.candidates = {row["perfume_id"]: row for row in evidence["candidates"]}

    def condition(self, condition, candidate):
        def result(status, reason, support=None):
            return {"condition_id": condition["id"], "status": status, "reason": reason, "support": support or []}
        if condition["operator"] == "unknown":
            return result("UNKNOWN", condition["reason"])
        if self.evidence["review"]["status"] != "approved":
            return result("UNKNOWN", "EVIDENCE_NOT_APPROVED")
        if candidate is None:
            return result("UNKNOWN", "CANDIDATE_EVIDENCE_MISSING")
        kind, value, operator = condition["kind"], condition["value"], condition["operator"]
        if kind in ("note", "accord"):
            values = candidate["notes" if kind == "note" else "accords"]
            if values is None:
                return result("UNKNOWN", "SOURCE_FIELD_MISSING")
            found = any(canonical_term(kind, item) == value for item in values)
            if found:
                return result("MISMATCH" if operator == "exclude" else "MATCH", "EXCLUDED_TERM_PRESENT" if operator == "exclude" else "REQUIRED_TERM_PRESENT")
            return result("UNKNOWN", "ABSENCE_NOT_PROVEN")
        contracts = [item for item in self.evidence["metric_contracts"] if item["kind"] == kind and item["value"] == (value if kind == "season" else None)]
        states, support, reasons = set(), [], []
        for contract in contracts:
            if contract["review"]["status"] != "approved":
                reasons.append("METRIC_CONTRACT_NOT_APPROVED")
                continue
            rows = [row for row in candidate["metrics"] if row["key"] == contract["metric_key"]]
            for row in rows:
                if row["scale"] != contract["scale"]:
                    reasons.append("UNKNOWN_METRIC_SCALE")
                    continue
                if row["records"] is None or row["records"] == 0:
                    reasons.append("NO_RECORDED_OBSERVATIONS")
                    continue
                if row["reliability"] not in contract["accepted_reliability"] or row["derived_from"] not in contract["allowed_derived_from"]:
                    reasons.append("UNAPPROVED_METRIC_PROVENANCE")
                    continue
                classification = next((key for key, categories in contract["categories"].items() if row["category"] in categories), None)
                if classification is not None:
                    states.add(classification)
                    support.append({"contract_id": contract["id"], "metric_key": row["key"], "records": row["records"], "reliability": row["reliability"]})
        if len(states) > 1:
            return result("UNKNOWN", "CONFLICTING_METRIC_EVIDENCE", support)
        if not states:
            return result("UNKNOWN", sorted(set(reasons))[0] if reasons else "NO_INTERPRETABLE_METRIC")
        classification = next(iter(states))
        wanted = ("unsuitable" if operator == "exclude" else "suitable") if kind == "season" else value
        return result("MATCH" if classification == wanted else "MISMATCH", "REVIEWED_CATEGORY_CONTRACT", support)

    def assess(self, conditions, perfume_id):
        outcomes = [self.condition(condition, self.candidates.get(perfume_id)) for condition in conditions]
        counts = Counter(item["status"] for item in outcomes)
        overall = "MISMATCH" if counts["MISMATCH"] else "UNKNOWN" if counts["UNKNOWN"] else "MATCH"
        return {"perfume_id": perfume_id, "status": overall, "match_count": counts["MATCH"],
                "mismatch_count": counts["MISMATCH"], "unknown_count": counts["UNKNOWN"], "conditions": outcomes}


class CandidateReranker:
    def rerank(self, hits, assessments, enabled=False):
        evaluation.require(type(enabled) is bool, "INVALID_ENABLE_FLAG")
        ids = [hit["perfumeId"] for hit in hits]
        evaluation.require(len(ids) == len(set(ids)), "DUPLICATE_SEARCH_CANDIDATES")
        evaluation.require(set(ids) == set(assessments), "ASSESSMENT_CANDIDATE_MISMATCH")
        any_signal = any(item["match_count"] or item["mismatch_count"] for item in assessments.values())
        if enabled and any_signal:
            indexed = sorted(enumerate(hits), key=lambda pair: (
                bool(assessments[pair[1]["perfumeId"]]["mismatch_count"]),
                -assessments[pair[1]["perfumeId"]]["match_count"],
                assessments[pair[1]["perfumeId"]]["unknown_count"], -pair[1]["similarity"], pair[0]))
            ordered = [hit for _, hit in indexed]
        else:
            ordered = list(hits)
        eligible = [hit["perfumeId"] for hit in ordered if assessments[hit["perfumeId"]]["status"] == "MATCH"] if enabled else []
        if not enabled:
            decision = "DISABLED"
        elif not hits:
            decision = "NO_SEARCH_RESULTS"
        elif eligible:
            decision = "SUPPORTED_CANDIDATES_AVAILABLE"
        elif all(item["status"] == "MISMATCH" for item in assessments.values()):
            decision = "ALL_CANDIDATES_MISMATCH"
        else:
            decision = "INSUFFICIENT_EVIDENCE_ABSTAIN"
        return {"hits": ordered, "decision": decision, "eligible_perfume_ids": eligible,
                "changed": ids != [hit["perfumeId"] for hit in ordered], "all_conditions_unknown": not any_signal}


def status_rates(hits, assessments, k):
    prefix = hits[:k]
    if not prefix:
        return {status: evaluation.metric(reason="NO_CANDIDATES") for status in ("MATCH", "MISMATCH", "UNKNOWN")}
    counts = Counter(assessments[hit["perfumeId"]]["status"] for hit in prefix)
    return {status: evaluation.metric(counts[status] / len(prefix)) for status in ("MATCH", "MISMATCH", "UNKNOWN")}


def experiment(dataset, run, evidence, depths=(5, 10, 20), ks=(1, 5, 10, 20), enabled=False):
    evaluation.require(type(enabled) is bool, "INVALID_ENABLE_FLAG")
    evaluation.validate_dataset(dataset)
    evaluation.validate_run(run, dataset)
    validate_evidence(evidence, dataset, run)
    evaluation.require(bool(depths) and all(type(depth) is int and depth in (5, 10, 20) for depth in depths), "INVALID_CANDIDATE_DEPTHS")
    evaluation.require(bool(ks) and all(type(k) is int and 1 <= k <= 20 for k in ks), "INVALID_K_VALUES")
    analyzer, assessor, reranker = QueryConditionAnalyzer(), CandidateSuitabilityAssessor(evidence), CandidateReranker()
    queries = {query["id"]: query for query in dataset["queries"]}
    summaries, artifacts = {}, {}
    for depth in sorted(set(depths)):
        if depth > run["config"]["search_top_k"]:
            summaries[str(depth)] = {"status": "DEPTH_NOT_CAPTURED"}
            continue
        a, b = deepcopy(run), deepcopy(run)
        for label, item in (("a", a), ("b", b)):
            item["run_id"] = f"rerank-{label}-{evaluation.sha(run)[:16]}-d{depth}"
            item["config"]["search_top_k"] = depth
            for record in item["records"]:
                # A reordered source number must NEVER be used with an old answer, picks or review.
                record["recommendation"] = None
        cases = []
        rows_b = {record["query_id"]: record for record in b["records"]}
        rows_a = {record["query_id"]: record for record in a["records"]}
        for query_id in sorted(queries):
            record = rows_a.get(query_id)
            conditions = analyzer.analyze(queries[query_id]["query"])
            capture = record["search"] if record else None
            if not capture or capture["status"] != "succeeded":
                cases.append({"query_id": query_id, "category": queries[query_id]["category"],
                              "conditions": conditions, "decision": "SEARCH_NOT_SUCCEEDED", "rates": {}})
                continue
            response = capture["response"]
            hits = response["results"][:depth]
            response["results"], response["returnedCount"] = hits, len(hits)
            assessments = {hit["perfumeId"]: assessor.assess(conditions, hit["perfumeId"]) for hit in hits}
            result = reranker.rerank(hits, assessments, enabled)
            rows_b[record["query_id"]]["search"]["response"] = {"results": result["hits"], "returnedCount": len(hits)}
            cases.append({"query_id": record["query_id"], "category": queries[record["query_id"]]["category"], "conditions": conditions,
                          "decision": result["decision"], "eligible_perfume_ids": result["eligible_perfume_ids"], "changed": result["changed"],
                          "before_perfume_ids": [hit["perfumeId"] for hit in hits], "after_perfume_ids": [hit["perfumeId"] for hit in result["hits"]],
                          "assessments": list(assessments.values()), "rates": {str(k): {"a": status_rates(hits, assessments, k), "b": status_rates(result["hits"], assessments, k)} for k in sorted(set(ks)) if k <= depth}})
        report_a, report_b = evaluation.evaluate(dataset, a, ks), evaluation.evaluate(dataset, b, ks)
        comparison = evaluation.compare_reports(report_a, report_b)
        def summarize_rates(rows):
            return {str(k): {arm: {status: evaluation.average([row["rates"][str(k)][arm][status] for row in rows if str(k) in row["rates"]], len(rows))
                                   for status in ("MATCH", "MISMATCH", "UNKNOWN")} for arm in ("a", "b")} for k in sorted(set(ks)) if k <= depth}
        summaries[str(depth)] = {"status": "EVALUATED", "rule_status_rates": summarize_rates(cases),
            "by_category_rule_status_rates": {category: summarize_rates([row for row in cases if row["category"] == category]) for category in sorted({row["category"] for row in cases})},
            "decisions": dict(sorted(Counter(row["decision"] for row in cases).items())), "cases": cases,
            "artifact_sha256": {"a": evaluation.sha(report_a), "b": evaluation.sha(report_b), "comparison": evaluation.sha(comparison)}}
        artifacts[str(depth)] = (report_a, report_b, comparison)
    manifest = {"schema_version": 1, "metadata": {"experimental_enabled": enabled, "production_path_connected": False,
        "version": VERSION, "rule_version": RULE_VERSION, "sort_policy": SORT_POLICY,
        "tool_sha256": evaluation.sha(Path(__file__).read_text(encoding="utf-8")), "lexicon_sha256": evaluation.sha(LEXICON),
        "dataset_sha256": evaluation.sha(dataset), "run_sha256": evaluation.sha(run), "evidence_sha256": evaluation.sha(evidence),
        "data_kind": dataset["data_kind"], "depths": sorted(set(depths)), "k_values": sorted(set(ks)),
        "recommendation_reexecuted": False, "condition_rates_are_rule_diagnostics_not_human_accuracy": True}, "depths": summaries}
    return manifest, artifacts


def write_experiment(directory, manifest, artifacts):
    destination = Path(directory)
    destination.mkdir(parents=True, exist_ok=False)
    with (destination / "experiment.json").open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(manifest, stream, ensure_ascii=False, sort_keys=True, indent=2, allow_nan=False)
        stream.write("\n")
    for depth, (a, b, comparison) in artifacts.items():
        for label, report, paired in (("baseline", a, False), ("reranked", b, False), ("comparison", comparison, True)):
            evaluation.write_output(destination / f"depth-{depth}" / label, report, comparison=paired)
    with (destination / "condition-rates.csv").open("x", encoding="utf-8-sig", newline="") as stream:
        writer = csv.writer(stream)
        writer.writerow(("query_id", "category", "candidate_depth", "k", "arm", "match_rate", "mismatch_rate", "unknown_rate", "reason", "decision"))
        for depth, summary in manifest["depths"].items():
            for case in summary.get("cases", []):
                for k, arms in case["rates"].items():
                    for arm, rates in arms.items():
                        writer.writerow((case["query_id"], case["category"], depth, k, arm, *[rates[status]["value"] for status in ("MATCH", "MISMATCH", "UNKNOWN")], rates["MATCH"]["reason"], case["decision"]))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("dataset", "run", "evidence", "output-dir"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--candidate-depths", default="5,10,20")
    parser.add_argument("--ks", default="1,5,10,20")
    parser.add_argument("--enable-experimental-reranking", action="store_true", default=False)
    args = parser.parse_args(argv)
    try:
        for value in (args.candidate_depths, args.ks):
            evaluation.require(re.fullmatch(r"[0-9]+(?:,[0-9]+)*", value) is not None, "INVALID_DEPTH_OR_K")
        manifest, artifacts = experiment(evaluation.load_json(args.dataset), evaluation.load_json(args.run), evaluation.load_json(args.evidence),
            [int(value) for value in args.candidate_depths.split(",")], [int(value) for value in args.ks.split(",")], args.enable_experimental_reranking)
        write_experiment(args.output_dir, manifest, artifacts)
        print("OFFLINE_RERANKING_WRITTEN")
        return 0
    except evaluation.InvalidInput as failure:
        print("RERANKING_REJECTED: " + str(failure), file=sys.stderr)
        return 2
    except FileExistsError:
        print("RERANKING_REJECTED: OUTPUT_EXISTS", file=sys.stderr)
        return 2
    except (OSError, ValueError, RecursionError):
        print("RERANKING_REJECTED: LOCAL_IO_OR_FORMAT_ERROR", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
