# SCENT ARCHIVE RAG 품질 평가

이번 구현은 **이미 수집한 로컬 JSON을 평가하는 도구**다. 실제 PostgreSQL·Ollama·API 호출, SSH, Docker, Jenkins, 임베딩·checkpoint 변경을 하지 않았다. 현재 장시간 임베딩 작업과 함께 실행할 서버 수집기나 예약 작업을 추가하지 않는다. 실제 품질 수치는 아직 없다.

## 1. 구조와 기존 구현에서 확인한 사항

Python 표준 라이브러리만 사용한다. 25개 질문 규모의 계산에 Java 애플리케이션 기동, Maven 빌드, DB 드라이버, 별도 Python 패키지가 필요하지 않다. 기존 API·비동기 Job·취소·source/factId·Java 검증은 그대로 둔다. 도구에는 네트워크·DB·외부 프로세스 실행 기능이 없고, 명시한 **새 결과 디렉터리**에만 JSON/CSV를 만든다. 기존 디렉터리가 있으면 덮어쓰지 않고 실패한다.

```text
사람이 검토한 질문·qrels JSON + 승인 후 별도로 수집한 응답 JSON
                         ↓
                  scripts/rag-evaluation.py
                         ↓
           report.json / cases.csv → 필요 시 compare
```

기존 소스 분석 결과:

- `PerfumeSemanticSearchRequest`: topK 기본 5, 범위 1–20. 저장소는 코사인 거리와 perfume ID 순서로 정렬한다. 평가 도구는 응답 순서를 그대로 사용하고 similarity로 다시 정렬하지 않는다.
- `PerfumeRecommendationResponse.sources`는 **검색된 전체 후보**이며 실제 선택된 향수 목록이 아니다. 현재 조립 답변의 `[번호]`를 source의 1부터 시작하는 순번에 연결해야 한다. 이 번호는 perfume ID가 아니다.
- `RecommendationPromptBuilder`: 검색 순서에서 최대 1–2개 후보를 허용하고 각 source 내부 factId 1–2 중 하나를 선택한다. 기본 후보 상한은 1이며, 원래 검색 순서가 추천에 강한 구조적 영향을 줄 수 있다. 프롬프트 길이 제한으로 실제 노출 후보는 설정 상한보다 적을 수 있다.
- 최종 API에는 원래 LLM `picks`나 요청 시점 `Prompt.allowed`가 없다. **최종 답변만으로 factId 검증 통과나 DB 사실성을 증명할 수 없다.** 공개 응답만 있으면 인용 번호 연결·후보 관련성은 평가할 수 있지만 `evidence_integrity`는 `EVIDENCE_TRACE_NOT_CAPTURED`로 남긴다.
- 기존 DB 근거는 임베딩 생성 시점의 `source_text` 스냅샷이다. 최신 public 테이블의 사실과 항상 같다는 뜻이 아니다. 서버의 기존 SHA-256 확인과 응답 검증은 변경하지 않았다.

## 2. 파일

| 파일 | 역할 |
| --- | --- |
| `scripts/rag-evaluation.py` | 입력 검증, 지표, 추천 평가, JSON/CSV, 실험 간 비교 CLI |
| `evaluation/datasets/scent-archive-ko-v1.json` | 실제 평가용 한국어 질문 템플릿 25개; 라벨은 모두 비어 있음 |
| `evaluation/examples/synthetic-dataset.json` | 계산 검증용 가상 질문·정답 5개 |
| `evaluation/examples/synthetic-run.json` | 가상 검색·답변·근거·리뷰 입력 |
| `tests/evaluation/test_rag_evaluation.py` | DB 없는 경량 단위 테스트 |
| `docs/rag-quality-evaluation.md` | 지표·라벨링·실행·후속 승인 절차 |

실제 수집물·라벨·응답은 Git 제외 경로인 `target/rag-evaluation-private/` 또는 별도 관리 경로에 보관한다. 버전 관리하는 예시에 실제 원문·응답·인증정보를 넣지 않는다. 결과에는 질문 ID·유형·숫자·고정 사유·실행 설정만 포함한다. 질문, 답변, 향수 이름, 원문 근거·벡터는 결과/로그에 출력하지 않는다. 입력 파일에는 검토에 필요한 원문이 있을 수 있으므로 접근 권한을 제한한다.

