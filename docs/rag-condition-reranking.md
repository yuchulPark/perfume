# RAG 질문 조건 분석·재정렬 실험 1차

**운영 연결 없는 로컬 프로토타입이다.** 기본 재정렬은 비활성이고 `--enable-experimental-reranking`을 지정한 오프라인 실험만 순서를 바꾼다. Spring API, Job·취소, source/factId, 프롬프트, DB·임베딩·checkpoint, Docker/Jenkins 설정을 변경하지 않는다. 모델·DB·HTTP 클라이언트나 자동 실행 작업을 추가하지 않았다.

## 1. 현재 추천이 조건과 불일치할 수 있는 구조

분석한 소스는 `PerfumeSemanticSearchService`, `JdbcPerfumeSemanticSearchRepository`, `PerfumeRecommendationService`, `RecommendationPromptBuilder`, `RecommendationEvidenceFormatter`, `PerfumeEmbeddingText`와 기존 평가 도구·데이터셋이다.

- 검색은 질문 전체를 임베딩하고 `ai.perfume_embeddings`에서 모델명/전체 digest/text_version이 일치하는 행만 코사인 거리 오름차순, ID 오름차순으로 반환한다. topK 기본 5, 허용 범위 1–20이다. 부정·복합 조건을 별도로 구조화해 검사하거나 필터링하는 경로는 없다.
- 추천 서비스는 검색 순서를 유지해 근거를 읽고 프롬프트에 전달한다. 기본 LLM 후보 상한은 1이며 최대 2다. 입력 예산에 따라 실제 노출 후보는 더 줄어든다. 따라서 잘못 올라온 1위 후보를 LLM이 다른 후보로 충분히 대체하기 어렵다.
- 모델은 후보당 최대 두 factId 중 **한 문장**을 선택한다. Java는 허용 source/factId, 중복·순서·스키마를 검증하고 근거 문장을 조립한다. 이 검증은 사실·인용 무결성을 보호하지만 질문의 모든 조건을 충족하는지 확인하지는 않는다.
- formatter는 노트·어코드·설명·평가 원자료 등을 문장화한다. 지속력/발향/계절 관련 질문이면 해당 지표나 불확실성 문장을 앞세우지만, 제외 노트·필수 조건을 후보 수준에서 검증하는 재정렬기는 아니다.
- `PerfumeEmbeddingText`는 이름/브랜드/설명/연도/리뷰 수, 층별 노트, 어코드와 score/percentage, Metric의 score/category/records/reliability/scale/derived_from 등을 저장한다. 이 텍스트가 풍부하다는 사실만으로 유사도 검색이 모든 조건을 만족시키지는 않는다.

오류나 환각 없이도 관련된 노트 한 가지가 비슷한 향수가 조건 위반 상태로 상위에 올 수 있다. **근거 무결성과 조건 적합성은 별개**라는 기존 평가 원칙을 유지한다.

## 2. 별도 구성과 파일

| 파일 | 역할 |
| --- | --- |
| `scripts/rag-reranking.py` | 독립 분석기·판정기·재정렬기, 오프라인 A/B CLI |
| `tests/evaluation/test_rag_reranking.py` | 가상 데이터 기반 경량 회귀 테스트 |
| `evaluation/examples/synthetic-reranking-dataset.json` | 가상 질문·라벨 7개 |
| `evaluation/examples/synthetic-reranking-run.json` | 가상 Top-K 응답·실행 설정 |
| `evaluation/examples/synthetic-reranking-evidence.json` | 가상 카탈로그 근거·범주 해석 규약 |
| `docs/rag-condition-reranking.md` | 설계, 실행, 실제 검증·운영 적용 조건 |

기존 `scripts/rag-evaluation.py`, 한국어 질문 25개와 기존 가상 예제는 수정하지 않는다. 기존 도구의 입력 검증·해시·검색 지표·paired 비교·JSON/CSV 출력을 그대로 재사용한다. Python 표준 라이브러리만 사용하며 두 스크립트를 같은 scripts 디렉터리에 둔다. Java 실행이나 Maven 의존성은 필요 없다.

