# RAG 3차 번호 선택과 검증 실패 진단

로컬 Java 코드·Mock 테스트·문서만 준비했다. 실제 Ollama/PostgreSQL/ScentRev 호출, SSH, Docker 컨테이너 작업, 모델 다운로드, DB 변경, Commit/Push, Jenkins 실행은 수행하지 않았다. Java 17 / Spring Boot 3.5.16, 기존 RAG 1–2차·Import·Preview·skip-existing·SQL·Compose·Jenkins·React는 변경하지 않았다.

## 확인된 사실과 미확정 원인

운영자 제공 로그의 API 응답은 약 197.04초 후 502/GENERATION_FAILED다. 검색·근거 준비를 통과했고 Ollama HTTP 200, done=true, doneReason=stop, eval_count=72, thinkingChars=0을 받았으며 최종 실패 단계가 validation이었다. load 약 65.58초, prompt 평가 약 22.34초, 생성 약 105.27초(0.684 tokens/s)가 기록됐다. **이 요청의 최종 오류는 Java의 생성 결과 검증 실패이며 HTTP 통신 실패나 LLM timeout으로 분류할 근거가 없다.** stop은 전송/생성 종료이며 내부 JSON·근거 검증 성공을 뜻하지 않는다.

이전 `validateAndRender()`는 아래 조건을 모두 같은 GENERATION_FAILED로 처리했다. 실제 생성 본문을 보관하지 않았으므로 어느 조건을 위반했는지는 **미확정**이다. 띄어쓰기 문제라고 단정하지 않는다.

| 이전 문장 복사 방식의 검사 | 가능한 실패 조건 |
| --- | --- |
| 입력 길이·JSON 파싱 | null, 8192자 초과, 문법 오류, 중복 키, JSON 뒤 추가 데이터 |
| answer 스키마 | object 아님, answer 누락/잘못된 타입, 여분 필드 |
| 줄 수·중복 | 기본 최대 2줄/2개 모드 최대 4줄 초과, 빈 결과, 같은 줄 반복 |
| 문장 exact match | 내부 공백·조사·구두점·인용부호·출처 변경, 바꿔쓰기, 새 주장/향수. 줄 앞뒤 공백은 기존 strip으로 허용했음 |
| identity-first·근거 쌍 | identity보다 사실이 먼저, 다른 출처의 사실 연결, identity만 반환, 후보당 사실이 하나가 아님 |
| 후보 수 | 허용된 후보 수보다 많이 설명 |

새 구현은 문장 복사 과제를 없애며, 기존 응답을 복원하거나 재현했다고 주장하지 않는다. 새 실패의 고정 원인 코드는 이후 요청부터 확인할 수 있다.

## LLM 요청과 응답

기존 system / USER_QUERY / EVIDENCE_DATA의 3개 메시지를 유지한다. 가상 EVIDENCE_DATA 예시는 다음과 같다. 실제 DB 원문/프롬프트는 출력하거나 저장하지 않는다.

```json
{"candidates":[{"source":1,"name":"Example Perfume","brand":"Example Brand","facts":[{"factId":1,"text":"저장된 탑 노트는 «Bergamot»입니다. [1]"},{"factId":2,"text":"이 근거에는 지속력 평가가 없어 지속력을 확정할 수 없습니다. [1]"}]}]}
```

LLM 응답은 번호만 포함한다.

```json
{"picks":[{"source":1,"factId":2}]}
```

2개 모드 예시:

```json
{"picks":[{"source":1,"factId":2},{"source":2,"factId":1}]}
```

