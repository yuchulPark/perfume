# 향수 임베딩 저장·증분 갱신 1차

이번 변경은 코드·수동 SQL·오프라인 테스트 준비다. **운영 SQL 적용, 실제 임베딩 생성, SSH, Commit/Push, Jenkins 실행은 수행하지 않았다.** 아래 Ubuntu 명령은 코드 반영 및 DB 변경·배치 실행을 각각 승인한 뒤 운영자가 실행한다.

일반 웹 실행에서는 `perfume.ai.embedding.batch.enabled=false`이며 AI bean, DB 검사, Ollama 호출, 스케줄러, 임베딩 실행 API가 없다. 기존 Import·검색·React·Compose·Jenkins 파일을 변경하지 않는다. Windows의 `localhost:11434`를 사용하지 않는다.

## 저장 구조와 수동 마이그레이션

[SQL](sql/perfume-embeddings-v1.sql)은 `ai` 스키마와 `ai.perfume_embeddings` 한 테이블만 만든다. 이미 적용된 `public.vector` 0.8.7을 요구하며 extension을 생성하지 않는다.

| 컬럼 | 의미 |
| --- | --- |
| `perfume_id` | `public.perfumes.id` 참조; 삭제 연쇄 처리 없음 |
| `model_name`, `model_digest` | Ollama 태그와 설치된 모델의 전체 manifest SHA-256; Docker Image ID와 다름 |
| `text_version` | 현재 `perfume-v1`; 텍스트 생성 규칙 변경 시 새 버전 필요 |
| `source_text`, `source_sha256` | 실제 임베딩 입력과 UTF-8 SHA-256 |
| `embedding` | `public.vector(1024)`; NULL·0 벡터 금지 |
| `created_at`, `updated_at` | `timestamptz`; 갱신 시 최초 생성 시각 보존 |

기본 키는 `(perfume_id, model_name, model_digest, text_version)`다. 동일 모델/형식의 변경만 조건부 upsert하며 다른 모델의 기존 행은 보존한다. 검색용 HNSW/IVFFlat 인덱스는 2차에서 데이터와 검색 조건을 보고 정한다. `perfume_user`가 소유하고 PUBLIC에는 스키마/테이블 권한을 주지 않는다. 기존 역할·비밀번호·public 테이블 컬럼을 바꾸지 않는다.

SQL은 `docs/sql`에 있고 JPA entity로 매핑하지 않는다. Spring SQL init/Flyway/Liquibase에 등록하지 않는다. 기존 개발 설정의 `ddl-auto=update`도 AI 테이블을 생성하지 않는다. 수동 배치 모드는 다른 실행 옵션보다 우선하여 `ddl-auto=validate`, `spring.sql.init.mode=never`를 강제한다. AI 테이블이 없거나 vector 차원/기본 키가 다르면 배치가 종료된다.

마이그레이션은 기존 `ai` 스키마가 있으면 트랜잭션을 실패시킨다. 오류를 없애려고 기존 스키마나 데이터를 삭제하지 말고 별도 검토한다. 재적용도 기존 행을 초기화하지 않는다.

## 실제 데이터 매핑과 고정 텍스트

| 원본 | 포함 항목 |
| --- | --- |
| `public.perfumes`, `public.brands` | 이름, 브랜드 이름, 설명, 출시 연도, 저장된 리뷰 수 |
| `public.perfume_notes` → `public.notes` | TOP → MIDDLE → BASE; 각 층에서 position → 이름 순 |
| `public.perfume_unlayered_notes` → `public.notes` | UNLAYERED로 별도 표기; 층을 추정하지 않음 |
| `public.perfume_accords` → `public.accords` | position → 이름 순; 실제 percentage·score |
| `public.perfume_metrics` | metric_key 순; score, category, n_records, reliability, scale, derived_from, reviews_count, label, details |