## 3. 결정적인 조건 분석

`QueryConditionAnalyzer`는 NFKC 정규화, 소문자화, 공백 정규화 후 명시적인 한국어/영어 표현을 처리한다. 무작위 처리와 LLM 호출은 없다. 조건의 kind/value/operator와 고정 사유를 반환하고, 원문을 진단 로그에 복사하지 않는다.

```text
여름에 사용하기 좋은 시트러스 향수인데 바닐라는 싫어
→ season / summer / include
→ accord / citrus / include
→ note / vanilla / exclude
```

초기 표기 매핑은 코드의 닫힌 LEXICON으로 고정했다. 바닐라/vanilla, 장미·로즈/rose, 베르가모트/bergamot, 아이리스/iris, 라벤더/lavender, 재스민/jasmine과 시트러스/citrus, 플로럴/floral, 우디/woody 및 사계절의 직접 표기만 다룬다. 검증 범위는 이 직접 표기들의 규칙·경계·한영 대응이며, 전체 향료 사전이나 ScentRev의 공식 alias 목록을 검증했다는 뜻은 아니다. 임의 입력 매핑을 로드하지 않는다. 확대는 실제 DB 명칭 검토와 테스트 후 별도 버전으로 한다.

다음 추론은 하지 않는다:

- bergamot/grapefruit가 있으니 citrus 어코드라고 단정.
- amber가 있으니 vanilla가 있다고 단정.
- rosemary/rosewood/로즈마리를 rose로 부분 일치.
- 시트러스나 상쾌함으로 여름 적합성을 추론.
- sillage를 projection과 같은 지표로 자동 치환.

`with vanilla`, `without rose`, `바닐라는 싫어`, `시트러스 향수`, `바닐라와 장미는 싫어` 같은 포함/제외 문형을 처리한다. 인접한 다른 노트에 부정 표현이 번지지 않도록 prefix, suffix, 나열·절 경계를 구분한다. 같은 조건을 반복해도 가중치가 늘지 않도록 중복 제거한다.

`싫지 않아`, `없어도 괜찮아`, `제외하지 마`, `not without`, `not required`, OR/조건문, 서로 충돌하는 포함·제외는 unknown이다. 모호한 문형이 있으면 같은 절을 보수적으로 처리할 수 있다. 지원 문법을 제외하고 남은 분위기·상황·수치 등도 `UNPARSED_QUERY_CONTENT` 조건으로 남긴다. 지원하는 조건만 읽고 나머지를 무시해 전체 질문이 만족됐다고 표시하지 않는다. 만능 한국어 분석기가 아니므로 일부 자연스러운 표현도 UNKNOWN이 될 수 있다.

지속력이 좋은/높은/긴, 오래 가는/long-lasting, 낮은/짧은 지속력, 강한/약한 발향력·high/weak projection 같은 명시적 선호를 추출한다. '높지 않다'를 '낮다'로 역산하거나 10시간 등 수치 요구를 임의 범주로 바꾸지 않는다. 지원하지 않는 표현은 unknown이다.

## 4. 후보 적합성 판정과 Metric 해석

`CandidateSuitabilityAssessor`는 조건별 MATCH/MISMATCH/UNKNOWN과 고정 사유를 반환한다. 전체 판정은 하나라도 MISMATCH면 MISMATCH, 위반 없이 UNKNOWN이 있으면 UNKNOWN, 모든 조건이 확인됐을 때만 MATCH다.

| 조건과 저장 근거 | 판정 |
| --- | --- |
| 원하는 노트/어코드가 해당 구조화 필드에 직접 존재 | MATCH |
| 제외한 노트/어코드가 해당 필드에 직접 존재 | MISMATCH |
| 원하는/제외한 항목이 목록에 없음 | UNKNOWN: ABSENCE_NOT_PROVEN |
| 데이터·후보 근거 누락 또는 검토되지 않은 스냅샷 | UNKNOWN |
| 의미가 확인된 Metric 범주가 조건과 일치/반대 | MATCH / MISMATCH |
| 척도·범주·표본·신뢰성 해석 부족 | UNKNOWN |