- source는 **검색 결과의 원래 1-based 출처 번호**이며 perfume_id가 아니다. LLM 후보를 줄여도 다시 배정하지 않는다.
- factId는 해당 source 안의 선택지 순서 1/2다. 같은 원본·질문·순위·예산에서 결정적으로 배정한다. 예산으로 둘째 선택지가 빠지면 1만 존재한다. 다른 질문/요청에 번호의 의미를 재사용하거나 캐시하지 않는다.
- 식별자는 `(source,factId)` 쌍이다. source 1과 2 모두 factId=1이 있을 수 있지만 각자의 문장만 가리킨다. source 1의 2번이 있어도 source 2에 2번이 없으면 UNKNOWN_FACT_ID다. 전역 factId 목록에서 검색하지 않는다.
- LLM에는 이름/브랜드와 사실·불확실성의 의미만 전달한다. 전체 identity, perfumeId, snapshotAt은 서버에서 보관한다. 이름/브랜드를 모델이 다시 생성할 필요가 없고, 모델이 응답에 붙이면 여분 필드로 거부한다.

서버는 현재 요청의 허용 목록에서 source를 찾고, 그 source의 factId를 검사한 뒤 **정확한 identity 원문 → 정확한 fact 원문**을 붙인다. INTRO, 스냅샷 최신성·검색 유사도의 한계, 생략 안내도 기존 Java 문구를 그대로 붙인다. LLM의 자연어 문자열을 답변에 직접 사용하는 경로는 없다. 전체 검증이 끝나기 전에 일부 문장을 반환하지 않는다.

공개 API는 그대로 `answer`, `sources`, `returnedCount`다. picks/factId/validationReason은 공개 응답에 추가하지 않는다. Top-K 5에서 실제 검색 결과가 5개면 sources/returnedCount는 5이고, similarity도 기존 검색 결과 값이다. LLM은 기본 최대 1개, 옵션으로 최대 2개만 설명한다. 선택은 검색 순서를 따라야 하며 역순을 정렬해서 보정하지 않는다. 2개 모드에서 1개를 선택하는 것은 허용한다.

## 검증과 안전한 실패 분류

외부 HTTP/code/detail은 기존 **502 / GENERATION_FAILED / 고정 detail**을 유지한다. 아래 enum은 내부 로그 전용이며 예외에 잘못된 값이나 provider 메시지를 보관하지 않는다. 여러 문제가 있으면 먼저 발견한 검증 조건 하나를 기록한다.

| validationReason | 조건 |
| --- | --- |
| INVALID_JSON | null/빈 생성 본문, JSON 문법 오류·중복 키·뒤따르는 JSON/텍스트·불완전 JSON |
| INVALID_SCHEMA | 최상위 또는 picks 원소가 object가 아님 |
| MISSING_REQUIRED_FIELD | picks 또는 source/factId 누락 |
| UNEXPECTED_FIELD | 최상위/선택 object의 여분 필드. 기존 answer 형식도 거부 |
| INVALID_FIELD_TYPE | picks가 배열이 아님, source/factId가 정수가 아님. 문자열·소수·지수 표기·boolean·null 변환 금지 |
| INVALID_IDENTIFIER | 0·음수 또는 Java int 범위를 벗어난 번호 |
| UNKNOWN_SOURCE | 실제 LLM 근거로 전달한 후보에 없는 source. 검색됐어도 생략된 후보는 선택 불가 |
| UNKNOWN_FACT_ID | 해당 source 안에 없는 factId |
| DUPLICATE_SOURCE | 같은 source를 두 번 선택 |
| INVALID_SELECTION_COUNT | 빈 배열, 간결/2개 모드의 상한 초과 |
| INVALID_SELECTION_ORDER | 선택한 source가 검색 순서의 역순 |
| EVIDENCE_MISMATCH | 서버 허용 목록의 identity/사실 쌍 구조 불일치. 정상 근거로 위장하지 않음 |
| RESPONSE_TOO_LONG | 생성 본문이 기존 8192자 제한 초과 |

로그 예시(잘못된 번호 자체는 출력하지 않음):

```text
event=validation_failed, stage=validation, validationReason=UNKNOWN_FACT_ID, optimizationMode=compact, contextSources=1, allowedFacts=2, selectedCount=1, elapsedMs=0
```

