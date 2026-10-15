# RAG 3차 CPU 프롬프트·출력 최적화

아래 변경 전후 표는 **번호 선택 도입 전 CPU 최적화 기록**이다. 현재는 [번호 선택·검증 실패 분류](ollama-rag-fact-selection.md)가 적용되어 LLM은 picks만 반환하고 Java가 문장을 조립한다. 현재 출력 기본값은 128토큰이며 아래 과거 256 설정은 최신 기본값이 아니다.

이번 작업은 로컬 코드·Mock 테스트·문서 준비까지다. 실제 Ollama/PostgreSQL/ScentRev 호출, Docker 작업, SSH, 배포, Commit/Push는 수행하지 않았다. 모델 `qwen3:4b-instruct`, 임베딩, DB/SQL, Compose, Jenkins, Import, React는 그대로다.

## 관측과 변경 이유

운영자가 측정한 가상 1개 후보/128토큰 요청은 약 129초, 첫 content 98.98초, load 62.75초, prompt 평가 0.085초/278토큰, 생성 30.21초/78토큰(2.58 tokens/s), stop이었다. 512토큰은 이 TPS를 단순 적용하면 생성만 약 198초가 될 수 있다. 이는 추정이며 실제 RAG 처리 시간이나 개선율이 아니다. 단계 합과 전체 시간의 약 36초 차이는 미확정이고, 누적 CPU throttling 값으로 개별 요청의 병목을 확정하지 않는다.

앞선 CPU 최적화에서는 모델 작업을 허용 문장 선택으로 좁히고 기본 출력 상한을 256으로 줄였다. 현재 번호 선택 방식의 기본 128 근거는 새 문서를 참고한다. 128에서 긴 한국어 identity/근거와 JSON을 완성하지 못할 가능성을 고려한 절충이다. 256도 성공 보장은 아니며 실제 `done_reason=stop`과 서버 검증으로 판정한다. 로딩·runner 대기·미확인 36초는 이 변경만으로 해결됐다고 볼 수 없다. 타임아웃을 늘리거나 재시도하지 않는다.

## 변경 전후 로컬 실측

`RecommendationTestSupport.text(1/2)`의 동일한 가상 스냅샷과 질문 `여름에 지속력이 긴 향수`, 동일 ObjectMapper를 사용했다. 변경 전 builder를 실제 실행해 `target/phase1-validation/rag-cpu-before.json`에 길이만 기록했고, 변경 후 `RecommendationPromptOptimizationTests`가 `rag-cpu-current.json`을 생성한다. 아래 수치는 당시 보존한 측정값이다. 각 값은 **3개 메시지 content의 UTF-8 바이트 합**이며 HTTP body나 모델 template를 포함하지 않는다. DB 원문/실제 서버 프롬프트를 측정한 값이 아니다.

| 비교 항목 | 기존 | 최적화 후 |
| --- | --- | --- |
| System Prompt 바이트(후보 상한 지시 포함) | 576 | 413 |
| 가상 근거 1개 입력 바이트 | 1371 | 753 |
| 가상 근거 2개 입력 바이트(2개 모드 비교) | 2105 | 1032 |
| 가상 근거 2개 입력 바이트(새 기본 간결 모드) | 2105 | 753; 1위만 전달 |
| LLM 최대 출력 토큰 | 512 | 기본 256; 128–768 유지 |
| LLM 설명 후보 | 최대 2 | 기본 1, 명시적으로 최대 2 |
| LLM에 전달하는 허용 문장 | 사실 최대 24개/후보+identity·불확실성, 입력 예산 적용 | identity+사실/불확실성 선택지 2개, 최대 3개/후보 |
| 위 가상 fixture의 허용 문장 수 | 1개 9 / 2개 18 | 1개 3 / 2개 6 |
| 실제 LLM 답변 문장 | 최대 6줄 | 후보당 identity+사실/불확실성 하나; 기본 2줄, 최대 4줄 |
| 입력 예산 | `4096-output-512`, 기본 3072 | 별도 기본 2560바이트, 출력 축소로 증가하지 않음 |
| JSON·출처·허용 문장·identity-first 검증 | 엄격 | 유지, 짧은 형식도 서버에서 검증 |
| 임베딩·LLM 전체 digest 검증 | 적용 | 유지 |
| 검색·추천 기본 활성화 | false | false |

