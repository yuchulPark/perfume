# RAG 3차 LLM 지연 진단

이 문서는 안전한 메타데이터 진단과 수동 측정 절차다. 후속 [CPU 최적화](ollama-rag-cpu-optimization.md)에서 프롬프트·근거량·출력 상한을 축소했다. 실제 Ollama/DB/ScentRev 호출, 컨테이너 작업, SSH, Commit/Push, Jenkins 실행은 하지 않았다. 타임아웃·재시도·모델·기본 비활성화와 엄격한 근거 검증은 유지한다.

## 현재 판단과 확인한 코드

운영자 관측은 짧은 OK 약 13초, 짧은 JSON 약 5.5초, 모델 전환 약 17초의 성공과, 비교 문장 120–157초 및 RAG 생성 단계 300초 실패다. 임베딩도 약 4.57초에 성공한다. 기본 통신·짧은 생성은 가능하지만 **긴 요청의 어느 단계가 느린지는 아직 미확정**이다. 모델 로딩만이 원인이라고 단정할 수 없으며 메모리 부족 증거가 없다고 CPU 병목·throttling·runner 대기까지 배제할 수는 없다.

후속 운영자 진단의 가상 1개/128토큰/stream=true 요청은 전체 약 129초, 첫 content 98.98초, load 62.75초, prompt 평가 0.085초/278토큰, 생성 30.21초/78토큰(2.58 tokens/s), stop/HTTP 200이었다. 단계 합과 전체 시간의 약 36초 차이는 아직 설명되지 않았다. 이를 queue 시간이나 로딩 시간으로 확정하지 않는다. cpu.stat의 누적 throttling 수치와 oom_kill=0만으로 개별 요청 지연 원인을 확정할 수도 없다. 요청 전후 증가량을 추가로 측정한다.

`OllamaRecommendationClient`는 tags → chat → tags를 순차 호출하며 자동 재시도하지 않는다. 서버 시간 지표는 기존 안전 진단에 유지한다. 비스트리밍은 종료 전 토큰 진행을 볼 수 없으므로 최종 응답을 받지 못한 타임아웃 로그만으로 로딩/프롬프트 평가/생성을 나눌 수 없다.

실제 입력은 system, USER_QUERY, EVIDENCE_DATA의 3개 메시지다. 현재 기본 content 합은 UTF-8 최대 2560바이트이며 출력과 별도 예산이다. 기본 1개 후보/허용 3문장 이내에서 identity+사실/불확실성 하나를 출력한다. 출력 상한은 256토큰이며 128/192/512도 명시적으로 선택할 수 있다. byte 수는 정확한 tokenizer 수치나 template 포함 길이가 아니다. 짧은 관측에서 prompt 평가가 짧았으므로 입력 축소만으로 62.75초의 load 구간이나 미확인 36초가 해결된다고 주장하지 않는다.

## Ollama 0.40.4 호환성 확인