`performance.longevity`, `performance.sillage`, `performance.projection`, `identity.rating` 등 저장된 metric_key를 그대로 사용한다. details가 SQL NULL이면 JSON 파싱을 생략하고 누락 정보로 처리한다. 실제 JSON의 객체 키는 재귀 정렬하며 배열 순서는 유지한다. 유효하지 않은 JSON은 `INVALID_SOURCE`로 처리한다. numeric/JSON 숫자는 소수 정밀도를 보존하고 불필요한 소수점 0만 정규화한다. null·빈 항목은 생략하고 공백·줄바꿈 형식은 고정한다. 외부 API나 생성 LLM으로 누락 정보를 채우지 않는다. 이미지 URL·provider 원본 payload·유사 향수·사용자 의견은 이번 텍스트 대상에서 제외한다.

한 향수의 본문과 관계 행은 **짧은 REPEATABLE READ / READ ONLY 트랜잭션**으로 읽는다. 원본에 수정 시각/변경 이벤트가 공통으로 없으므로 매 수동 실행에서 선택 범위의 텍스트를 다시 만들어 해시를 확인한다. 이것은 전체 **재임베딩**이 아니다. public의 관계 변경·브랜드/노트 이름 변경·평가 변경도 다음 실행에서 감지한다. 실시간 이벤트/자동 갱신은 이번 단계에 없다.

## 증분 처리와 실패 보호

1. 전용 JDBC 연결에서 PostgreSQL advisory lock을 얻는다. 다른 JVM/모델의 배치도 동시에 실행하지 않는다.
2. 시작 시 전체 해당 범위의 건수와 최대 ID를 기록하고 ID 기반 keyset 페이지로 읽는다. 실행 중 추가된 더 큰 ID는 다음 실행으로 넘긴다.
3. 모델명·digest·텍스트 버전이 같은 기존 행의 해시가 같으면 Ollama 임베딩 요청과 DB 쓰기를 건너뛴다.
4. 신규·변경 건만 한 향수씩 `/api/embed`에 요청한다. HTTP 중에는 DB 트랜잭션을 유지하지 않는다.
5. 1024차원, 숫자 타입, NaN/Infinity/float overflow, 0 벡터를 검사한다. `/api/tags`로 실행 전 및 임베딩 전후 실제 digest를 대조한다. 모델 다운로드·자동 재시도·원본 텍스트 잘라내기를 하지 않는다.
6. 생성 후 원본 해시를 다시 확인한다. 달라졌으면 `SOURCE_CHANGED`로 실패 처리하며 기존 정상 임베딩을 유지한다. 성공한 항목만 atomic upsert하여 개별 커밋한다.
7. 개별 데이터·Ollama 오류는 ID와 고정 오류 코드만 출력하고 다음 항목을 계속 처리한다. 어느 항목에서든 `DATABASE` 오류가 발생하면 해당 실패 건수와 검사 위치를 기록한 뒤 즉시 배치를 중단하고 세션을 닫는다. 앞서 성공적으로 커밋한 임베딩은 보존하며 확인 가능한 누적 건수/커서를 출력한다. 잠금 실패 등도 배치를 중단한다.

요청 제한/HTTP 오류, 연결 실패, 타임아웃, 잘못된 응답/차원, 모델 불일치 모두 실패이며 정상 벡터로 저장하지 않는다. 오류 body·SQL 진단·DB 비밀번호·API 키·원본 텍스트·벡터는 진단 로그에 출력하지 않는다. 읽기 실패는 해당 읽기 트랜잭션을 rollback한다. 개별 데이터 오류는 다음 항목으로 격리하지만 DB 읽기·쓰기의 `DATABASE` 오류는 즉시 배치를 중단한다. 저장은 단일 문장 autocommit으로 격리한다. 종료 시 advisory lock을 해제한다. 해제 실패 시 풀에 잠금 연결을 반환하지 않도록 실제 JDBC 연결을 abort한 뒤 닫는다.

원본을 잠가 Import를 막는 기능은 추가하지 않았다. 재확인 직후에 원본이 다시 바뀔 수 있으므로 다음 실행의 해시 검사가 최종 변경을 반영한다. 최초 적재/검증 중에는 Import·관리자 쓰기를 잠시 멈추면 기준 비교가 쉽고 웹 조회는 계속 사용할 수 있다.