노트 이름은 원래 값 전체를 직접 표기 목록과 비교한다. 설명·향수 이름에 노트 단어가 나온다는 이유로 노트 필드를 채우지 않는다. 기록에 없는 것을 실제로 없는 것으로 해석하지 않는다. 현재 DB에 명시적인 '미포함 보증' 필드가 없으므로 제외 조건이 있는 후보는 명백한 위반을 피했더라도 전체 MATCH가 되기 어렵다. 이 한계를 감추려고 제외 조건을 충족으로 바꾸지 않는다.

Metric은 **검토된 해석 규약(metric_contracts)** 이 있을 때만 사용한다. 규약은 id, kind, season value(계절일 때), 정확한 metric_key/scale, 범주별 상태, 허용 reliability/derived_from, 검토자·시각과 semantic_reference를 포함한다. score를 높고 낮음으로 변환하는 임계값·산식은 없다.

규약을 적용할 때:

1. review=approved이고 실제 필드 의미를 semantic_reference로 확인해야 한다.
2. metric_key/scale이 정확히 일치해야 한다. 원자료에 scale이 없으면 임의로 채우지 않는다.
3. records가 실제로 기록돼 있고 0보다 커야 한다. 이것은 관측치가 없음을 걸러내는 검사이며 통계적으로 충분한 표본 수라는 뜻이 아니다.
4. reliability와 derived_from은 검토된 허용 목록과 일치해야 한다. 수치 신뢰성 점수를 새로 만들지 않는다.
5. category를 승인된 high/low 또는 suitable/unsuitable에 직접 연결한다. 중간·미확인 범주는 UNKNOWN이다. 해석 가능한 서로 반대 근거가 있으면 UNKNOWN이다.

표본·신뢰성은 판정 가능성의 조건과 진단 정보이며 재정렬 가중치로 합산하지 않는다. '표본 100이면 충분', 'score 0.8이면 높음' 같은 기준은 도입하지 않았다. 예제 규약은 **synthetic 척도에 대한 가상 정의**다. 실제 provider 범주 의미·통계 품질을 승인한 규약이 아니므로 운영에 복사하면 안 된다. 실제 데이터의 scale/reliability 누락으로 대부분 UNKNOWN이 될 가능성도 검증해야 한다.

## 5. 재정렬과 추천 보류 정책

`CandidateReranker`의 실험 활성 시 정렬 키는 다음 순서다.

1. 명시적 위반이 있는가: 위반 없는 후보 먼저.
2. 확인된 MATCH 조건 개수 내림차순.
3. UNKNOWN 조건 개수 오름차순.
4. 기존 pgvector similarity 내림차순.
5. 원래 검색 순서.

조건별 중요도 가중치, 학습 모델, 임계값 최적화는 없다. 모든 명시적 추출 조건을 이 초기 실험의 검사 조건으로 취급한다. 필수·선호 강도 구분은 후속 과제다. 동일 조건/유사도는 stable하게 유지한다. 모든 후보의 모든 조건이 UNKNOWN이면 새 신호가 없으므로 similarity로도 재정렬하지 않고 원래 순서를 유지한다. 일부 조건만 MATCH인 UNKNOWN 후보는 재정렬될 수 있지만 추천 가능하다고 확정하지 않는다.

순위 실험은 **위반 후보도 삭제하지 않는다.** A/B가 동일한 후보 집합을 갖도록 뒤로 배치하고, 별도 eligible_perfume_ids에는 전체 MATCH 후보만 넣는다. 중복 검색 ID나 후보/판정 집합 불일치는 거부한다.