allowedFacts는 identity를 제외한 선택지 수다. selectedCount는 파싱·배열 검사 전 실패하면 `unavailable`이며 0으로 추정하지 않는다. elapsedMs는 검증 단계 시간이고 API 전체 시간은 기존 Service 로그에서 별도로 본다. 고정 코드·모드와 숫자만 기록하며 원문·전체 프롬프트·답변·실패 필드명/값·예외 메시지/스택·비밀번호/API Key는 출력하지 않는다.

Ollama 응답 자체가 length/미완료/timeout이면 기존 Client/Service에서 LLM_INVALID_RESPONSE 또는 LLM_TIMEOUT 등으로 먼저 실패한다. 이 경우 validation 단계에 도달한 것으로 꾸미거나 validationReason을 만들어내지 않는다. 기존 최종 응답의 load/prompt/eval duration, 토큰 수, TPS 진단은 유지한다.

## 무결성과 CPU 정책

기존 read-only 근거 조회, 원본 source_text SHA-256, 모델명·전체 digest·text_version, 향수 이름/브랜드, 실제 검색 ID 대조는 그대로다. 모든 검색 결과의 스냅샷을 기존처럼 검증하고 현재 요청의 전달된 선택지만 사용한다. 신규 DB 조회나 INSERT/UPDATE/DELETE가 없다. 질문 검색용 임베딩 1회와 향수 임베딩 재생성은 다르며 후자는 실행하지 않는다. ScentRev를 호출하거나 추가 정보로 근거를 보충하지 않는다.

질문한 평가 우선순위, 누락 지속력/계절의 불확실성, 위험 provider 지시문 필터, 후보별 사실 선택지 최대 2개는 유지한다. source가 유효하다는 이유로 모델의 새 사실·향수·유사도·단위를 신뢰하지 않는다. 자동 재시도·JSON 수선·누락 필드 보완·역순 정렬·legacy answer fallback은 없다.

`/api/chat`, stream=false, think=false, format=json, temperature=0.2, num_ctx=4096, num_thread=3, keep_alive=5m 및 생성 모델 `qwen3:4b-instruct`를 유지한다. 새 JSON Schema API 옵션이나 라이브러리는 도입하지 않았다. 실제 설치 버전의 추가 호환성을 확인하지 않고 format을 임의 변경하지 않는다.

출력 기본값은 **256 → 128토큰**으로 줄였다. 예상 선택 JSON은 1개 35바이트/2개 59바이트의 ASCII이고, Top-K 최대 20의 두 자리 source를 사용하는 2개 예시는 61바이트다. 전체 한국어 문장 복사보다 작아 기본 128을 선택했다. 정확한 Qwen tokenizer 측정이나 완성 보장은 아니며 whitespace/잘못된 생성으로 length가 발생하면 계속 실패한다. 허용 범위 128–768, 192/256/512 명시 설정은 유지한다. 입력 기본 2560바이트·상한 2816은 출력 감소와 독립이며 자동 증가하지 않는다.

동시 실행 기본 1·최대 2·대기열 0, read/생성/전체 timeout, 두 AI 기본 플래그 false를 그대로 유지한다. 출력량 감소가 모델 로딩·runner 대기·토큰당 CPU 속도를 개선한다는 보장은 없다. 원문 요청의 0.684 TPS나 과거 가상 요청의 2.58 TPS를 새 요청의 확정 속도로 사용하지 않는다.

## 변경 전후 로컬 측정

같은 `RecommendationTestSupport.text(1/2)` 가상 스냅샷·질문 `여름에 지속력이 긴 향수`와 ObjectMapper로 변경 전 builder를 먼저 실행했다. 출력은 각 후보의 실제 허용 문장을 선택한 완성 JSON과 번호 JSON을 비교했다. 측정 JSON에는 바이트 수만 저장한다. 실제 운영 요청의 971바이트와 다른 fixture이므로 이를 새 운영 입력 길이로 환산하지 않는다.