| 옵션 (`perfume.ai.embedding.` 접두사) | 기본값 / 허용 범위 |
| --- | --- |
| `batch.enabled` | false; 실행 시 정확히 `--perfume.ai.embedding.batch.enabled=true` CLI 필수 |
| `model` | `qwen3-embedding:0.6b` |
| `model-digest` | 기본값 없음; `/api/tags`의 64자리 digest 또는 `sha256:` 포함 전체값 필수 |
| `base-url` | `http://ollama:11434`; 인증정보·쿼리·추가 경로 금지 |
| `batch.page-size` | 25 / 1–100; 메모리에 ID 페이지만 보관 |
| `batch.max-items` | 1000 / 1–10000; 변경 없음·실패를 포함한 검사 건수 제한 |
| `batch.after-id` | 0 / 0 이상; 시작 제외 ID |
| `batch.delay-ms` | 250 / 0–60000; 임베딩 시도 후에만 대기 |
| `connect-timeout-ms`, `read-timeout-ms` | 3000 / 1–30000, 180000 / 1–300000 |

결과의 `available`은 after-id 이후 시작 시 전체 건수, `target`은 max-items 제한을 반영한 목표, `processed`는 실제 검사 건수다. `new`, `changed`, `skipped`, `failed`, `lastId`, `upperId`, `hasMore`, `interrupted`도 출력한다. 조회 전 실패로 건수를 알 수 없으면 `unavailable`로 표시한다. 항목 실패/중단은 성공 종료로 보고하지 않는다. 검증 실패·일부 실패 시에도 이전 개별 커밋은 보존된다.

`hasMore=true`이면 **실패가 없는 실행**의 lastId를 다음 `batch.after-id`로 넘긴다. 전체 주기가 끝나면 다음 증분 검사 주기는 다시 0에서 시작한다. 실패가 있었거나 중단됐다면 이전 범위부터 다시 검사하여 실패 항목을 놓치지 않는다. 높은 lastId 뒤로 건너뛰면 앞선 실패·변경 항목은 검사되지 않는다. 성공한 항목은 해시 비교로 건너뛰므로 안전하게 재실행할 수 있다.

## Ubuntu 적용·실행 — 각각 별도 승인 후

먼저 코드를 검토하고 앱 반영을 별도로 승인한다. 이번 작업에서 Commit/Push나 배포를 하지 않았다. 최신 backend 이미지가 배포된 후 다음 순서를 진행한다. DB 이미지는 이미 pgvector 지원 상태를 유지하며 전환하지 않는다.

PuTTY 로그인 셸에서 기존 [작업 셸](../scripts/pgvector-maintenance-shell.sh)을 사용한다. 로그인 셸에 strict 옵션을 직접 켜지 않는다.

```bash
set +e
set +u
set +o pipefail
bash /srv/perfume/scripts/pgvector-maintenance-shell.sh || \
  printf '작업 셸이 종료됐습니다. 기존 로그인 셸은 유지됩니다.\n'
```

다음 명령은 `(perfume-maintenance)` 자식 셸에서 실행한다. helper의 FD 9가 기존 향수 Jenkins 잠금을 보유한다. 잠금 파일을 삭제하거나 FD를 다시 열지 않는다.

```bash
cd /srv/perfume
umask 077
unset COMPOSE_FILE COMPOSE_PROJECT_NAME COMPOSE_PROFILES COMPOSE_DRY_RUN PERFUME_DB_IMAGE
unset DB_NAME DB_USERNAME DB_PASSWORD DB_URL SCENTREV_API_KEY
compose() {
  docker compose --project-name perfume-dev --project-directory /srv/perfume \
    --env-file /srv/perfume/.env --file /srv/perfume/compose.yaml "$@"
}
DB_CONTAINER="$(compose ps --quiet db)"
BACKEND_CONTAINER="$(compose ps --quiet backend)"
[[ "$DB_CONTAINER" =~ ^[0-9a-f]+$ ]]
[[ "$BACKEND_CONTAINER" =~ ^[0-9a-f]+$ ]]
test "$(docker inspect --format '{{.State.Health.Status}}' "$DB_CONTAINER")" = healthy
test "$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/var/lib/postgresql/data"}}{{.Name}}{{end}}{{end}}' "$DB_CONTAINER")" = perfume-dev_pgdata
BACKEND_IMAGE_ID="$(docker inspect --format '{{.Image}}' "$BACKEND_CONTAINER")"
test "$(docker image inspect --format '{{.Id}}' perfume-dev-backend)" = "$BACKEND_IMAGE_ID"
compose exec -T db psql -X --no-password --host /var/run/postgresql \
  --username perfume_user --dbname perfume --set ON_ERROR_STOP=1 -c \
  "SELECT current_database(), current_user, current_setting('server_version_num'); SELECT extname, extversion FROM pg_extension WHERE extname='vector'; SELECT count(*) AS current_perfumes FROM public.perfumes;"
```