## 3. 라벨링 구조와 기준

실제 질문 템플릿은 `data_kind=real`, `corpus_snapshot=null`, 모든 질문이 draft/unreviewed이고 `qrels=[]`다. 임의 정답 향수 ID를 생성하지 않았다. `no_match` **유형의 질문 템플릿도 실제 정답이 없다고 확정한 것이 아니다.** 사람이 현재 DB를 확인한 후 answerability를 지정한다. 데이터가 부족하면 no_match 대신 insufficient_information으로 구분한다.

질문 유형은 accord, note, mood, longevity, projection, season, exclusion, compound, missing_information, no_match의 10개다. 분위기·계절 등 주관적 항목은 검토자가 먼저 판단 기준을 정한다.

각 질문의 필드:

| 필드 | 의미 |
| --- | --- |
| id / category / query | 고정 질문 ID, 유형, 한국어 질문 원문 |
| constraints | 검토할 조건 목록. 실행 가능한 DB 필터가 아님 |
| judgment_scope | unreviewed / pool / exhaustive |
| answerability | unreviewed / answerable / insufficient_information / no_match |
| review | draft 또는 approved, reviewer, 시간대가 있는 reviewed_at |
| qrels | `perfume_id`, 정수 `grade` 0–3, `condition_satisfied` true/false/null의 배열 |

grade 기준:

- 0: 질문과 무관하거나 명확히 부적절.
- 1: 일부 관련성은 있으나 약하거나 핵심 조건에 충분하지 않음.
- 2: 핵심적으로 관련되지만 모든 부가 조건까지 확인되지는 않음.
- 3: 요청 조건을 충분한 저장 근거로 확인한 매우 적절한 후보.

기본 이진 관련성은 grade ≥ 1이며 `--relevant-grade 2` 또는 3으로 바꿀 수 있다. **grade와 조건 충족 여부는 별도 라벨**이다. 일부 관련된 향수도 제외 노트 등 필수 조건은 어길 수 있다. condition_satisfied=true는 검토 대상 조건을 모두 확인했을 때, false는 명백한 위반이 있을 때 사용한다. 데이터가 부족하면 null이다. 노트가 기재되지 않았다는 사실만으로 해당 노트가 없다고 확정하지 않는다. 누락된 지속력 평가를 낮은 지속력으로 라벨링하지 않는다.

검색 결과를 보고 그대로 정답으로 지정하거나 LLM 설명을 정답으로 사용하지 않는다. 실제 DB/임베딩 근거를 보고 사람의 검토를 거친다. 판단 불가능한 향수는 qrels에서 제외하고 pool로 유지한다. 상세 판단 근거·수동 검토 메모는 별도 비공개 파일에 질문 ID/향수 ID/스냅샷으로 연결하는 것을 권장한다.

범위에 따른 차이:

- unreviewed 또는 draft: 모든 검색 지표를 계산 불가로 처리한다. 임시 라벨이 있어도 확정 정답으로 쓰지 않는다.
- pool: 사람이 검토한 후보 집합만 라벨이 있다. 반환된 Top-K 안에 미평가 ID가 있으면 해당 K의 지표는 `UNJUDGED_RESULTS`다. 모두 평가됐으면 Precision/Hit Rate/Reciprocal Rank는 계산하지만 전체 Recall/nDCG는 계산하지 않는다.
- exhaustive: **고정 corpus에서 관련 향수 집합을 빠짐없이 확인했다는 사람의 선언**이다. 단순히 Top-20을 본 것만으로 지정하지 않는다. 명시하지 않은 ID는 관련성 0으로 취급한다. 조건 충족 라벨은 여전히 별도이며, 미기재 조건을 false로 추정하지 않는다.

approved에는 reviewer와 시각, root corpus_snapshot이 필요하다. no_match는 exhaustive이고 양의 관련성 라벨이 없어야 한다. 승인된 빈 pool, 중복 ID, bool/문자열/범위 밖 grade, 상충하는 라벨은 거부한다. 이 검증은 사람의 라벨 자체가 옳다는 보장은 아니다.