| 항목 | 기존 문장 방식 | 번호 선택 방식 |
| --- | --- | --- |
| System Prompt 바이트 | 413 | 311 |
| 후보 1개 입력 content 합 | 753 | 683 |
| 후보 2개 입력 content 합(명시적 2개 모드) | 1032 | 979 |
| 후보 1개 LLM 출력 JSON | 172바이트 | 35바이트 |
| 후보 2개 LLM 출력 JSON | 333바이트 | 59바이트 |
| LLM 기본 출력 상한 | 256토큰 | 128토큰 |
| 모델이 생성할 내용 | identity+한국어 근거 | source/factId와 JSON 필드 |
| 한국어 문장 조립 | LLM 복사·서버 exact match | Java의 검증된 원문 조회 |
| source·근거·전체 모델 digest | 엄격 검증 | 유지 |
| 공개 API | answer/sources/returnedCount | 유지 |
| 검색/추천 기본 활성화 | false | false |

입력 바이트는 3개 메시지의 content 합이며 HTTP body나 모델 template를 포함하지 않는다. 로그는 `target/phase1-validation/rag-selection-baseline-test.log`, 길이는 `rag-selection-before.json`, `rag-selection-after.json`에 있다. 새 regression test가 같은 fixture의 크기를 검사한다. **CPU 개선율·실제 JSON 완성률은 미측정**이다.

## 이후 Ubuntu localhost 통합 검증 — 이번에 실행하지 않음

1. 별도 승인 후 [기존 추천 실행 문서](perfume-rag-recommendation.md)의 「Ubuntu localhost 전용 검증」 절차를 사용한다. 새 코드가 포함된 검토된 backend 이미지가 준비되어 있어야 하며 기존 웹서비스에는 AI를 활성화하지 않는다. 현재 작업에서는 이미지 빌드·배포도 하지 않았다.
2. PuTTY 로그인 셸에서 strict 옵션을 직접 켜거나 helper를 source하지 않는다. 기존 `bash /srv/perfume/scripts/pgvector-maintenance-shell.sh`로 자식 작업 셸에 들어간다. helper의 FD 9가 perfume 배포 잠금을 보유한다. 다른 Import/AI 요청을 겹치지 않고 Compose의 프로젝트/서비스 label과 DB 볼륨·기존 컨테이너 ID를 기존 문서대로 확인한다.
3. 기존 두 모델의 전체 digest와 현재 embedding 건수/ID/차원/manifest를 기존 read-only 명령으로 기록한다. 지금 5개라는 관측은 시작 시점에 다시 확인하되 개수를 고정 assertion으로 삼지 않는다. 모델 다운로드·증분 배치·DDL·DB 이미지 전환을 하지 않는다.
4. 전체 기존 `compose run --rm --no-deps --pull never` 명령으로 localhost 18082 전용 검증 컨테이너만 실행하되 다음 옵션을 사용한다. 단독 실행 명령이 아니며 기존 두 digest, URL, AI 활성 플래그, Import/Preview/임베딩 배치 false와 timeout 설정을 함께 유지한다. 운영 .env는 수정하지 않는다.

   ```text
   --perfume.ai.recommend.max-evidence-candidates=1
   --perfume.ai.recommend.max-input-bytes=2560
   --perfume.ai.recommend.max-output-tokens=128
   ```