## 근거 선택과 검증

- pgvector Top-K와 실제 검색 결과의 `sources`, `returnedCount`, 기존 응답 JSON 구조는 유지한다. LLM은 별도 후보 상한에 따라 검색 순위 1위 또는 1–2위만 본다. 출처를 다시 번호 매기지 않는다.
- 기존 read-only 스냅샷 조회·해시·모델 식별 검증은 전체 검색 결과에 그대로 수행한다. 새 DB 조회나 쓰기는 없다. 전체 스냅샷의 이름·브랜드 일치 검사도 유지한다.
- 질문한 지속력/발향력 평가, 계절 평가, 노트, 어코드, 기타 평가, 설명 순으로 선택한다. 질문한 지속력·계절 자료가 없으면 해당 불확실성 문장을 우선한다. 노트에서 성능·계절 적합성을 추론하지 않는다. 지시문/위험 문자열로 제외된 Metric은 평가가 있다는 근거로 세지 않는다.
- 후보별 identity와 첫 사실/불확실성의 쌍을 먼저 확보하고 추가 선택지는 하나만 넣는다. 문자 일부를 자를 때의 표시와 120바이트 상한은 유지한다. perfumeId/snapshotAt 중복 메타데이터는 프롬프트에서 생략하지만 서버 검증 및 최신성 안내는 유지한다.
- 현재 LLM은 허용된 (source,factId)만 선택하며 서버가 정확한 한국어 문장을 조회한다. identity-first·동일 출처·후보당 사실/불확실성 정확히 하나·중복 금지를 검사한다. 원문 지시를 따르거나 새 향수/문장/수치/가짜 출처를 만들면 실패한다.
- 현재 내부 LLM 응답은 `{"picks":[{"source":1,"factId":1}]}`만 허용하며 외부 answer는 Java가 조립한다. 추가/중복 필드, trailing JSON, 부분 JSON, `done_reason=length`, 타임아웃 부분 출력은 성공으로 반환하지 않는다. 자동 수선·재생성·재시도는 없다.
- INTRO와 스냅샷 최신성·유사도의 한계 안내, 생략 안내는 기존 서버 문구를 그대로 추가한다. 후보를 생략했으면 이를 표시한다.

## 실행 옵션

설정은 `perfume.ai.recommend.` 접두사다. 기존 [추천 실행 문서](perfume-rag-recommendation.md)의 전체 digest와 비활성 기본 정책을 그대로 따른다.

| 옵션 | 기본값 | 범위/의미 |
| --- | --- | --- |
| `max-evidence-candidates` | 1 | 1=compact / 2=two-candidate. 검색 Top-K와 별개 |
| `max-input-bytes` | 2560 | 1024–2816; 전체 메시지 content 예산 |
| `max-output-tokens` | 128 | 기존 128–768; 128/192/256/512 선택 가능 |

입력 상한 2816은 context 4096에서 최대 출력 768과 template 여유 512를 예약한다. 출력 설정을 바꾸어도 입력 상한은 고정된다. 정확한 tokenizer 계산은 아니므로 실제 prompt/eval count 검사도 유지한다. 긴 질문/이름 때문에 최소 쌍이 들어가지 않으면 근거 없음으로 실패하며 identity만 전송하지 않는다.

동시 실행 기본 1·최대 2·대기열 0, read 180초/생성 300초/요청 420초 기본값은 바꾸지 않았다. 기존 수동 검증 인스턴스에서 별도로 사용하던 timeout 값도 이 작업에서 변경하지 않는다. 기본 기능은 계속 비활성이고 새 공개 실행 API나 자동 배치가 없다.

## 이후 Ubuntu 수동 통합 검증 — 이번에 실행하지 않음