| decision | 의미 |
| --- | --- |
| DISABLED | 기본 상태, 순서 변경·추천 승인 없음 |
| SUPPORTED_CANDIDATES_AVAILABLE | 실험에서 모든 조건을 확인한 후보가 존재 |
| ALL_CANDIDATES_MISMATCH | 전부 명시적 위반. 강제 추천하지 않음 |
| INSUFFICIENT_EVIDENCE_ABSTAIN | 충분히 확인된 후보 없음. 설명/조건 명확화가 필요한 상태 |
| NO_SEARCH_RESULTS / SEARCH_NOT_SUCCEEDED | 후보 없음 또는 미수집/실패 |

이는 오프라인 정책 결과이며 현재 API 응답을 바꾸거나 LLM을 호출하지 않는다. 조건 위반이 확인되지 않았다는 사실과 조건 충족이 증명됐다는 사실을 구분한다.

## 6. 입력 근거 스냅샷

기존 평가 도구의 dataset/run JSON 형식을 그대로 쓴다. 추가 evidence JSON은 새 가상 예제를 참조한다.

- root: schema_version=1, data_kind, run_sha256, config_sha256, corpus_snapshot, embedding_snapshot, evidence_basis=catalog_snapshot, review, metric_contracts, candidates.
- candidates: perfume_id, notes(문자열 배열 또는 null), accords(배열 또는 null), metrics, snapshot_sha256.
- 각 metrics: key, score, category, records, reliability, scale, derived_from. 누락된 scalar는 null이고 의미를 보완하지 않는다.
- snapshot_sha256은 perfume_id/notes/accords/metrics만 담은 canonical JSON의 해시다. 기존 도구의 sha/canonical 정의를 재사용한다.

모델 전체 digest/text_version을 포함하는 run config 해시, 입력 run 해시와 corpus/embedding snapshot을 모두 연결한다. 다른 실행·모델·스냅샷을 조용히 섞지 않는다. 후보 근거 변경, 중복 Metric key, 상충하는 범주 매핑 등을 검증한다. 해시는 파일 일관성 검사이지 실제 DB 출처에 대한 암호학적 증명은 아니다. 실제 데이터와 비교한 export 검토가 필요하다.

실제 반출 시 `PerfumeEmbeddingDocument.notes.name`, `accords.name`, `metrics.key/score/category/records/reliability/scale/derivedFrom`의 기존 구조를 사용한다. 층과 관계없이 노트 존재 여부만 확인하며 누락된 자료를 생성하지 않는다. public 테이블에 쓰거나 테이블을 추가할 필요가 없다.

이 실험은 **고정 카탈로그 스냅샷을 기준으로 적합도를 연구**한다. 기존 RAG가 사용하는 임베딩 당시 source_text와 최신 카탈로그는 다를 수 있다. 새 판정이 LLM에 실제 제공한 근거로도 설명 가능한지, source_sha256/시점이 맞는지는 운영 연결 전 추가 검증해야 한다. 최신 카탈로그 사실을 과거 LLM 근거처럼 취급하지 않는다.

실제 export/라벨/응답은 Git 제외 경로 `target/rag-reranking-private/` 같은 별도 위치에 둔다. 예제는 모두 synthetic이며 실제 향수 ID나 측정 결과가 아니다. 원문 질문·향수명·노트 원자료·응답 본문을 결과 로그로 전달하지 않는다. 결과에는 ID·정규화 조건·고정 사유·개수·해시 및 해석에 사용한 records/reliability 정도만 남긴다.

## 7. 로컬 실행 및 기존 평가 도구 연계

Python 3.10 이상, 추가 패키지 없이 저장소 루트에서 실행한다. 아래 명령은 로컬 예제 파일만 읽으며 실제 수집을 하지 않는다. 결과 디렉터리는 새 이름이어야 하며 덮어쓰기하지 않는다.

```bash
# 기본 비활성: A/B 순서가 같고 decision=DISABLED
python -B scripts/rag-reranking.py \
  --dataset evaluation/examples/synthetic-reranking-dataset.json \
  --run evaluation/examples/synthetic-reranking-run.json \
  --evidence evaluation/examples/synthetic-reranking-evidence.json \
  --output-dir target/rag-reranking-default

# 명시적인 오프라인 재정렬 실험
python -B scripts/rag-reranking.py \
  --dataset evaluation/examples/synthetic-reranking-dataset.json \
  --run evaluation/examples/synthetic-reranking-run.json \
  --evidence evaluation/examples/synthetic-reranking-evidence.json \
  --candidate-depths 5,10,20 --ks 1,5,10,20 \
  --enable-experimental-reranking \
  --output-dir target/rag-reranking-enabled
```