5. 두 번째 셸에서 `perfume-dev-rag-recommendation-test` label/실행 상태/127.0.0.1 게시 주소를 확인한다. 내부 localhost의 기존 catalog 준비 검사를 한 뒤 승인된 질문을 topK=5로 **한 번만** 요청한다. 질문/답변을 로그나 파일에 tee하지 않는다. 실제 원문을 셸 이력에 남기지 않는 stdin 입력 방식은 [진단 문서](ollama-rag-diagnostics.md)의 기존 예시를 사용한다.
6. 요청 진단의 `selectionFormat=source-fact-id`, compact, contextSources=1, allowedSentences<=3(identity 제외 선택지 최대 2개), maxOutputTokens=128을 확인한다. 이 marker가 없으면 이전 이미지일 수 있으므로 실제 추천 검증을 진행하지 않는다. final doneReason=stop 후 **API 200과 정확한 Java 문장·출처 조립**까지 확인한다. sources는 실제 전체 검색 결과이고 설명은 그중 1개인지 확인한다.
7. 502라면 본문을 수집하는 대신 안전한 validationReason·allowedFacts·selectedCount를 보고 분류한다. 원래 502의 이유를 이번 reason으로 소급 확정하지 않는다. length/부분 JSON/timeout은 실패로 기록한다. 자동 재시도하지 않는다. 요청 종료와 runner CPU 유휴를 확인한 후 사람이 256 비교를 별도 선택할 수 있다. 입력/후보는 고정하고 출력 옵션만 바꾼다.
8. 2개 기능은 다음 별도 승인된 실행에서 max-evidence-candidates=2로 비교한다. 중복/역순 보정 없이 실제 올바른 JSON과 Java의 두 쌍 조립을 확인한다. 128/256의 실제 eval_count·duration·TPS를 비교하고 로딩/준비 지연도 별도로 기록한다. 스트리밍 가상 진단은 [기존 오프라인 요약기 절차](ollama-rag-diagnostics.md)를 재사용한다.
9. 첫 번째 셸에서 Ctrl+C로 **해당 포그라운드 테스트 컨테이너만** 종료한다. `--rm`으로 그 컨테이너가 종료·제거됐는지 확인하고, 남아 있으면 이름/label/실제 작업 상태를 먼저 확인한다. 실행 중인 컨테이너를 확인 없이 삭제하지 않는다. 기존 DB/Ollama/backend/frontend나 볼륨을 정리 대상으로 삼지 않는다. 클라이언트 취소 후에도 runner 계산이 남을 수 있어 유휴를 확인한다.
10. 첫 작업 셸에서 기존 `embedding_manifest > "$AUDIT_DIR/after.txt"`, `cmp`로 전후 동일성을 확인한다. DB/backend 컨테이너 ID와 기존 웹 health도 확인한다. 이후 **자식 작업 셸에서 `exit`**한다. helper와 상속 자식의 FD가 닫히며 잠금이 해제된다. 잠금 파일을 삭제하거나 다른 셸에서 강제 해제하지 않는다. 실패로 자식 셸이 종료돼도 원래 로그인 셸은 유지되며, 남은 자식 작업이 있으면 잠금 파일을 지우지 말고 종료 상태를 먼저 확인한다.

## 로컬 테스트와 변경 파일

신규 `RecommendationSelectionTests` 55개는 1/2개 선택, 정확한 원문·identity-first 조립, 실제로 하나만 전달된 선택지, source별 factId 범위, source 유지, 모든 내부 실패 분류·비노출을 검사한다. 기존 Service의 실패 검사는 번호 오류/자유 텍스트 혼입 및 재생성 금지로 확장했다. 기존 topK 5 전체 sources, read-only Mock JDBC, 검색 임베딩 단일 호출, 모델 digest, length/timeout, 기본 비활성·동시 실행·인젝션·누락 지표 테스트도 유지한다.

Java 17 / 준비된 Maven 3.9.16·로컬 캐시의 오프라인 `package` 성공: 전체 **1082개 중 1067개 통과, 기존 live/manual 15개 skipped, 실패·오류 0**이다. 추천 258개(신규 55개 포함), 검색 84개, 임베딩 72개 및 기존 Import/Preview/skip-existing 회귀 테스트를 포함한다. 로그는 `target/phase1-validation/rag-selection-focused.log`, `rag-selection-package.log`다. wrapper/의존성을 수정하지 않았으며 기존 live/manual 제외 외에 테스트를 추가로 생략하지 않았다. 배포 보호 56개, 오프라인 응답 진단기 12개, React 8개도 통과했다. 배포 검사는 가짜 실행기와 로컬 Compose config만 사용하며 실제 컨테이너를 변경하지 않는다. Mock의 토큰 수는 가상 값이므로 실제 모델이 128 안에 완성한다는 증거가 아니다.