라벨 확정 후 dataset version과 corpus_snapshot을 고정한다. 수정하면 새 버전으로 관리하고 같은 평가에서 전후 라벨을 섞지 않는다. 라벨을 확인할 때의 전체 향수 집합과 검색 가능한 임베딩 범위를 구분한다. 미임베딩 정답을 제외해 Recall을 높이지 않는다.

## 4. 검색 지표의 정의

K는 평가할 순위 깊이, g_i는 i위 관련성 grade, R은 grade가 임계값 이상인 정답 향수 집합이다. 중복 결과에는 첫 등장에만 관련성/이득을 부여한다. 중복은 원래 순위 슬롯을 소비하고 아래 후보를 끌어올리지 않는다. 중복 건수와 검토 사유도 보고한다.

| 지표 | 계산 | 조건 |
| --- | --- | --- |
| Precision@K | Top-K의 관련 향수 수 / K | 해당 순위의 관련성 판정 가능. 완료 응답이 K개 미만이면 미반환 슬롯은 이득 0 |
| Recall@K | Top-K의 관련 향수 수 / 전체 관련 향수 수 | exhaustive이고 R이 비어 있지 않음 |
| RR@K | K 이내 첫 관련 결과의 순위 역수, 없으면 0 | 해당 순위 판정 가능. exhaustive이며 양의 라벨이 전혀 없으면 정의하지 않음 |
| MRR@K | 질문별 RR@K의 산술 평균 | 보고서 `reciprocal_rank.mean`; 전체 무제한 순위 MRR로 해석하지 않음 |
| nDCG@K | DCG@K / IDCG@K | exhaustive이고 이상적 이득이 양수 |
| Hit Rate@K | K 이내 관련 결과가 하나 이상이면 1, 아니면 0의 평균 | 해당 순위 판정 가능 |

`DCG@K = Σ (2^g_i - 1) / log2(i+1)`. IDCG는 확정 라벨을 grade 내림차순으로 배치한 이상적 DCG다. nDCG는 모든 graded gain을 사용하며, 이진 관련성 임계값을 올려도 낮은 grade의 graded gain 자체를 0으로 바꾸지 않는다.

검색이 성공했고 정답이 있는데 결과가 비면 모든 검색 지표가 0이다. API 실패/취소/미수집, 라벨 부재, 수집 깊이 부족은 0이 아니라 null과 고정 사유다. 예를 들어 Top-5만 수집했으면 @10/@20은 `DEPTH_NOT_CAPTURED`다. 입력을 임의로 잘라 저장하면 실제 완료 응답과 구별할 수 없으므로 **완전한 응답과 실제 요청 topK를 보관**해야 한다.

확인된 no_match 질문은 Precision/Hit Rate가 0이고 Recall/MRR/nDCG는 정의하지 않는다. 이는 추천의 올바른 거절을 실패로 판정한 것이 아니다. `by_answerability`에서 별도로 보고 수동 응답 검토와 함께 해석한다. 전체 평균만으로 거절 품질을 판단하지 않는다.

평균은 질문별 macro 평균이다. 각 지표에 `eligible_questions`, `ineligible_questions`를 붙이고 0도 평균에 포함한다. 정의 불가능한 값을 0으로 채우지 않는다. API 실패가 품질 평균에서 빠지므로 반드시 status별 수집/실패 건수도 함께 본다. pool의 평가 가능 질문만 골라 얻은 평균을 전체 질문 정확도라고 부르지 않는다.

## 5. 추천·근거 평가의 경계