각 후보 깊이 N=5/10/20에서 원래 검색의 첫 N개로 pool을 고정하고, **그 pool의 순열만** 만든다. Top-5만 수집했으면 N=10/20을 가정하지 않고 DEPTH_NOT_CAPTURED다. 반환된 전체 후보가 N개 미만인 실제 완료 응답은 그 반환 후보로 비교한다. 입력 전체/카탈로그 후보를 몰래 추가하지 않는다.

산출물:

```text
experiment.json                   분석 조건, 후보별 판정·원래/새 ID 순서·보류 정책
condition-rates.csv               질문×후보 깊이×K×A/B의 규칙 판정 비율
depth-5/baseline/report.json       기존 평가 도구로 계산한 A 검색 품질
depth-5/baseline/cases.csv
depth-5/reranked/report.json       같은 라벨로 계산한 B 검색 품질
depth-5/reranked/cases.csv
depth-5/comparison/comparison.json 기존 paired 비교
depth-5/comparison/comparison.csv
depth-10/... / depth-20/...
```

experiment에는 rule/lexicon/도구 버전·해시, 원본 dataset/run/evidence 해시, 활성 여부, 각 산출물 해시를 기록한다. 정렬 정책과 입력을 재현할 수 있다. 지표별 분모·null 사유·유형별 품질은 기존 report/compare를 그대로 사용한다. 재정렬기 자체에는 두 번째 Precision/MRR/nDCG 공식을 만들지 않았다.

기존 한국어 질문 25개를 실제 라벨링하고 기존 형식의 run/evidence를 수집하면 dataset 경로를 그 파일로 바꿔 실행할 수 있다. 질문 ID·문장·라벨을 A/B 사이에서 바꾸지 않는다. 미수집 질문도 규칙 집계의 평가 불가 분모에 포함한다. 정답이 없으면 검색 품질 변화는 null이며 개선율을 만들지 않는다.

**중요한 해석상 제한:**

- 동일 pool N개를 단순 재정렬하면 Precision@N·Recall@N·Hit Rate@N은 변하지 않는다. Top-5의 5개 전체에서 Precision@5 향상을 기대하면 안 된다. Top-10/20 안의 Precision@1/@5, MRR, nDCG 등 위치 영향을 본다.
- MATCH/MISMATCH/UNKNOWN 후보 비율은 실제 반환 prefix min(K, 반환 수)를 분모로 하며, 후보가 없으면 null이다. 셋은 정의될 때 합계 1이다. macro 평균과 eligible/ineligible 질문 수도 보고한다.
- 이 비율은 **규칙 자체의 판정 진단**이다. 재정렬 기준으로 사용한 값이 좋아지는 것은 독립 정답 정확도 증거가 아니다. 확정된 사람 라벨의 Precision/MRR/nDCG와 UNKNOWN·보류 비율을 함께 검토한다.
- 기존 추천 답변을 B 순위에 붙이면 source 번호와 factId가 틀어진다. 두 파생 평가 입력의 recommendation은 null로 분리하고, 원본 run은 그대로 보존한다. 추천 응답 호환성이나 정확도 향상을 새로 실측했다고 주장하지 않는다. 이 작업은 검색 순위 실험이며 LLM 재실행이 아니다.
- 전체 A/B 검색 품질과 함께 by_category, 후보 깊이, 라벨 커버리지·보류 현황을 확인한다. 실제 측정 없이 어느 설정이 더 낫다고 결론 내리지 않는다.

## 8. 경량 테스트 결과

```bash
python -B -m unittest discover -s tests/evaluation -v
```