1. 별도 승인 후 [기존 추천 검증 절차](perfume-rag-recommendation.md)의 「Ubuntu localhost 전용 검증」 절에 있는 유지보수 자식 셸·배포 잠금·전후 read-only manifest 절차를 재사용한다. 기존 운영 서비스·.env·DB·모델을 변경하지 않는다. AI 작업을 서로 겹치지 않는다.
2. 새 코드를 포함하도록 별도로 준비·확인한 backend 이미지로 localhost 전용 테스트 컨테이너만 사용한다. 이전 이미지에는 새 기본값/옵션이 없으므로 버전을 확인한다. 컨테이너 생성은 현재 승인 범위가 아니며 기존 문서의 `compose run --no-deps --pull never`는 후속 승인 후에만 실행한다.
3. 기존 **전체 실행 명령**에 아래 세 옵션을 명시한다. 두 모델의 전체 digest, 검색·추천 활성화, Import/Preview/임베딩 배치 false, localhost 포트와 기존 timeout 옵션을 생략하지 않는다. `.env`는 수정하지 않는다.

   ```text
   --perfume.ai.recommend.max-evidence-candidates=1
   --perfume.ai.recommend.max-input-bytes=2560
   --perfume.ai.recommend.max-output-tokens=128
   ```

4. `topK=5`로 승인된 같은 질문을 한 번 호출한다. 테스트 데이터가 5개 미만이면 실제 반환 개수를 기준으로 본다. API sources는 전체 결과, LLM 설명은 최대 1개인지 확인한다. 질문·전체 프롬프트·답변을 로그/파일로 남기지 않는다. 답변 품질을 수동 확인할 때도 비공개 터미널만 사용한다.
5. 안전한 요청 로그에서 `optimizationMode=compact`, `contextSources=1`, `allowedSentences<=3`, `inputBudgetBytes=2560`, 실제 `messageContentBytes`, `maxOutputTokens=128`, `omitted`를 확인한다. 최종 `load_duration`, `prompt_eval_duration`, `eval_duration`, 토큰 수, generatedTokensPerSecond를 비교한다. HTTP 200이나 `responseComplete=true`만으로 성공 판정하지 말고 추천 검증까지 확인한다.
6. 요청 종료와 runner CPU 유휴를 확인한 뒤에만 다음 실험을 사람이 선택한다. 128에서 stop/근거 검증을 확인하고 필요한 경우 256으로 **출력 옵션만** 바꿔 번호 JSON 완성 여부를 비교한다. length/부분 JSON이면 실패로 기록하고 이전에 성공한 상한으로 수동 복귀한다. 실패 직후 자동 재시도하지 않는다.
7. 최대 2개 기능은 `max-evidence-candidates=2`로 별도 검증한다. 입력 예산·출력 128은 우선 그대로 두고, 두 선택의 JSON이 잘리면 원인을 기록한 뒤 256 옵션을 별도 실험으로 선택할 수 있다. 기본값을 바꾸거나 출력 길이에 따라 자동 증액하지 않는다. 2개 모드의 성공/지연은 아직 미검증이다.
8. [스트리밍 진단](ollama-rag-diagnostics.md)으로 같은 가상 입력의 stream/non-stream을 별도로 비교하고 요청 전/중/후 CPU·throttling **증가량**을 본다. 위 36초를 특정 구간으로 임의 배정하지 않는다. 작은 프롬프트도 지연되면 로딩/runner 상태부터 조사하며 모델/리소스 변경은 별도 검토한다.
9. 기존 문서대로 테스트 컨테이너 종료 후 전후 embedding manifest와 서비스 상태를 비교하고 작업 잠금을 해제한다. 실험 때문에 임베딩을 재생성하거나 기존 5개 벡터를 수정하지 않는다.

## 최초 CPU 최적화 시점의 로컬 검증 기록

다음 1024개 결과는 앞선 작업 기록이다. 현재 전체 결과와 파일 변경 목록은 [번호 선택 문서](ollama-rag-fact-selection.md)에 정리했다.

신규 최적화 Mock 테스트는 동일 fixture 길이 감소, 1/2개 완성 JSON, Top-K 5와 출처 보존, 출력 128/192/256/512/768의 전송 및 입력 예산 독립성, length/부분 JSON 거부, 누락 지표 우선순위·위험 Metric·제외 후보 거부를 검사한다. 기존 환각/가짜 출처/인젝션/기본 비활성/읽기 전용/검색 임베딩 단일 호출/동시 실행·timeout 테스트도 유지한다.