| 항목 | 자동 계산 범위 | 필요한 입력 |
| --- | --- | --- |
| citation_binding | `[번호]`가 실제 sources 순번에 연결되는지, 순서/개수·중복 source ID 검사 | 현재 API 응답 |
| selected_relevance | 선택 향수 중 이진 관련성 라벨 충족 비율 | 확정 qrels |
| condition_satisfaction | 선택 향수 중 조건 충족 라벨 true 비율 | 선택 향수 모두의 확정 condition_satisfied |
| evidence_integrity | source↔perfume ID, source_text SHA-256, source별 factId 허용 범위, picks/인용 일치, 조립 답변의 정확한 일치 | 검토된 요청 시점 reference + 실제 picks |
| human_question_relevance | 답변 전체가 질문과 관련되는지 | 해당 답변을 사람이 판단한 bool 라벨 |
| human_evidence_relevance | 선택된 근거가 질문에 충분히 관련되는지 | 해당 답변을 사람이 판단한 bool 라벨 |
| human_uncertainty_appropriate | 누락 정보·불확실성을 적절하게 표현했는지 | 해당 답변을 사람이 판단한 bool 라벨 |

selected_relevance와 condition_satisfaction은 후보 기준이다. 근거 문장의 의미 적합성이나 답변 전체의 완전성을 대신하지 않는다. 선택이 없거나 조건 라벨이 하나라도 없으면 조건 충족률은 계산하지 않는다. 정보가 없다고 표현하는 문장이 있다는 이유만으로 불확실성 처리가 올바르다고 자동 채점하지 않는다.

`evidence_reference` 형식은 가상 run 예제를 따른다:

- review, query_sha256, config_sha256, corpus_snapshot: 검토자·시점과 요청/설정 연결.
- prefix_lines / suffix_lines: 실제 서버가 사용하는 고정 앞뒤 문장. 선택된 향수의 정답 라벨이 아님.
- sources: 실제 프롬프트에 노출된 후보만, source 순번과 perfume_id, 원래 source_text/source_sha256, identity_line, facts.
- 각 facts 항목: 해당 source의 로컬 fact_id, **서버가 허용한 정확한 문장** text, kind(stored_fact/uncertainty). 다시 만든 프롬프트나 LLM이 새로 만든 문장을 reference로 쓰지 않는다.
- picks: 실제 모델의 선택 JSON에서 얻은 source/factId. 최종 답변을 보고 원래 picks를 관측했다고 추정하지 않는다.

현재 API만 사용할 때는 picks와 evidence_reference를 null로 둔다. 향후 전체 자동 근거 평가가 필요하면 **별도 승인된 단발성 테스트 harness/진단 세션**에서 기존 build/generate/validateAndRender의 같은 요청에 대한 allowed 문장·picks·결과를 수집해야 한다. 이번에 운영 API나 로깅 경로를 변경하지 않았다.

source_text 해시는 파일 변조를 검사하지만 DB에서 가져온 파일이라는 출처를 자체 증명하지는 않는다. 검토자는 실제 DB/임베딩 스냅샷과 원문·문장 매핑을 확인해야 한다. 통과한 평가의 scope는 `human_reviewed_request_snapshot`이다. **현재 운영 DB를 독립 검증한 결과나 최신 사실 보증이 아니다.** 잘못 승인한 reference의 의미상 오류는 Python이 알아낼 수 없다.

답변별 human_review는 review, answer_sha256, query_sha256, config_sha256과 세 bool/null 항목을 갖는다. 답변·질문·설정 해시가 달라지면 `STALE_RESPONSE_REVIEW`다. 예전 리뷰를 새 실험의 정답으로 자동 재사용하지 않는다. reference의 설정/문맥 불일치는 무결성 실패로 보고한다. 해시가 맞도록 고치는 것으로 오래된 리뷰를 새 리뷰처럼 만들지 않는다.

검색 순위 의존성은 `selected_rank_mean`, `top1_selected`, `rank_prefix_selected`를 관측한다. 마지막 항목은 선택 순위가 1부터의 연속 prefix인지 뜻한다. `rank_choice_constrained_by_cap`과 검증된 trace가 있을 때 `exposed_candidate_count`도 기록한다. 후보 상한 1에서 1위만 선택하는 현상은 설계의 결과일 수 있다. 이 수치만으로 LLM 편향·인과관계를 증명하지 않는다. 후보를 고정하고 노출 순서만 바꾸는 실험은 향후 별도 harness 승인이 필요하며 기존 API의 순서를 이번에 바꾸지 않는다.