17.11/170011, vector 0.8.7, 현재 실제 향수 건수를 확인한다. **848개를 고정값으로 가정하지 않는다.** SQL 적용과 배치 실행은 별개 승인 대상이다. 현재 상태의 최신 백업을 먼저 만든다. 기존 [운영 백업 방식](pgvector-production-deployment.md)을 재사용하지만, 그 문서의 DB 이미지 전환·extension 생성 단계는 다시 실행하지 않는다.

```bash
mkdir -p backups/perfume-embedding
BACKUP_DIR="backups/perfume-embedding/$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -m 700 "$BACKUP_DIR"
compose exec -T db pg_dump --host /var/run/postgresql --username perfume_user \
  --dbname perfume --format custom > "$BACKUP_DIR/perfume.dump"
compose exec -T db pg_dumpall --host /var/run/postgresql --username perfume_user \
  -l perfume --roles-only --no-role-passwords > "$BACKUP_DIR/roles.sql"
compose exec -T db pg_restore --list < "$BACKUP_DIR/perfume.dump" > "$BACKUP_DIR/dump-toc.txt"
sha256sum "$BACKUP_DIR/perfume.dump" "$BACKUP_DIR/roles.sql" > "$BACKUP_DIR/SHA256SUMS"
sha256sum --check "$BACKUP_DIR/SHA256SUMS"
```

백업 파일은 Git/CI artifact에 넣지 않는다. 해시·목차 확인만으로 복원 가능성을 단정하지 말고, 이미 검증한 pgvector 지원 독립 리허설 DB에 최신 dump의 복원·원본 비교도 확인한다. 운영 DB/볼륨에는 복원하지 않는다.

**승인된 SQL 적용 — DB의 기존 데이터/볼륨에 복원·초기화 작업을 하지 않는다:**

```bash
compose exec -T db psql -X --no-password --host /var/run/postgresql \
  --username perfume_user --dbname perfume --set ON_ERROR_STOP=1 < docs/sql/perfume-embeddings-v1.sql
compose exec -T db psql -X --no-password --host /var/run/postgresql \
  --username perfume_user --dbname perfume --set ON_ERROR_STOP=1 -c \
  "SELECT count(*) AS current_perfumes FROM public.perfumes; SELECT count(*) FROM ai.perfume_embeddings;"
```

새 AI 테이블 0행과 public 향수 건수 보존을 확인한다. `ai`가 이미 존재하는 오류가 나면 이 SQL을 수정해 강행하거나 기존 테이블을 삭제하지 않는다. SQL 파일이 기존 Hibernate 초기화 경로로 들어가지 않도록 유지한다.

**읽기 전용 모델 확인 — backend 내부 네트워크로 접근한다:**

```bash
compose exec -T backend curl --fail --silent --show-error --max-time 10 http://ollama:11434/api/tags
read -rp 'qwen3-embedding:0.6b full digest from /api/tags: ' MODEL_DIGEST
[[ "$MODEL_DIGEST" =~ ^(sha256:)?[0-9a-f]{64}$ ]]
```

같은 이름의 설치된 모델의 `digest`를 그대로 넣는다. 12자리 표시 값, Docker 이미지 digest, 과거 모델 digest를 넣지 않는다. 모델이 없으면 중단하며 이 절차에서 pull하지 않는다.

**배치 실행 승인 후 먼저 최대 5건으로 확인한다:**