공식 [v0.40.4 릴리스](https://github.com/ollama/ollama/releases/tag/v0.40.4), 해당 태그의 [api/types.go](https://github.com/ollama/ollama/blob/v0.40.4/api/types.go)와 [server/routes.go](https://github.com/ollama/ollama/blob/v0.40.4/server/routes.go)를 확인했다.

| 위치 | 현재 전송 값 | 고정 버전 소스 확인 |
| --- | --- | --- |
| ChatRequest | model, messages, stream=false, format=json | 해당 필드 존재 |
| ChatRequest | think=false, truncate=false, keep_alive=5m | 해당 필드 존재; think는 명시적 false와 미지정 구분 |
| options | num_ctx=4096, num_predict=256(현재 기본), num_thread=3, temperature=0.2 | Options/Runner 정의에 존재 |

정의상 사용 가능한 옵션이며 제거·치환하지 않았다. 다만 저장소 소스가 실제 실행 바이너리와 같은지는 `/api/version` 및 이미지 ID로 별도 확인해야 한다. `think=false`의 실제 효과는 모델 capability/template에도 의존한다. `num_predict`는 최대 생성 수이고 응답 시간 보장은 아니다.

고정 버전의 비스트리밍 Chat 코드는 청크를 내부에서 합친 뒤 JSON을 반환한다. 스트리밍은 같은 생성의 진행을 NDJSON으로 관찰하게 하며 그 자체로 추론량을 줄이지 않는다. `load_duration`은 handler 시작부터 runner 준비 후 시점까지라 **순수 디스크 로딩 외 scheduler 대기 등이 포함될 수 있다.** 각 duration의 차이를 정확한 queue 시간으로 계산하지 않는다. [고정 버전 handler 구현](https://github.com/ollama/ollama/blob/v0.40.4/server/routes.go)

## 추가된 안전한 진단

`OllamaRecommendationDiagnostics`는 활성 추천 요청에서만 다음 INFO 이벤트를 기록한다. 신규 공개 API·저장·진단용 모델 호출은 없다.

- `event=request`: optimizationMode(compact/two-candidate), maxEvidenceCandidates, inputBudgetBytes, 메시지 개수와 구간별 UTF-8 바이트, 실제 문맥 후보 수, 허용 문장 수, 생략 여부, context/출력/스레드/시간 상한.
- `event=response`: HTTP 상태·본문 수신 완료·done·허용된 종료 사유와 실제 서버 수치.
- 기존 실패 로그: `httpStatus`, `responseComplete`, `serverMetricsAvailable=false` 추가. HTTP 200 후 본문 읽기 타임아웃도 확인 가능.

| 서버 필드 | 로그 단위/해석 |
| --- | --- |
| total_duration | `total_duration_ns`, 서버 전체 시간 |
| load_duration | `load_duration_ns`, 로딩/runner 준비 구간 |
| prompt_eval_duration | `prompt_eval_duration_ns`, 프롬프트 평가 시간 |
| eval_duration | `eval_duration_ns`, 토큰 생성 시간 |
| prompt_eval_count | 실제 프롬프트 토큰 수 |
| eval_count | 실제 생성 토큰 수 |

`promptTokensPerSecond = prompt_eval_count × 1e9 / prompt_eval_duration`, `generatedTokensPerSecond = eval_count × 1e9 / eval_duration`다. nanosecond를 초로 환산하려면 1e9로 나눈다. 누락·음수·문자열·overflow는 `unavailable`, 0 duration의 속도도 `unavailable`이다. 최종 응답이 없으면 서버 지표를 0으로 만들거나 추정하지 않는다. 전체 duration과 각 구간의 합은 같다고 가정하지 않는다. 프롬프트 캐시가 반복 요청의 평가 속도에 영향을 줄 수 있어 프롬프트 TPS만으로 하드웨어 성능을 확정하지 않는다.

본문 검증 실패(`done_reason=length`, thinking 출력 등)도 수신된 시간/카운터는 먼저 기록한다. **`responseComplete=true`는 HTTP 본문을 읽었다는 뜻이며 추천 검증 성공을 뜻하지 않는다.** content/공백/thinking은 문자 수만 기록한다. 필드명 외 응답 문자열은 기록하지 않으며 done_reason도 stop/length/load/unload만 허용한다.

질문·프롬프트·답변·벡터·인증정보·서버 오류 본문·스택은 출력하지 않는다. wire logging, Ollama debug/trace, HTTP body 로깅을 켜거나 전체 `docker logs`를 수집하지 않는다. 이 변경이 HTTP API 응답에 진단 정보나 내부 주소를 추가하지도 않는다.

## 수동 측정 전제 — 아래 명령은 이번에 실행하지 않음

코드 반영 및 실제 모델 진단은 별도 승인 후 진행한다. 기존 [유지보수 자식 셸](../scripts/pgvector-maintenance-shell.sh)과 Jenkins 잠금 정책을 따른다. PuTTY 로그인 셸에 strict 옵션을 직접 켜지 않는다. [기존 실행 문서](perfume-rag-recommendation.md)의 작업 셸 진입·compose 함수만 재사용하고 SQL/배치/컨테이너 생성 절차를 다시 실행하지 않는다.

이미 실행 중인 localhost 전용 검증 인스턴스만 사용한다. 없으면 중단하며 이 문서는 생성·재시작 명령을 제공하지 않는다. 다른 RAG/임베딩 요청이나 앞선 타임아웃 작업이 계속 CPU를 쓰고 있으면 측정을 겹치지 않는다. 메모리에 모델이 남아 있다는 사실만으로 생성 중이라고 판단하지 말고 CPU도 확인한다.

```bash
cd /srv/perfume
unset COMPOSE_FILE COMPOSE_PROJECT_NAME COMPOSE_PROFILES COMPOSE_DRY_RUN PERFUME_DB_IMAGE
unset DB_NAME DB_USERNAME DB_PASSWORD DB_URL SCENTREV_API_KEY
compose() {
  docker compose --project-name perfume-dev --project-directory /srv/perfume \
    --env-file /srv/perfume/.env --file /srv/perfume/compose.yaml "$@"
}
command -v python3 >/dev/null
test -f scripts/ollama-response-diagnostics.py
RAG_CONTAINER=perfume-dev-rag-recommendation-test
test "$(docker inspect --format '{{index .Config.Labels "com.docker.compose.project"}}' "$RAG_CONTAINER")" = perfume-dev
test "$(docker inspect --format '{{.State.Running}}' "$RAG_CONTAINER")" = true
OLLAMA_CONTAINER="$(compose ps --quiet ollama)"
[[ "$OLLAMA_CONTAINER" =~ ^[0-9a-f]+$ ]]
test "$(docker inspect --format '{{index .Config.Labels "com.docker.compose.project"}}' "$OLLAMA_CONTAINER")" = perfume-dev
test "$(docker inspect --format '{{index .Config.Labels "com.docker.compose.service"}}' "$OLLAMA_CONTAINER")" = ollama
docker inspect --format 'image_id={{.Image}} image={{.Config.Image}} cpu_nanos={{.HostConfig.NanoCpus}} memory_limit={{.HostConfig.Memory}}' "$OLLAMA_CONTAINER"
docker exec "$RAG_CONTAINER" curl --fail --silent --show-error --max-time 10 http://ollama:11434/api/version | \
  python3 -c 'import json,re,sys; v=json.load(sys.stdin).get("version"); print(json.dumps({"version":v if isinstance(v,str) and re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?",v) else "unavailable"}))'
```

설정 태그와 실제 실행 버전을 함께 확인한다. 실제 버전이 0.40.4와 다르면 이 소스 비교를 그대로 적용하지 않고 다시 확인한다. 기존 전체 digest 검증도 유지하며 모델 다운로드·갱신은 하지 않는다.

모델 메타데이터는 필요한 항목만 통과시킨다. template/system/parameters 원문은 출력하지 않는다.

```bash
docker exec "$RAG_CONTAINER" curl --fail --silent --show-error --max-time 10 \
  -H 'Content-Type: application/json' --data '{"model":"qwen3:4b-instruct"}' http://ollama:11434/api/show | \
  python3 -c 'import json,re,sys; d=json.load(sys.stdin); q=d.get("details",{}).get("quantization_level"); t=d.get("template",""); c=d.get("capabilities",[]); print(json.dumps({"completion_capable":"completion" in c,"thinking_capable":"thinking" in c,"template_bytes":len(t.encode()) if isinstance(t,str) else None,"template_has_think_marker":isinstance(t,str) and "<think>" in t,"quantization":q if isinstance(q,str) and re.fullmatch(r"(?:I?Q[0-9][A-Z0-9_]*|F16|F32|BF16)",q) else "unavailable"}))'
compose exec -T ollama ollama ps
```

`thinking_capable`이나 template marker만으로 false 옵션 무시를 단정하지 않는다. 아래 스트리밍에서 thinking 문자/marker 발생도 대조한다. 실제 생성은 여전히 기존 모델 digest 검사를 통과해야 한다.

## 실제 RAG의 메타데이터 관찰

기존 검증 API에 승인된 질문을 한 번만 보낸다. 원문을 셸 인자로 남기지 않으려면 입력을 숨겨 읽고 stdin으로 전달할 수 있다. 출력 답변은 버린다. `set -x`를 켜지 않는다.

```bash
read -rsp '진단할 질문(화면/로그에 출력하지 않음): ' RAG_QUERY
printf '\n'
printf '%s' "$RAG_QUERY" | python3 -c 'import json,sys; print(json.dumps({"query":sys.stdin.read(),"topK":5}))' | \
  docker exec -i "$RAG_CONTAINER" curl --silent --show-error --max-time 610 --output /dev/null \
    --write-out 'HTTP=%{http_code} total_s=%{time_total}\n' -H 'Content-Type: application/json' \
    --data-binary @- http://127.0.0.1:18082/api/ai/perfumes/recommend || \
  printf '추천 측정 실패. 본문 없이 상태/메타데이터를 확인합니다.\n'
unset RAG_QUERY
```

Java의 `event=request`로 실제 길이·문맥 수·출력 상한을 확인한다. 최종 통계가 수신된 경우만 응답 이벤트로 각 시간을 비교한다. 타임아웃이면 final metric은 알 수 없다. 진단 로그를 볼 때도 고정 진단 logger/메타데이터만 사용하고 기존 로그 전체를 공유하지 않는다.

## 별도 스트리밍 진단: 응답 내용은 출력하지 않음

`scripts/ollama-response-diagnostics.py`는 **stdin 전용 오프라인 요약기**다. 자체 HTTP/DB/Docker 호출·재시도·저장 기능이 없다. NDJSON에서 첫 청크/첫 content/첫 공백 아닌 content/첫 thinking 시간, 청크·문자·공백 수, 마지막 서버 통계만 출력한다. 청크 수/문자 수는 토큰 수가 아니다. `<think>` marker는 존재 여부만 보고하며 청크 경계도 처리한다. 원문 없이 진행 여부를 확인할 수 있다.

최종 `done=true`가 없으면 incomplete/종료 코드 2다. HTTP 오류 본문·잘못된 JSON·너무 큰 청크도 고정 코드로만 보고한다. `complete`도 Ollama 전송 완료 측정이며 기존 추천의 출처/문장 검증 성공을 뜻하지 않는다.

아래는 **실제 DB를 읽지 않는 가상 후보**다. 현재 Java system 규칙을 소스에서 읽어 같은 3메시지 구조로 보내지만 실제 사용자의 RAG 근거와 동일한 프롬프트는 아니다. 실제 프롬프트는 내보내거나 로그에 기록하지 않는다. 함수는 단독 출력/tee하지 말고 아래 파이프로만 사용한다.

```bash
rag_diagnostic_payload() {
  python3 - "$1" "$2" "$3" <<'PY'
import json, pathlib, re, sys
mode, tokens, candidates = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
if mode not in ("stream", "non-stream") or tokens not in (32,64,128,192,256,512) or candidates not in (1,2,5):
    raise SystemExit("INVALID_DIAGNOSTIC_LIMITS")
try:
    source = pathlib.Path("src/main/java/com/perfume/ai/recommend/RecommendationPromptBuilder.java").read_text(encoding="utf-8")
    declaration = re.search(r'static final String SYSTEM\s*=\s*(.*?);\r?\n', source, re.S).group(1)
    system = "".join(json.loads(part) for part in re.findall(r'"(?:\\.|[^"\\])*"', declaration))
    system += f" At most {min(candidates,2)} perfume(s)."
except Exception:
    raise SystemExit("SYSTEM_TEMPLATE_UNAVAILABLE") from None
blocks = [{"source":i,"sentences":[
    f"진단 후보 {i} (가상 브랜드)는 검색된 후보입니다. [{i}]",
    f"저장된 탑 노트는 «Bergamot»입니다. [{i}]",
    f"이 근거에는 지속력 평가가 없어 지속력을 확정할 수 없습니다. [{i}]"
]} for i in range(1,candidates+1)]
messages = [{"role":"system","content":system},
    {"role":"user","content":"USER_QUERY " + json.dumps("저장된 노트가 있는 후보를 짧게 비교",ensure_ascii=False)},
    {"role":"user","content":"EVIDENCE_DATA " + json.dumps(blocks,ensure_ascii=False,separators=(",",":"))}]
if sum(len(message["content"].encode()) for message in messages) > 2560:
    raise SystemExit("DIAGNOSTIC_INPUT_BUDGET_EXCEEDED")
print(json.dumps({"model":"qwen3:4b-instruct","messages":messages,"stream":mode=="stream", "format":"json",
    "think":False,"truncate":False,"keep_alive":"5m","options":{"num_ctx":4096,"num_predict":tokens,"num_thread":3,"temperature":0.2}},ensure_ascii=False))
PY
}
run_rag_diagnostic() {
  local mode="$1" tokens="$2" candidates="$3"
  local -a status
  if rag_diagnostic_payload "$mode" "$tokens" "$candidates" | \
    docker exec -i "$RAG_CONTAINER" curl --silent --show-error --no-buffer --max-time 330 \
      --write-out '%{stderr}HTTP=%{http_code} total_s=%{time_total} first_http_byte_s=%{time_starttransfer}\n' \
      -H 'Content-Type: application/json' --data-binary @- http://ollama:11434/api/chat | \
    python3 -u scripts/ollama-response-diagnostics.py --mode "$mode"; then
    status=("${PIPESTATUS[@]}")
  else
    status=("${PIPESTATUS[@]}")
  fi
  printf 'payload_exit=%s curl_exit=%s parser_exit=%s\n' "${status[0]}" "${status[1]}" "${status[2]}"
}
```

다음 명령은 각각 **수동 1회**씩, 앞 요청이 완전히 종료한 뒤 실행한다. 자동 반복/재시도가 아니다. 같은 모델·후보 수·출력 상한에서 stream만 바꿔 비교한다.

```bash
run_rag_diagnostic stream 128 2
# 위 요청 종료와 CPU 유휴를 확인한 다음 별도로 실행:
run_rag_diagnostic non-stream 128 2
```

추가 측정은 하나의 변수만 바꾼다. 우선 후보 1개와 256토큰으로 비교하고 192/128은 다음 승인된 실험으로 선택한다. 후보 1/2/5개는 가상 입력 부담 비교이며 5개는 실제 추천의 최대 2개 한도를 늘리는 설정이 아니다. 출력 32/64는 직접 진단 전용이며 추천 설정의 하한 128을 바꾸지 않는다. 512 측정이 항상 필요한 것은 아니다. 작은 상한에서도 느리면 먼저 그 결과를 분석한다. `done_reason=length`는 추천 성공이 아니라 잘린 생성이며 본문 검증을 완화하지 않는다.

`first_http_byte_s`는 HTTP 헤더/바이트까지이며 첫 토큰 시간이 아니다. 요약기의 첫 non-whitespace content 시간도 Python/파이프 시작 오차와 청크 batching을 포함한다. 비스트리밍의 첫 content는 완성 응답 시점이므로 스트리밍 TTFT와 같은 의미가 아니다. 스트리밍의 잦은 바이트는 소켓 read timeout을 피할 수 있어도 총 처리 시간을 줄이지 않는다. 두 방식 모두 같은 330초 wall deadline으로 비교한다. curl 비정상 종료/진단 incomplete는 성공으로 집계하지 않는다.

처음 cold 실행과 직후 warm 실행을 비교해 stream 효과라고 단정하지 않는다. 최종 load_duration, 모델 전환 유무, prefix cache 영향을 함께 기록한다. 순서를 바꾸는 추가 실행이 필요하면 사람이 결과를 보고 승인 범위에서 한 번씩 실행한다. 모델 stop/pull·컨테이너 재시작을 워밍 조건으로 사용하지 않는다.

## 요청 중 CPU·runner 관찰 — 읽기 전용

다른 Ubuntu 셸에서 같은 대상 ID를 확인하고 읽기 전용으로 관찰한다. 질문/본문이 있을 수 있는 프로세스 인자(cmdline)나 환경변수는 출력하지 않는다.

```bash
compose() {
  docker compose --project-name perfume-dev --project-directory /srv/perfume \
    --env-file /srv/perfume/.env --file /srv/perfume/compose.yaml "$@"
}
OLLAMA_CONTAINER="$(compose ps --quiet ollama)"
test "$(docker inspect --format '{{index .Config.Labels "com.docker.compose.project"}}' "$OLLAMA_CONTAINER")" = perfume-dev
docker stats --no-stream --format 'cpu={{.CPUPerc}} memory={{.MemUsage}} pids={{.PIDs}}' "$OLLAMA_CONTAINER"
docker top "$OLLAMA_CONTAINER" -eo pid,ppid,pcpu,pmem,etime,comm
compose exec -T ollama ollama ps
docker exec "$OLLAMA_CONTAINER" sh -c 'for f in /sys/fs/cgroup/cpu.max /sys/fs/cgroup/cpu.stat /sys/fs/cgroup/memory.events; do if [ -r "$f" ]; then printf "%s\n" "$f"; cat "$f"; fi; done'
```

2–5초 간격으로 측정 전/요청 중/클라이언트 타임아웃 후 값을 비교한다. CPU 제한 3코어에서 Docker CPU 300% 근처는 세 코어 사용을 뜻할 수 있다. `cpu.stat`의 throttled 시간/횟수 **증가량**, runner CPU, content 진행을 함께 본다. CPU가 높고 content가 증가하면 생성/평가 중일 가능성, 낮은 CPU와 장시간 무진행이면 대기/runner 정체 가능성이 있으나 그 수치만으로 확정하지 않는다. `memory.events`의 oom/oom_kill 증가 여부도 확인한다. 이미 sysstat가 설치되어 있으면 위에서 확인한 해당 runner PID만 `pidstat -p <PID> -t 1`로 관찰할 수 있다. 이 작업을 위해 패키지를 설치하거나 stock 등 다른 프로젝트 프로세스를 조사하지 않는다.

## 결과별 판단과 권장 해결 순서

| 측정 결과 | 우선 해석과 다음 조치 |
| --- | --- |
| load_duration 비중 큼 | 모델 전환/runner 준비·대기를 먼저 조사. MAX_LOADED_MODELS=1의 전환 비용을 포함하며 메모리 상한을 무작정 늘리지 않음 |
| prompt_eval_duration 비중 큼, 입력 증가에 따라 악화 | 후보/필드/규칙 축소를 검토. 실제 prompt_eval_count로 확인하고 정확한 근거 검증은 유지 |
| 첫 content 후 계속 진행, eval_duration 큼 | decode 처리량 병목. 출력 128 대 512의 실제 eval_count/속도를 비교 |
| 공백 위주 증가, length 종료, eval_count가 상한에 도달 | JSON/모델 출력이 불필요하게 길어질 가능성. 프롬프트 단순화·출력 축소를 별도 검토. 불완전 JSON은 성공 처리하지 않음 |
| thinking 수/marker 관측 | 설치 모델/template·false 옵션의 실제 처리 확인. 이유 텍스트를 로그로 확인하지 않음 |
| 비스트리밍 실패, 스트리밍은 진행하지만 같은 총 기한 내 미완료 | 중간 출력이 보이는 차이일 수 있음. 스트리밍 전환을 성능 해결로 단정하지 않음 |
| 작은 입력·출력 64/128도 느리고 generation TPS 낮음 | CPU용 더 작은/다른 양자화 LLM을 별도 승인 후 비교. 한국어·형식·근거 검증 품질도 함께 평가 |

필요 시간의 참고식은 `로딩/준비 + 프롬프트 평가 + 목표 생성 토큰 / 생성 TPS + 여유`다. 예를 들어 2 tokens/s이면 512개 생성만 약 256초이고 모델 전환 17초와 평가 시간이 추가된다. 300초에 출력 512개를 끝내려면 생성 외 시간이 0이어도 약 1.71 tokens/s가 필요하다. 실제 응답은 상한보다 일찍 멈출 수 있으므로 상한 대신 실제 eval_count도 확인한다. 관측이 없는 TPS를 가정해 timeout만 늘리지 않는다.

이번 최적화의 검증 순서는 **동일 모델·전체 digest 확인 → 별도 인스턴스의 1개/256 추천 → final metrics와 CPU 증가량 확인 → stream/non-stream 비교 또는 192/128 비교 → 2개 후보 비교**다. timeout/모델/서버 리소스는 변경하지 않는다. 작아진 입력·출력으로도 계속 실패하면 load/미확인 시간과 runner 상태를 따로 조사한다. 더 가벼운 LLM은 이번 범위에서 적용·다운로드하지 않으며 별도 승인·코드 검토가 필요하다. 임베딩 모델이나 저장된 벡터를 변경하지 않는다.

## 로컬 검증 범위

신규 Java Mock 테스트는 요청/응답 본문 비노출, duration/rate, 누락·잘못된 metadata, zero duration, 기존 옵션/반환값 유지, length 종료, HTTP 200 후 timeout을 확인한다. Python 테스트는 가짜 NDJSON/JSON을 BytesIO로 공급해 진행/최종 통계·불완전 응답·thinking·공백·입력 크기 제한과 비노출을 검사한다. 실제 Ollama·Docker·DB는 호출하지 않는다. 실제 지연의 최종 원인과 개선 효과는 위 측정 전까지 미검증이다.

2026-10-16 CPU 최적화 포함 Java 17 / Maven 3.9.16 오프라인 `package` 성공: 총 1024개 중 1009개 통과, 기존 live/manual 15개 skipped, 실패·오류 0이다. 추천 200개에는 기존 진단 12개와 신규 최적화 22개가 포함된다. Python 요약기 12개, 배포 보호 56개, React 단위 테스트 8개도 통과했다. 배포 테스트는 가짜 Docker/SSH와 로컬 config 검사를 사용하며 실제 컨테이너를 변경하지 않는다. 관련 문서 Bash 블록 14개는 문법만 검사했고, 가상 payload 36종은 로컬 메모리에서 생성·검사하여 본문을 출력하지 않았다.

로그는 `target/phase1-validation/rag-cpu-focused.log`, `rag-cpu-package.log`, `rag-cpu-parser-tests.log`, `rag-cpu-deployment-tests.log`다. 기존 Windows wrapper 초기화 문제 때문에 준비된 Maven과 로컬 캐시로 검사했으며 wrapper/의존성 파일은 수정하지 않았다. 운영 통합·Docker 이미지 빌드는 수행하지 않았다.