가상 예제에는 근거 무결성은 통과하지만 조건 충족/관련성은 0인 사례가 있다. 이는 두 종류 평가가 독립적임을 보여주는 테스트이며 실제 서비스 품질 수치가 아니다.

## 6. 실행 입력과 로컬 사용

지원 환경은 Python 3.10 이상이며 추가 패키지 설치는 필요 없다. 프로젝트 루트에서 실행한다. Windows에서 `python` 별칭을 사용할 수 없으면 설치된 Python 경로로 대체한다.

```bash
# 이 명령들은 로컬 파일만 읽거나 새 보고서 디렉터리만 생성한다.
python -B scripts/rag-evaluation.py validate-dataset --dataset evaluation/datasets/scent-archive-ko-v1.json
python -B scripts/rag-evaluation.py validate-dataset --dataset evaluation/examples/synthetic-dataset.json --fingerprints
python -B scripts/rag-evaluation.py evaluate \
  --dataset evaluation/examples/synthetic-dataset.json \
  --run evaluation/examples/synthetic-run.json --ks 1,3,5 \
  --output-dir target/rag-evaluation-synthetic
```

이미 존재하는 결과 디렉터리를 지정하면 OUTPUT_EXISTS로 실패한다. 재실행에는 새 이름을 사용한다. 도구는 파일이나 checkpoint를 삭제하지 않는다. JSON 중복 키·NaN/Infinity·알 수 없는 필드·잘못된 자료형·카운트·중복 질문·해시 불일치를 거부한다. 입력은 최대 20MB, 질문은 최대 1000개, 검색 결과는 최대 20개다. CLI 오류에는 원래 예외·경로·입력 내용을 붙이지 않는다.

run JSON의 필수 root는 schema_version=1, run_id, data_kind, dataset_sha256, config, records다. 가상 run은 synthetic, 실제 run은 real이어야 하며 혼용하면 실패한다. 실제 파일을 만들 때 예제의 가상 ID·모델 digest·스냅샷 값을 그대로 사용하지 않는다.

config에는 다음 실측 값을 기록한다:

- corpus_snapshot: 확정 라벨을 검토한 전체 데이터 스냅샷 식별자.
- embedding_snapshot, embedding_coverage(total_perfumes/embedded_perfumes): 임베딩 확대 전후를 구분하는 식별자·같은 모델 기준 실제 수. 특정 향수 개수를 고정하지 않는다.
- embedding_model/digest, llm_model/digest: 사용 모델과 전체 소문자 64자리 digest. `sha256:` 접두사는 제외한다. 같은 태그만으로 동일 모델이라 가정하지 않는다.
- text_version, prompt_revision, formatter_revision, backend_revision: 실행한 텍스트/프롬프트/근거 생성 코드/백엔드 버전.
- ollama_version과 generation_options: 실제 Ollama 버전, num_ctx, num_predict, temperature, num_thread, max_input_bytes. 마지막 항목은 Java 프롬프트 입력 예산이다. 기본값으로 추측하지 말고 수집 시점의 실제 설정을 기록한다. 기존 호출은 seed를 지정하지 않으므로 동일 LLM 출력을 보장하지 않는다.
- captured_at: 시간대가 있는 수집 시각, search_top_k: 실제 요청 깊이, max_evidence_candidates: 실제 LLM 설정 상한.

records는 질문별 query_id/query_sha256/search/recommendation이다. 미수집 질문은 records에 없어도 되며 보고서에서 누락 건으로 나타난다. 검색 상태는 succeeded/failed/cancelled/timed_out/not_captured 중 하나다. 실패 시 response=null이고 error_code는 본문 대신 안전한 고정 코드만 둔다.

search.response에는 기존 검색 API의 `results/returnedCount` JSON을 그대로 넣는다. recommendation.response에는 기존 추천 API의 `answer/sources/returnedCount` JSON을 넣는다. Job은 SUCCEEDED일 때의 result만 이 위치에 넣고 토큰·HTTP 헤더·jobId는 넣지 않는다. 다른 최종 상태는 해당 실패/취소 상태로 기록한다. 진행 중 Job을 성공으로 변환하지 않는다.