Mock의 생성 토큰 수는 가상 값이다. **Mock 성공은 실제 Qwen tokenizer에서 128/256 안에 JSON이 완성되거나 CPU 응답 시간이 개선됐다는 증거가 아니다.** 실제 속도, load 구간, 미확인 시간, 2개 후보의 품질·완성 여부는 위 후속 통합 테스트에서 확인해야 한다.

Java 17 / Maven 3.9.16의 오프라인 `package`: 전체 1024개 중 1009개 통과, 기존 live/manual 15개 skipped, 실패·오류 0. 추천 200개(신규 최적화 22개 포함), 검색 84개, 임베딩 72개, Preview 81개, skip-existing 51개, 기존 단일 브랜드 Import 12개가 포함된다. 배포 보호 56개, Python 응답 진단기 12개, React 8개도 통과했다. 수동 문서 Bash 블록 14개는 실행하지 않고 문법만 검사했고, 가상 payload 36종은 로컬 메모리에서만 검사했다. runtime JAR에 문서/수동 SQL/측정 JSON이 없음을 확인했다. Docker 이미지 빌드와 실제 통합 테스트는 수행하지 않았다.

로그: `target/phase1-validation/rag-cpu-focused.log`, `rag-cpu-package.log`, `rag-cpu-deployment-tests.log`, `rag-cpu-parser-tests.log`. Windows wrapper 초기화 문제 때문에 기존에 준비된 Maven/로컬 의존성 캐시를 사용했고 Maven 설정·의존성은 변경하지 않았다.

## 최초 CPU 최적화 시점의 수정·추가 파일

| 경로 | 변경 |
| --- | --- |
| `src/main/java/com/perfume/ai/recommend/RecommendationPromptBuilder.java` | 간결 system, 후보/선택지 축소, 완전한 근거 쌍과 짧은 답변 검증 |
| `src/main/java/com/perfume/ai/recommend/RecommendationEvidenceFormatter.java` | 질문 지표별 우선순위, 누락·위험 Metric 불확실성 유지 |
| `src/main/java/com/perfume/ai/recommend/PerfumeRecommendationOptions.java` | 출력 기본 256, 독립 입력 예산과 후보 상한 |
| `src/main/java/com/perfume/ai/recommend/PerfumeRecommendationConfiguration.java` | 기존 builder에 후보 상한 전달 |
| `src/main/java/com/perfume/ai/recommend/OllamaRecommendationDiagnostics.java` | 안전한 모드·설정 메타데이터 추가, 기존 시간/TPS 유지 |
| `src/test/java/com/perfume/ai/recommend/RecommendationPromptOptimizationTests.java` (추가) | 22개 최적화·출처·토큰·JSON·불확실성 Mock 회귀 검사 |
| `src/test/java/com/perfume/ai/recommend/RecommendationPromptBuilderTests.java` | 줄인 근거 구조와 명시적 2개 모드에 기존 보안 검사 적용 |
| `src/test/java/com/perfume/ai/recommend/PerfumeRecommendationConfigurationTests.java` | 새 기본값/범위, 기존 비활성·기동 보호 유지 |
| `src/test/java/com/perfume/ai/recommend/OllamaRecommendationClientTests.java` | 출력 기본 256 전송 확인 |
| `src/test/java/com/perfume/ai/recommend/OllamaRecommendationDiagnosticsTests.java` | 새 안전한 메타데이터·기본값, 기존 측정/비노출 유지 |
| `src/test/java/com/perfume/ai/recommend/RecommendationTestSupport.java` | 가상 설정·1/2개 builder 공용 helper |
| `docs/perfume-rag-recommendation.md` | 현재 기본값·검증 정책·수동 실행 옵션 반영 |
| `docs/ollama-rag-diagnostics.md` | 운영자 실측의 한계·현재 메타데이터·독립 입력 예산/192토큰 진단 반영 |
| `docs/ollama-rag-cpu-optimization.md` (추가) | 비교표·옵션·후속 통합 절차·검증 결과 |