기존 평가 테스트 52개 + 새 재정렬 테스트 56개, **총 108개 통과**. 실제 PostgreSQL·Ollama 없이 가상 데이터·Mock·로컬 임시 파일만 사용했다. 포함/제외/복합, 부정 오인식, 잘못된 표기 대응, 한영, 누락·Metric 척도·표본·신뢰성, 계절, 빈 결과, stable sort, UNKNOWN 보존, 기본 비활성, 5/10/20 pool 경계, 같은 후보 집합에서 Precision 불변, 기존 추천 입력 보존, 25개 질문 재사용, 무라벨 처리, 해시·출력 재현성을 검사했다.

이번에는 Maven 전체 빌드·통합 테스트, Docker/DB/실제 Ollama 검증을 하지 않았다. 기존 Java·배포 코드를 수정하지 않았으며 별도 서버 작업도 수행하지 않았다. 가상 테스트의 숫자를 실제 향수 추천 개선율로 제시하지 않는다. 로그는 `target/rag-reranking-validation/unit-tests.log`에 있다.

## 9. 실제 검증과 운영 연결 전 조건

별도 승인 후 실제 데이터로 확인할 항목:

1. 배치 종료·자원 여유·읽기 전용 수집 승인을 확인한 뒤 기존 25개 질문에 대한 Top-20 완전 응답을 수집한다. 후보·노트·어코드·Metric은 같은 카탈로그 스냅샷으로 반출한다. 운영 checkpoint를 수정하지 않는다.
2. 실제 한국어/영어 노트 이름이 닫힌 직접 표기와 얼마나 맞는지 확인한다. 예제와 다른 명칭을 유사도나 부분 문자열로 조용히 치환하지 않는다. 미지원 표기·부정 문형의 UNKNOWN 사례를 검토한다.
3. 실제 Metric key, scale, category, records, reliability, derived_from의 의미와 누락률을 확인한다. source 문서/정의가 없으면 규약을 만들지 않고 UNKNOWN으로 둔다. 표본 최소 기준·가중치 조정은 별도 검토·승인 대상이다.
4. 사람이 확정한 같은 라벨로 A/B 검색 지표와 규칙 판정·보류 비율을 본다. 제외·계절·복합 질문에서 거짓 MATCH가 없는지 우선 확인한다. 기계 판정 정확도 자체를 검증하려면 후보별 사람의 MATCH/MISMATCH/UNKNOWN 판정도 별도로 모아야 한다.
5. 개선이 확인된 뒤에도 stale embedding과 카탈로그 차이, source 재번호 부여, factId 재생성, Job 취소 시점·deadline·admission, 추가 조회 시간 등을 별도 설계·테스트한다. 예전 picks/답변을 재사용하지 않는다.
6. 기본 비활성 운영 feature flag와 작은 승인 범위, 기존 경로 복귀 방법을 마련한 후에만 API 연결을 검토한다. 지금 오프라인 플래그는 운영 설정이 아니며 어떤 서버 경로에도 연결되지 않았다.

후속 2차 제안: 실패 유형별로 검증된 직접 표기 확장, 필수/선호 구분, 명시적 논리식·부정 범위 개선, 검증된 Metric 의미 사전, 실제 라벨을 이용한 hybrid 후보 수집·재정렬 비교, 부분 충족/정보 부족을 사용자에게 설명하는 정책을 차례로 실험한다. 노트 미기재를 '없음'으로 바꾸거나 숫자를 임의 기준으로 이산화하는 방식은 피한다. LLM 후보 상한 1/2 조정도 동일 후보·라벨로 별도 비교한다.

GitHub/Jenkins 반영 전 실제 데이터·인증정보가 포함되지 않았는지 확인하고, 경량 평가 테스트를 실행한다. 서버 자원과 승인이 확보되면 기존 Job/취소·검색·추천·배포 보호 회귀를 수행한다. 이번 작업은 로컬 파일·Mock 검증까지이며 Commit/Push, Jenkins, Docker 실행·변경, SSH, DB 변경, 임베딩 재생성, 실제 Ollama 호출을 하지 않는다.