같은 추천 요청의 검색 품질을 평가하려면 recommendation.sources로 그 요청의 search.response를 구성할 수 있다. 여기에는 **실제로 반환한 전체 sources**를 사용하며 선택된 인용만 가져오면 안 된다. 별도로 호출한 검색 결과와 추천 sources가 다르면 `SEARCH_RECOMMENDATION_RANKING_DIFFER`로 표시한다. 추천 순위 진단은 추천 응답 자체의 sources를 기준으로 계산한다.

dataset_sha256은 UTF-8 canonical JSON(sort_keys=True, ensure_ascii=False, separators=(',', ':'), allow_nan=False)의 SHA-256이다. query_sha256과 answer_sha256은 해당 문자열의 UTF-8 바이트 해시다. config_sha256은 동일 canonical 방식으로 config를 해시한다. 데이터셋/질문 해시는 위 --fingerprints로 확인할 수 있다. 검토 시 아래처럼 도구의 동일 함수를 로컬에서 사용할 수도 있다:

```python
import importlib.util
from pathlib import Path
spec = importlib.util.spec_from_file_location("rag_eval", "scripts/rag-evaluation.py")
tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)
run = tool.load_json(Path("target/rag-evaluation-private/run.json"))
config_sha256 = tool.sha(run["config"])
# 각 원문 문자열은 tool.sha(text), 확정 데이터셋은 tool.sha(dataset).
# 새로 수집·검토한 artifact에 기록할 해시다. 오래된 승인을 강제로 재연결하지 않는다.
```

## 7. 보고서와 실험 비교

report.json에는 실행 설정·도구 버전/소스 해시·데이터셋/입력 해시, 전체/유형별/answerability별 평균, 지표별 분모, 수집 실패·평가 불가 사유, 질문별 결과, cases_needing_review가 있다. CSV는 질문×K의 값/계산 불가 사유다. CSV의 추천 값은 각 K 행에 반복되므로 CSV 전체를 다시 평균내지 않는다. 추천 macro 평균은 JSON summary를 사용한다.

같은 로컬 실행 환경·입력·도구로 실행하면 생성 시각을 새로 끼워 넣지 않아 JSON/CSV 바이트가 동일하다. 도구 소스 해시는 줄바꿈을 LF로 정규화해 Windows/Linux 체크아웃 차이를 제거한다. 질문/응답의 실제 수집 시각은 config.captured_at에 이미 기록한다. 결과 reproducibility는 **오프라인 계산 재현성**이며 실제 LLM 호출의 동일 출력 보장은 아니다.

```bash
python -B scripts/rag-evaluation.py compare \
  --before target/experiment-a/report.json \
  --after target/experiment-b/report.json \
  --output-dir target/experiment-comparison
```

동일 dataset SHA·corpus·지표 정책·관련성 임계값·K 목록·도구 소스 버전만 비교한다. 둘 다 평가 가능한 **동일 질문 쌍만** 평균·점수 차이(after-before, mean_score_delta)에 넣고 paired/excluded 건수를 표시한다. 차이는 부호를 유지한다. 서로 다른 eligible 집합의 전체 평균을 단순 차감하지 않는다. 임의 개선율이나 상대 개선 퍼센트를 만들지 않는다. 검색/추천 상태별 커버리지도 전후 기록한다.

비교 가능한 실험 예:

| 바꿀 조건 | 유지할 조건/주의점 |
| --- | --- |
| search_top_k 5/10/20 | 같은 질문·라벨·K 목록. @10/@20 미수집 실험은 해당 비교에서 제외 |
| max_evidence_candidates 1/2 | 같은 검색 후보, 모델, 라벨. 각 설정의 실제 prompt trace/응답 리뷰는 새로 수집 |
| 임베딩 확대 전/후 | 같은 전체 corpus/라벨, 다른 embedding_snapshot/실측 coverage |
| 프롬프트/번호 선택 방식 전/후 | 같은 corpus/라벨, 정확한 코드 revision과 요청별 새 reference/리뷰 |