수동 문서의 Bash 블록 14개는 실행하지 않고 `bash -n`으로 문법만 검사했다. 가상 번호 선택 payload 36종을 로컬 메모리에서 생성·검사했으며 본문은 출력하지 않았다. runtime JAR에 수동 SQL·문서·측정 JSON이 없음을 확인했다. 작업 시작 파일 해시와 비교해 기존 검색/임베딩/Import/SQL/배포 및 공개 Service/Response/Handler·Ollama Client·Evidence Formatter가 그대로임을 확인했다. Docker 이미지 빌드는 수행하지 않았다.

| 수정·추가 파일 | 변경 |
| --- | --- |
| `src/main/java/com/perfume/ai/recommend/RecommendationPromptBuilder.java` | 번호 근거 payload, 엄격한 선택 검증·서버 문장 조립·고정 실패 분류 |
| `src/main/java/com/perfume/ai/recommend/RecommendationValidationReason.java` (추가) | 내부 진단 enum 13개 |
| `src/main/java/com/perfume/ai/recommend/OllamaRecommendationDiagnostics.java` | 안전한 validation 이벤트와 selectionFormat; 기존 duration/TPS 유지 |
| `src/main/java/com/perfume/ai/recommend/PerfumeRecommendationOptions.java` | 출력 기본 128, 나머지 범위·예산·timeout 유지 |
| `src/test/java/com/perfume/ai/recommend/RecommendationSelectionTests.java` (추가) | 번호 선택·보안·실패 분류 55개 |
| `src/test/java/com/perfume/ai/recommend/RecommendationPromptBuilderTests.java` | 새 payload, 기존 legacy text·인젝션·허위 주장 거부 의도 유지 |
| `src/test/java/com/perfume/ai/recommend/RecommendationPromptOptimizationTests.java` | 번호 JSON으로 기존 크기·source·토큰·불확실성 테스트 적용 |
| `src/test/java/com/perfume/ai/recommend/RecommendationTestSupport.java` | 번호 JSON 공용 helper와 새 기본값 |
| `src/test/java/com/perfume/ai/recommend/OllamaRecommendationClientTests.java` | 기본 128 전송 및 상한 검증 |
| `src/test/java/com/perfume/ai/recommend/OllamaRecommendationDiagnosticsTests.java` | 새 기본값/marker, 기존 시간·비노출 유지 |
| `src/test/java/com/perfume/ai/recommend/PerfumeRecommendationConfigurationTests.java` | 기본 128·기존 기동 보호 |
| `src/test/java/com/perfume/ai/recommend/PerfumeRecommendationServiceTests.java` | 잘못된 선택·자유 문장 혼입 실패, 생성 1회 검증 |
| `src/test/java/com/perfume/ai/recommend/PerfumeRecommendationApiTests.java` | 공개 응답에 내부 picks/reason 비노출 |
| `docs/perfume-rag-recommendation.md` | 현재 프로토콜·기본값·수동 실행 반영 |
| `docs/ollama-rag-diagnostics.md` | 선택 프로토콜의 가상 payload와 안전한 실패 진단 |
| `docs/ollama-rag-cpu-optimization.md` | 과거 측정 기록 구분과 현재 실행 옵션 |
| `docs/ollama-rag-fact-selection.md` (추가) | 원인 분석·새 설계·비교·후속 검증 절차 |

실제 통합의 JSON 준수율·선택 관련성·load/runner 지연과 CPU 시간은 후속 검증 대상이다. 근거 번호 방식도 검색 품질이나 원본 정보의 정확성을 보장하지 않으며 기존 임베딩 5개 밖으로 검색 범위를 늘리지 않는다. 서버 리소스·모델·SQL·공개 활성화 변경은 이번 범위가 아니다.