```bash
# Refuse if a prior one-off worker still exists; inspect it rather than start another.
test -z "$(docker ps --all --quiet --filter 'name=^/perfume-dev-embedding-batch$')"
compose run --rm --no-deps --pull never -T --name perfume-dev-embedding-batch \
  --entrypoint java backend -Xms128m -Xmx512m -jar /app/app.jar \
  --perfume.ai.embedding.batch.enabled=true \
  --perfume.ai.embedding.base-url=http://ollama:11434 \
  --perfume.ai.embedding.model=qwen3-embedding:0.6b \
  --perfume.ai.embedding.model-digest="$MODEL_DIGEST" \
  --perfume.ai.embedding.batch.page-size=25 \
  --perfume.ai.embedding.batch.max-items=5 \
  --perfume.ai.embedding.batch.after-id=0 \
  --scentrev.brand-import=false \
  --spring.jpa.hibernate.ddl-auto=validate --spring.sql.init.mode=never --spring.jpa.show-sql=false
```

기존 backend와 동일하게 배포된 **로컬 이미지**가 존재하고 Image ID가 일치하는지 위에서 확인한 다음 실행한다. `--build`나 pull을 추가하지 않는다. 별도 일회성 backend 컨테이너가 기존 perfume 내부 네트워크와 DB 환경값을 이용하고 종료하면 제거된다. HTTP 서버를 열지 않고 공개 포트를 추가하지 않는다. `--no-deps`로 기존 DB/backend/frontend/Ollama를 시작·재생성하지 않는다. DB 볼륨을 worker에 mount하지 않는다. Java heap은 최대 512MB이고 임베딩은 직렬 처리한다. Jenkins 배포 방식과 기존 backend 프로세스는 유지한다.

테스트 성공과 저장 행 확인 후 필요한 범위로 `batch.max-items`를 1000 등으로 바꾸어 같은 명령을 수동 재실행한다. 최초 5건의 내용이 같으면 그 5건은 skip한다. 1000개 이상이면 결과 커서로 범위를 나누며 무제한 처리 옵션은 없다.

```bash
compose exec -T db psql -X --no-password --host /var/run/postgresql \
  --username perfume_user --dbname perfume --set ON_ERROR_STOP=1 -c \
  "SELECT model_name, model_digest, text_version, count(*) AS embeddings, min(public.vector_dims(embedding)) AS min_dims, max(public.vector_dims(embedding)) AS max_dims FROM ai.perfume_embeddings GROUP BY model_name, model_digest, text_version; SELECT count(*) AS current_perfumes FROM public.perfumes;"
test "$(compose ps --quiet db)" = "$DB_CONTAINER"
test "$(compose ps --quiet backend)" = "$BACKEND_CONTAINER"
curl --fail --silent --show-error 'http://127.0.0.1:8088/api/perfumes?size=1' > /dev/null
exit
```

차원 1024, 대상 모델 식별 정보, 결과 건수, 기존 컨테이너 ID와 웹 조회를 확인한 뒤 Import를 재개한다. 실패로 자식 셸이 종료되면 다음 블록을 로그인 셸에 계속 붙여 넣지 않는다. 원인을 확인하고 helper를 다시 실행한 다음 상태·모델 digest·실행 범위를 다시 확인한다.

Ctrl+C/SSH 단절 뒤에는 worker가 실제로 종료됐다고 가정하지 않는다. 정확한 `perfume-dev-embedding-batch` 컨테이너의 상태와 `com.docker.compose.project=perfume-dev`, `service=backend`, `oneoff=True` label을 읽기 전용으로 확인한다. 실행 중이면 두 번째 배치를 시작하지 않는다. worker만 종료하는 별도 조치 후 재실행하고 운영 DB나 웹 컨테이너를 중지하지 않는다. DB advisory lock도 다른 실행의 중복 저장을 막는다. 성공한 임베딩을 삭제하여 재시작하지 않는다.

## 의존성 선택과 2차 범위