corpus 자체가 달라진 경우 현재 compare는 거부한다. 같은 스냅샷에서 다시 측정하거나 별도 연구 설계를 마련해야 한다. 테스트셋을 보며 계속 조정해 점수를 과대평가하지 않도록 향후 개발용/최종 확인용 질문 분리와 복수 검토자의 일치도 확인을 권장한다.

## 8. 이번 검증과 후속 승인

실행한 경량 단위 테스트:

```bash
python -B -m unittest discover -s tests/evaluation -v
```

Windows의 기존 pgAdmin Python으로 **52개 통과**, 실패/오류 0. 산술 예제, no-label/pool/no-match, 중복·빈 검색, 형식/라벨·생성 설정 거부, 질문별/유형별 macro 평균, 스냅샷/원문 해시, factId 스코프, 미수집 trace, 사람 리뷰의 오래된 해시, 순위 진단, 비교 분모, JSON/CSV 바이트 재현성, 기존 출력 덮어쓰기 거부를 검사했다. 네트워크/프로세스 접근을 금지하는 Mock과 import 검사도 포함한다. 테스트 로그는 `target/rag-evaluation-validation/unit-tests.log`다.

전체 Maven, Docker 빌드, 배포/브라우저 통합 테스트, 실제 DB/Ollama 평가는 **장시간 임베딩 작업 보호를 위해 실행하지 않았다**. 이번 수치들은 가상 fixture 검증이며 실제 검색 정확도나 개선율이 아니다.

임베딩 배치 종료 후 별도 승인받아 진행할 순서:

1. 운영자가 배치/worker 종료와 자원 여유를 확인한다. 평가 도구가 worker를 중지하거나 checkpoint를 고치지 않는다.
2. 읽기 전용으로 전체 corpus와 모델별 임베딩 coverage·버전·digest·source 스냅샷을 확인한다. 운영 데이터에 SELECT 외 쓰기를 하지 않는다. 평가 전용 DB 자격증명이나 인증 토큰은 수집 파일에 넣지 않는다.
3. 질문별 후보 pool을 다양한 설정에서 수집하고 사람이 저장 근거를 검토한다. 미평가/정보 부족을 오답으로 바꾸지 않는다. exhaustive는 관련 집합을 실제로 충분히 확인했을 때만 선언한다. 라벨과 데이터셋 버전을 확정한다.
4. 승인된 소량의 실제 검색·추천을 직렬로 실행하고 요청 설정·완전한 응답·실패 상태를 로컬 JSON으로 반출한다. 이 단계의 API는 읽기 목적이지만 Ollama CPU/메모리를 사용하므로 자동·백그라운드 실행하지 않는다. Job/취소는 기존 기능을 그대로 사용한다.
5. 가능하면 승인된 별도 harness에서 동일 요청의 정확한 allowed/picks/원문 스냅샷을 얻어 검토한다. 얻을 수 없으면 해당 근거 검사를 평가 불가로 남긴다. 기존 API만으로 factId 관측이 가능하다고 가정하지 않는다. 최종 답변의 의미·불확실성 처리는 사람이 리뷰한다.
6. 이 도구의 evaluate로 오프라인 보고서를 만들고, 실패/미평가 비율과 category/answerability별 차이를 먼저 확인한다. 프롬프트 길이·단일 후보 편중, 복합/제외 조건, 누락 지표, 임베딩 coverage 문제를 구분해 개선 후보를 고른다.
7. 비교할 설정을 한 번에 하나씩 바꾸고 동일 확정 데이터셋으로 재수집·재평가한다. 실제 측정 없이 향상됐다고 보고하지 않는다.

GitHub/Jenkins 반영 전에는 변경 파일에 실제 응답/라벨·인증정보가 없는지 확인하고, 경량 평가 테스트를 다시 실행한다. 자원 여유와 별도 승인이 있을 때 기존 Maven·배포 보호·Job/취소 회귀를 수행한다. 이번 작업에서는 기존 CI 설정에 평가 수집을 추가하지 않았으며 Python 평가 단위 테스트도 자동 CI에 연결하지 않았다. Commit/Push, Jenkins 실행, 서버 배포, SSH, 실제 DB/Ollama 접근은 별도 승인 사항이다.
