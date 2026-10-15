# 향수 RAG 2차 — 조회 전용 자연어 의미 검색

이번 변경은 로컬 코드·문서·Mock 테스트 준비다. 실제 DB/Ollama/ScentRev 호출, SQL 적용, SSH, 컨테이너 재시작, Commit/Push, Jenkins 실행은 하지 않았다. LLM 답변 생성과 React 추천 화면은 다음 단계다.

## 구성과 기존 코드 재사용

`POST /api/ai/perfumes/search` → 입력 검증 → 실행 슬롯 확보 → 기존 Ollama 클라이언트로 질문 임베딩 → 읽기 전용 pgvector Top-K → JSON 순서로 처리한다.

| 파일 (`src/main/java/com/perfume/ai/search/`) | 역할 |
| --- | --- |
| `PerfumeSemanticSearchController`, `PerfumeSemanticSearchRequest`, `PerfumeSemanticSearchResponse` | HTTP, 엄격한 입력 검증, 최소 응답 |
| `PerfumeSemanticSearchService` | 대기열 없는 동시 실행 제한, 전체 요청 기한, 취소·종료 |
| `OllamaQueryEmbeddingAdapter` | 기존 `PerfumeEmbeddingClient`/`OllamaPerfumeEmbeddingClient` 재사용, 안전한 오류 변환 |
| `JdbcPerfumeSemanticSearchRepository` | 바인딩된 SELECT, 읽기 전용 트랜잭션, DB 측 거리·정렬·LIMIT |
| `PerfumeSemanticSearchOptions`, `PerfumeSemanticSearchConfiguration` | 검색 전용 설정과 조건부 bean; 배치와 별도 실행 자원 |
| `SemanticSearchFailure`, `PerfumeSemanticSearchExceptionHandler` | 고정 오류 코드와 ProblemDetail |

기존 `EmbeddingModelIdentity`와 `EmbeddingVector`를 그대로 사용한다. `/api/tags`에서 임베딩 전후 **모델명과 전체 SHA-256 manifest digest**를 확인하고, `/api/embed`에는 기존과 같은 입력 방식(`truncate=false`, 1024차원, `keep_alive=5m`)을 사용한다. 별도 instruction 접두사를 붙이지 않는다. NaN/Infinity·0 벡터·차원 불일치를 거부한다. 모델 다운로드·자동 재시도·질문/벡터의 DB 저장은 없다. Spring AI나 새로운 Maven 의존성은 추가하지 않는다.

검색은 `JdbcPerfumeEmbeddingStore`나 배치 runner를 사용하지 않는다. 기존 ScentRev Import·Preview·skip-existing·증분 임베딩 구현을 수정하지 않았다. 기존 SQL·Compose·Jenkins·프론트엔드 파일도 변경하지 않았다.

## 기본 비활성화와 설정

`perfume.ai.search.enabled=false`가 기본값이다. 유효한 검색 요청에는 `503 / SEARCH_DISABLED`를 반환하며, 검색용 Ollama 클라이언트·실행기·repository bean을 만들거나 AI 테이블/모델을 검사하지 않는다. 기존 일반 웹 설정과 REST API는 유지한다.

활성화 시 `PerfumeApplication`의 초기 listener가 **컨텍스트 초기화 전에** `ddl-auto=validate`, `spring.sql.init.mode=never`, `spring.jpa.show-sql=false`를 강제한다. AI JPA entity나 자동 마이그레이션은 없다. 기존 테이블이 없으면 검색 시 안전한 오류를 반환하며 스키마를 생성하지 않는다. Import·Preview·임베딩 배치 활성 플래그와 함께 실행하면 시작을 거부한다.

아래 키에는 모두 `perfume.ai.search.` 접두사가 붙는다. 배치의 `perfume.ai.embedding.*` 설정과 별개다.

| 키 | 기본값 | 허용 범위/의미 |
| --- | --- | --- |
| `enabled` | false | 명시적으로 true일 때만 활성화 |
| `base-url` | `http://ollama:11434` | 내부 HTTP(S) 주소; 인증정보·쿼리·추가 경로 금지 |
| `model` | `qwen3-embedding:0.6b` | 저장된 임베딩과 동일한 모델명 |
| `model-digest` | 없음, 활성화 시 필수 | `sha256:` 포함 또는 생략한 **64자리 전체 digest** |
| `text-version` | `perfume-v1` | 기존 텍스트 정책; 현재 다른 버전은 거부 |
| `connect-timeout-ms` | 3000 | 1–10000 |
| `read-timeout-ms` | 30000 | 1–60000 |
| `request-timeout-ms` | 45000 | 1–120000; 질문 임베딩과 DB 조회를 합친 API 대기 기한 |
| `db-timeout-seconds` | 5 | 1–10; DB 트랜잭션/JDBC 쿼리 제한 |
| `max-concurrent` | 1 | 1–2; JVM별 제한, 대기열 0 |