[Spring AI 공식 호환 표](https://github.com/spring-projects/spring-ai/tree/1.1.x)는 1.1.x에 Boot 3.5.x를 지정한다. 검토한 안정 버전은 [1.1.8](https://github.com/spring-projects/spring-ai/releases/tag/v1.1.8)이다. 이번 범위는 수동 upsert·원문 해시·모델 digest·엄격한 JSON 숫자 검사가 핵심이어서 기존 Spring `RestClient`, `JdbcTemplate`, PostgreSQL JDBC를 재사용한다. **pom.xml 변경/추가 AI starter는 없다.** Spring AI vector-store의 자동 schema 생성이나 모델 초기화를 새로 도입하지 않는다. `PerfumeEmbeddingClient`를 분리했으므로 2차에서 Spring AI adapter를 적용할 수 있다.

[Ollama embed API](https://docs.ollama.com/api/embed)의 `truncate=false`를 사용한다. 긴 텍스트가 모델 context를 넘으면 해당 향수만 실패시키고 기존 행을 유지한다. 요약/잘라내기/청킹은 이번 단계에서 자동으로 하지 않는다. [tags API](https://docs.ollama.com/api/tags)의 전체 digest로 동일 태그의 모델 교체도 식별한다.

2차 미구현: 사용자 자연어 query embedding, 코사인 유사도 검색, 필터/랭킹, Spring AI RAG advisor/prompt, Qwen3 생성 응답·근거 표시, React AI 추천 화면, 자동 worker/event 감지, chunking, 검색 인덱스, 관리자 HTTP 실행 API.

## 변경 파일과 검증

| 파일 | 역할 |
| --- | --- |
| `src/main/java/com/perfume/PerfumeApplication.java` | 수동 AI listener 등록 및 배치 종료 시 context 닫기 |
| `src/main/resources/application.properties` | 배치 기본 비활성 옵션 한 개 |
| `ai/embedding/EmbeddingModelIdentity.java`, `EmbeddingBatchOptions.java` | digest·형식 버전·URL·처리 한도 |
| `ai/embedding/PerfumeEmbeddingDocument.java`, `PerfumeEmbeddingText.java` | 원본 snapshot·고정 텍스트·SHA-256 |
| `ai/embedding/EmbeddingVector.java`, `EmbeddingFailure.java` | 벡터 검증·안전한 진단/누적 결과 |
| `ai/embedding/PerfumeEmbeddingClient.java`, `OllamaPerfumeEmbeddingClient.java` | Ollama API adapter |
| `ai/embedding/PerfumeEmbeddingStore.java`, `JdbcPerfumeEmbeddingStore.java` | 잠금·원본 읽기·AI 저장 |
| `ai/embedding/PerfumeEmbeddingBatch.java`, `PerfumeEmbeddingBatchRunner.java`, `PerfumeEmbeddingConfiguration.java` | 증분 흐름·명시적 수동 실행·조건부 bean |
| `src/test/java/com/perfume/ai/embedding/*Tests.java` | 텍스트·HTTP·JDBC·증분·비활성 보호 오프라인 테스트 |
| `docs/sql/perfume-embeddings-v1.sql`, 이 문서 | 승인 후 수동 적용과 실행 안내 |

Java 파일 표의 `ai/embedding/` 경로는 `src/main/java/com/perfume/` 기준이다. 테스트는 Ollama에 `MockRestServiceServer`/Mockito, DB에는 가짜 JDBC/메모리 저장소를 사용한다. 실제 HTTP, PostgreSQL 연결, SQL migration, Import는 실행하지 않는다. 기존 Maven 회귀 테스트와 배포 보호 테스트도 실행한다.

```bash
./mvnw -B -ntp test
python3 -m unittest discover -s tests/deployment -v
```

실제 Ubuntu에서의 SQL 적용 가능성·pgvector upsert·잠금 동시성·CPU 모델 응답 시간은 이 오프라인 검증과 구분하며, 승인 후 위 5건 실행으로 확인한다.

코드 검토 보완 후 로컬 검증 결과: Java 17/Maven 3.9.16 오프라인 `package` 성공. 594개 중 579개 통과, 기존 live/manual 15개 skipped, 실패/오류 0이며 AI 테스트 58개(추가 회귀 11개 포함)는 모두 통과했다. 배포 보호 테스트 56개와 React 기존 단위 테스트 8개도 통과했다. 문서 명령은 Bash 문법만 검사하며 실행하지 않았고 수동 SQL이 runtime JAR에 포함되지 않는 것도 확인했다. 로그는 `target/phase1-validation/embedding-review-package.log`, `embedding-review-deployment-tests.log`다. Windows `mvnw.cmd`의 기존 wrapper 초기화 오류 때문에 로컬에 준비된 같은 Maven 3.9.16과 캐시 저장소로 검사했으며 wrapper 파일은 수정하지 않았다.