슬롯이 없으면 즉시 429를 반환한다. 요청 기한 초과·인터럽트 시 작업 취소를 요청한다. HTTP I/O가 인터럽트를 무시하더라도 **기존 작업이 실제 종료할 때까지 슬롯을 계속 점유**하므로 취소된 작업이 무제한 누적되지 않는다. 애플리케이션 종료 시 실행기를 닫는다. 소켓 read timeout은 전체 처리 시간이 아니며, API가 먼저 504를 반환한 뒤 I/O가 종료될 수 있다. Ollama 서버 내부 계산의 즉시 중단은 보장하지 않는다. [Spring Web 6.2.19 timeout API](https://docs.spring.io/spring-framework/docs/6.2.19/javadoc-api/org/springframework/http/client/SimpleClientHttpRequestFactory.html)를 기준으로 설정한다.

CPU 환경의 과거 첫 응답 246초, 정상 요청 30–35초는 웹 검색에서도 주의할 관측값이다. 기본 30초 read/45초 전체 기한으로 실제 서버에서 성공할지는 미검증이다. 장시간 첫 로딩 시 504가 날 수 있고, 이때 자동 재시도나 모델 pull을 하지 않는다. 기존 배치의 300초 제한은 바꾸지 않는다. 추후 승인된 내부 테스트에서 응답 시간을 측정한 뒤 검색용 제한을 위 상한 내에서 조정한다.

현재 인증·사용자별 rate limit은 구현하지 않았다. 이 설정을 기존 공개 웹 backend에 켜지 말고, 아래처럼 **별도 localhost 검증 인스턴스**에서만 사용한다. JVM별 슬롯 제한은 여러 인스턴스 전체의 Ollama 부하를 제어하지 않는다. 임베딩 배치와 검색 검증도 동시에 실행하지 않는다.

## API 계약

```http
POST /api/ai/perfumes/search
Content-Type: application/json

{"query":"여름에 사용하기 좋은 시원한 남자 향수","topK":5}
```

질문은 공백만으로 구성되지 않은 최대 500 UTF-16 코드 단위(Java `String.length`) 문자열이다. 한글과 영문을 지원한다. `topK`는 생략 또는 null이면 5, 정수 1–20만 허용한다. 숫자 문자열·소수·알 수 없는 JSON 필드·잘못된 JSON은 400이다. 경계 앞뒤 공백을 임의로 제거하거나 질문을 잘라내지 않는다.

다음은 **가상의 응답 형식 예시**이며 실제 향수·점수는 DB 결과만 사용한다.

```json
{"results":[{"perfumeId":1,"name":"Example Perfume","brandName":"Example Brand","similarity":0.85}],"returnedCount":1}
```

일치하는 모델의 임베딩이 5개이고 JOIN 대상도 유효하면 topK=10이어도 최대 5개만 반환한다. 0개는 `200`과 `{"results":[],"returnedCount":0}`이다. 최소 유사도 필터는 없으며 코사인 유사도 범위는 -1에서 1이다. 점수는 확률/추천 확신도가 아니다.

오류는 기존 API와 같은 ProblemDetail 형태에 `code`를 추가한다. 예시:

```json
{"type":"about:blank","title":"Service Unavailable","status":503,"detail":"Semantic search is disabled.","instance":"/api/ai/perfumes/search","code":"SEARCH_DISABLED"}
```

| HTTP | code | 의미 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | 입력 오류 |
| 503 | `SEARCH_DISABLED` / `SEARCH_INTERRUPTED` | 비활성화 / 실행 중단 |
| 429 | `SEARCH_BUSY` | 슬롯 부족; 작업 접수·대기 없음 |
| 504 | `SEARCH_TIMEOUT` / `OLLAMA_TIMEOUT` / `DATABASE_TIMEOUT` | 전체 기한 / Ollama I/O / DB 제한 |
| 503 | `OLLAMA_UNAVAILABLE` / `MODEL_MISMATCH` / `DATABASE_UNAVAILABLE` | 연결 또는 전체 모델 식별 불일치 |
| 502 | `OLLAMA_HTTP_ERROR` / `OLLAMA_INVALID_RESPONSE` / `INVALID_EMBEDDING` | HTTP 거부 / 응답 구조 / 벡터 검증 오류 |
| 500 | `PGVECTOR_SEARCH_ERROR` / `SEARCH_FAILED` | SQL·pgvector 오류 / 기타 실패 |

장애를 빈 검색 결과로 바꾸지 않는다. 응답에는 벡터·source_text·SQL 파라미터·내부 예외 원인이 없다. 요청 DTO의 `toString()`도 질문을 포함하지 않는다. 자체 로그에 질문·벡터·비밀번호·API 키·Ollama 응답 본문을 기록하지 않는다. JDBC 파라미터 로깅을 피하도록 직접 PreparedStatement를 사용한다. 외부 HTTP body 로거·JDBC 프록시·드라이버 TRACE 로깅도 검증 환경에서 켜지 않는다. 기존 Ollama 클라이언트는 안전한 실패 코드·상태·경과 시간·예외 **클래스명**만 기록한다.

## 검색 SQL과 모델 격리

실제 실행 SQL은 다음과 같다. `?`는 순서대로 벡터 문자열, 모델명, 전체 digest, 텍스트 버전, 검증된 topK다. 사용자 입력을 SQL 문자열에 연결하지 않는다.

```sql
SELECT perfume_id, name, brand_name, 1 - cosine_distance AS similarity
FROM (
    SELECT e.perfume_id, p.name, b.name AS brand_name,
        e.embedding OPERATOR(public.<=>) CAST(? AS public.vector(1024)) AS cosine_distance
    FROM ai.perfume_embeddings e
    JOIN public.perfumes p ON p.id = e.perfume_id
    JOIN public.brands b ON b.id = p.brand_id
    WHERE e.model_name = ? AND e.model_digest = ? AND e.text_version = ?
) matched
ORDER BY cosine_distance ASC, perfume_id ASC
LIMIT ?;
```

DB에서 거리 계산과 Top-K를 처리한다. Java에는 결과 ID·이름·브랜드·점수만 가져온다. SQL은 `public`에 설치된 vector 타입/연산자를 명시한다. 별도 JDBC 트랜잭션 매니저가 `REQUIRES_NEW`, readOnly, `SET TRANSACTION READ ONLY`를 적용하며 기존 JPA 트랜잭션 설정은 변경하지 않는다. ResultSet·Statement·트랜잭션·연결은 성공/실패 모두 정리한다.

모델이 달라 저장 행이 없으면 빈 결과다. 다른 모델/과거 버전 행으로 대체하지 않는다. 기존 5개 행의 `(model_name, model_digest, text_version)`이 설정과 일치하고 벡터가 1024차원이면 그대로 재사용한다. 검색이 source_sha256을 갱신하거나 재임베딩하지는 않는다. 향수 변경 반영은 기존 증분 배치를 별도 승인하여 실행한다.

## 향후 Ubuntu에서 기존 5개로 검증하기 — 이번에 실행하지 않음

코드 반영·실제 API 호출은 별도 승인 후 진행한다. 기존 [배치 문서](perfume-embedding-batch.md)의 PuTTY 자식 작업 셸과 프로젝트/이미지 확인 방법을 재사용한다. **SQL 적용·배치 실행 단계는 다시 수행하지 않는다.** 로그인 셸에 `set -euo pipefail`을 직접 실행하지 않는다. 기존 작업 셸의 Jenkins 잠금이 유지되는 상태에서 검증한다.

1. 이미 새 코드가 포함된 backend 이미지가 준비됐는지 확인한다. 아래 설정은 운영 `.env`를 수정하지 않는다.

```bash
cd /srv/perfume
unset COMPOSE_FILE COMPOSE_PROJECT_NAME COMPOSE_PROFILES COMPOSE_DRY_RUN PERFUME_DB_IMAGE
unset DB_NAME DB_USERNAME DB_PASSWORD DB_URL SCENTREV_API_KEY
compose() {
  docker compose --project-name perfume-dev --project-directory /srv/perfume \
    --env-file /srv/perfume/.env --file /srv/perfume/compose.yaml "$@"
}
BACKEND_CONTAINER="$(compose ps --quiet backend)"
DB_CONTAINER="$(compose ps --quiet db)"
[[ "$BACKEND_CONTAINER" =~ ^[0-9a-f]+$ && "$DB_CONTAINER" =~ ^[0-9a-f]+$ ]]
BACKEND_IMAGE_ID="$(docker inspect --format '{{.Image}}' "$BACKEND_CONTAINER")"
test "$(docker image inspect --format '{{.Id}}' perfume-dev-backend)" = "$BACKEND_IMAGE_ID"
compose exec -T backend curl --fail --silent --show-error --max-time 10 http://ollama:11434/api/tags
read -rp 'Existing qwen3-embedding:0.6b full manifest digest: ' MODEL_DIGEST
[[ "$MODEL_DIGEST" =~ ^(sha256:)?[0-9a-f]{64}$ ]]
MODEL_DIGEST="sha256:${MODEL_DIGEST#sha256:}"
```

2. AI 테이블이 이미 존재하는 상태에서 다음 읽기 전용 조회로 모델별 건수·차원·현재 향수 건수를 확인한다. 설치 모델과 DB의 전체 digest가 일치하지 않으면 중단한다. 5개나 848개를 고정된 DB 검사 조건으로 사용하지 않는다.

```bash
compose exec -T db psql -X --no-password --host /var/run/postgresql \
  --username perfume_user --dbname perfume --set ON_ERROR_STOP=1 <<'SQL'
BEGIN READ ONLY;
SELECT count(*) AS current_perfumes FROM public.perfumes;
SELECT model_name, model_digest, text_version, count(*) AS embeddings,
       min(public.vector_dims(embedding)) AS min_dimensions,
       max(public.vector_dims(embedding)) AS max_dimensions
FROM ai.perfume_embeddings
GROUP BY model_name, model_digest, text_version ORDER BY 1, 2, 3;
COMMIT;
SQL
```

3. 배치를 실행하지 않고 검증 전 ID·해시·시각만 저장한다. 벡터와 원문은 출력하지 않는다. 검증 후에도 같은 함수를 사용한다.

```bash
umask 077
AUDIT_DIR="backups/semantic-search-validation/$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -m 700 -p "$AUDIT_DIR"
embedding_manifest() {
  compose exec -T db psql -X --no-password --host /var/run/postgresql \
    --username perfume_user --dbname perfume --set ON_ERROR_STOP=1 \
    --set model_digest="$MODEL_DIGEST" --tuples-only --no-align <<'SQL'
BEGIN READ ONLY;
SELECT perfume_id, model_name, model_digest, text_version, source_sha256, created_at, updated_at
FROM ai.perfume_embeddings
WHERE model_name='qwen3-embedding:0.6b' AND model_digest=:'model_digest' AND text_version='perfume-v1'
ORDER BY perfume_id;
COMMIT;
SQL
}
embedding_manifest > "$AUDIT_DIR/before.txt"
```

4. 별도 검증 컨테이너를 포그라운드에서 실행한다. Ollama는 Docker 내부 주소를 사용한다. 기존 backend/DB/Ollama를 재생성하지 않고, 호스트 `127.0.0.1:18081`에만 검증 웹 포트를 연다. 이미지 pull/build나 SQL 적용은 없다.

```bash
test -z "$(docker ps --all --quiet --filter 'name=^/perfume-dev-semantic-search-test$')"
SEARCH_EXIT=0
compose run --rm --no-deps --pull never -T --name perfume-dev-semantic-search-test \
  --publish 127.0.0.1:18081:18081 --entrypoint java backend \
  -Xms128m -Xmx512m -jar /app/app.jar \
  --server.address=0.0.0.0 --server.port=18081 \
  --perfume.ai.search.enabled=true \
  --perfume.ai.search.base-url=http://ollama:11434 \
  --perfume.ai.search.model=qwen3-embedding:0.6b \
  --perfume.ai.search.model-digest="$MODEL_DIGEST" \
  --perfume.ai.search.text-version=perfume-v1 \
  --scentrev.brand-import=false --scentrev.brand-preview=false \
  --perfume.ai.embedding.batch.enabled=false || SEARCH_EXIT=$?
printf '검증 인스턴스 종료 코드: %s\n' "$SEARCH_EXIT"
```

실행 명령의 실패/Ctrl+C를 명시적으로 처리해 strict 작업 셸과 잠금을 검증 후 비교까지 유지한다. 종료 코드 출력은 검색 성공 판정이 아니다. 시작 실패라면 API 검증을 진행하지 말고 안전한 오류 코드를 확인한다.

5. 같은 Ubuntu의 다른 셸에서 순차 요청한다. Windows `localhost:11434`에 Ollama가 있다고 가정하지 않는다. 이 요청은 **실제 Ollama 계산**을 유발하므로 현재 작업에서는 실행하지 않았다.

```bash
curl --silent --show-error --max-time 50 -H 'Content-Type: application/json' \
  --data '{"query":"여름에 사용하기 좋은 시원한 향수","topK":5}' \
  http://127.0.0.1:18081/api/ai/perfumes/search
curl --silent --show-error --max-time 50 -H 'Content-Type: application/json' \
  --data '{"query":"fresh citrus fragrance for summer","topK":10}' \
  http://127.0.0.1:18081/api/ai/perfumes/search
```

대상 임베딩이 여전히 5개라면 각각 최대 5개, 결과 ID는 manifest의 실제 ID에 속하고, 점수 내림차순/동점 ID 오름차순이어야 한다. 질문이 달라 순위가 달라지는 것은 정상이다. 성공 여부와 실제 지연을 측정하고, 504/502를 정상 결과로 기록하지 않는다. 0개 모델 필터 테스트는 DB 행 삭제·모델 교체 없이 Mock에서 검증했다. 실제 환경에서 확인하려고 digest를 임의로 바꾸면 설치 모델 불일치로 503이 먼저 발생할 수 있다.

검증 후 포그라운드 컨테이너만 Ctrl+C로 종료한다. 원래 작업 셸에서 `embedding_manifest > "$AUDIT_DIR/after.txt"`, `cmp "$AUDIT_DIR/before.txt" "$AUDIT_DIR/after.txt"`로 기존 행 보존을 비교하고 기존 DB 컨테이너 ID·웹사이트 상태도 확인한다. 다른 임베딩 배치를 동시에 실행했다면 그 변경과 구분해야 한다. 기존 컨테이너를 stop/restart하거나 볼륨을 정리하지 않는다. 작업 셸을 종료하면 기존 helper가 배포 잠금을 해제한다.

## 10,000개 이상 확장 검토 — 제안만, 적용하지 않음

현재 인덱스를 추가하지 않아 소규모 데이터의 정확 검색을 유지한다. 10,000개라는 숫자만으로 ANN을 필수로 가정하지 않고 DB 실행 시간·CPU·recall·Ollama 시간부터 각각 측정한다. 아래는 향후 검토할 SQL이며 자동 마이그레이션/시작 경로에 등록하지 않았다.

```sql
-- 향후 별도 검토·승인 대상. 아래 두 ANN 방식을 동시에 적용하지 않는다.
CREATE INDEX CONCURRENTLY perfume_embeddings_hnsw_cosine
ON ai.perfume_embeddings USING hnsw (embedding public.vector_cosine_ops);
-- 대안: 충분한 데이터 적재 후 lists/probes를 실측하여 결정한다.
CREATE INDEX CONCURRENTLY perfume_embeddings_ivfflat_cosine
ON ai.perfume_embeddings USING ivfflat (embedding public.vector_cosine_ops) WITH (lists = 10);
-- 모델 필터의 별도 B-tree도 데이터 분포와 기존 PK 사용 계획을 먼저 비교한다.
CREATE INDEX CONCURRENTLY perfume_embeddings_model_identity
ON ai.perfume_embeddings (model_name, model_digest, text_version);
```

HNSW는 IVFFlat보다 메모리·빌드 비용이 크고 학습 단계가 없다. IVFFlat은 데이터 적재 후 목록/탐색 수를 조정한다. 둘 다 근사 검색이므로 정확 검색과 결과/recall을 비교해야 한다. 1024차원은 vector ANN 인덱스의 지원 범위다. 모델 필터가 ANN 후보 선택 뒤 적용되면 결과 수가 topK보다 적을 수 있어 iterative scan·모델별 인덱스/파티셔닝을 별도 검토한다. [pgvector 0.8.7 공식 문서](https://github.com/pgvector/pgvector/blob/v0.8.7/README.md)의 indexing/filtering 설명을 기준으로 한다.

모델별 partial index는 바인딩된 모델/digest 조건을 쓰는 실제 prepared query에서 선택되는지 확인해야 한다. PostgreSQL은 계획 시 쿼리가 partial predicate를 만족한다고 증명할 수 있어야 한다. [PostgreSQL 17 partial index 문서](https://www.postgresql.org/docs/17/indexes-partial.html)를 참고한다. 현재의 동점 ID 정렬·JOIN도 플랜에 영향을 주므로 인덱스 사용을 보장하지 않는다. ANN 후보가 잘렸을 때 전역적인 정확 Top-K나 동점 집합의 완전성도 보장하지 않는다.

향후 복제 DB에서 현재 모델의 저장 벡터 1개를 입력으로 삼아 **벡터 본문 출력 없이** 초기 플랜을 확인할 수 있다. `psql`의 `model_digest` 변수를 먼저 설정하고 실행하며 실제 질문 바인딩 쿼리의 플랜/지연도 별도 비교한다. `EXPLAIN ANALYZE`는 SELECT를 실제 실행하므로 부하를 측정할 환경에서만 수행한다.

```sql
BEGIN READ ONLY;
EXPLAIN (ANALYZE, BUFFERS)
SELECT e.perfume_id, p.name, b.name AS brand_name,
       e.embedding OPERATOR(public.<=>) (
           SELECT reference.embedding FROM ai.perfume_embeddings reference
           WHERE reference.model_name='qwen3-embedding:0.6b'
             AND reference.model_digest=:'model_digest' AND reference.text_version='perfume-v1'
           ORDER BY reference.perfume_id LIMIT 1
       ) AS cosine_distance
FROM ai.perfume_embeddings e
JOIN public.perfumes p ON p.id=e.perfume_id
JOIN public.brands b ON b.id=p.brand_id
WHERE e.model_name='qwen3-embedding:0.6b' AND e.model_digest=:'model_digest' AND e.text_version='perfume-v1'
ORDER BY cosine_distance ASC, e.perfume_id ASC LIMIT 5;
COMMIT;
```

비교 항목은 actual rows, 필터로 제외된 행, Sort/Index Scan, buffers, 실행 시간, 정확 검색 대비 recall, 동점 정렬이다. JVM에 전체 벡터를 가져오는 방식은 사용하지 않는다. 이번에는 인덱스 생성·EXPLAIN·운영 SQL을 실행하지 않았다.

## 로컬 검증 범위

검색 전용 테스트는 `src/test/java/com/perfume/ai/search/`의 `PerfumeSemanticSearchApiTests`, `PerfumeSemanticSearchDisabledApiTests`, `PerfumeSemanticSearchServiceTests`, `PerfumeSemanticSearchConfigurationTests`, `OllamaQueryEmbeddingAdapterTests`, `JdbcPerfumeSemanticSearchRepositoryTests` 6개 파일에 있다. Mock MVC, MockRestServiceServer, Mock JDBC를 사용하며 실제 PostgreSQL/Ollama에 연결하지 않는다. 모델별 필터/정렬 테스트는 SQL 계약·바인딩과 가짜 JDBC 행을 검증한다. 실제 pgvector 연산·드라이버 호환성·실행 계획·서버 응답 시간은 위의 승인된 통합 검증 전까지 미검증이다.

2026-10-15 로컬 검증에서 Java 17 / Maven 3.9.16 오프라인 `package`가 성공했다. 총 824개 중 809개 통과, 기존 live/manual 15개 skipped, 실패·오류 0이다. 검색 신규 84개, 기존 임베딩 72개, Preview 81개, skip-existing 51개, 기존 단일 브랜드 Import 12개가 모두 통과했다. 별도 배포 보호 테스트 56개와 React 단위 테스트 8개도 통과했다. 배포 테스트의 Docker/Git/SSH 명령은 가짜 실행기로 대체하며 실제 배포를 하지 않는다. 수동 SQL·문서는 runtime JAR에 포함되지 않는 것도 검사했다.

Windows `mvnw.cmd`의 기존 초기화 문제 때문에 이미 준비된 Maven 및 로컬 의존성 캐시로 실행했으며 wrapper/의존성 파일은 수정하지 않았다. 결과 로그는 `target/phase1-validation/semantic-search-focused.log`, `semantic-search-package.log`, `semantic-search-deployment-tests.log`다. Docker 이미지 빌드·실제 서버 통합 검증은 이번에 수행하지 않았다.

인증·사용자별 rate limit·공개 활성화·LLM 답변/프롬프트·Spring AI RAG Advisor·React 추천 화면·채팅/검색 기록·재임베딩·스키마 변경은 이번 단계에 포함하지 않는다.
