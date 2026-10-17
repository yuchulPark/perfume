# 기존 웹 백엔드의 AI 검색·추천 활성화

이 문서는 **관리자가 이후 Ubuntu에서 수행할 절차**다. 이번 변경에서는 서버 접속, Docker/DB/Ollama 호출, `.env` 변경, Commit/Push, Jenkins 실행을 하지 않는다.

서비스 주소는 **http://192.168.0.19:8088**, frontend 포트는 기존 `8088:80`이다. AI용 호스트 포트나 SSH 터널을 추가하지 않는다. PostgreSQL/pgvector/Ollama 볼륨, 기존 임베딩, Checkpoint와 자동 임베딩 예약은 변경하지 않는다. 추천 범위는 동일 모델로 임베딩된 일부 향수의 저장 정보이며 개수를 고정하지 않는다.

## 개발 LAN 전용

AI API에는 인증과 사용자별 사용량 제한이 없다. LAN에 접근할 수 있는 사용자가 CPU·메모리와 긴 처리 시간을 소비하는 요청을 보낼 수 있다. 동시 실행 1개와 기한 제한은 사용자 인증·사용량 제한을 대신하지 않는다. 신뢰할 수 있는 개인 개발 LAN에서만 활성화한다. 인터넷 포트 전달이나 공개 프록시로 노출하지 않는다. 외부 공개에는 먼저 인증, 사용자별 요청/사용량 제한과 접근 통제가 필요하다. 질문·답변을 프록시/HTTP body 로그에 추가하지 않는다.

## 연결되는 설정

`compose.yaml`의 backend 환경변수는 다음과 같다. 활성화 플래그와 Digest **4개만** 서버 `/srv/perfume/.env`에서 주입한다. 모델과 제한값은 Compose의 고정 문자열이다. 환경변수 이름은 Spring `Environment.getProperty()`와 `@ConditionalOnProperty`에서 실제로 해석되는지 오프라인 테스트한다.

| 환경변수 | Spring 설정 | 값/기본값 |
| --- | --- | --- |
| `PERFUME_AI_SEARCH_ENABLED` | `perfume.ai.search.enabled` | `.env` 주입, 기본 `false` |
| `PERFUME_AI_SEARCH_MODEL_DIGEST` | `perfume.ai.search.model-digest` | `.env` 주입, 기본 빈 값 |
| `PERFUME_AI_RECOMMEND_ENABLED` | `perfume.ai.recommend.enabled` | `.env` 주입, 기본 `false` |
| `PERFUME_AI_RECOMMEND_MODEL_DIGEST` | `perfume.ai.recommend.model-digest` | `.env` 주입, 기본 빈 값 |
| `PERFUME_AI_SEARCH_MODEL` | `perfume.ai.search.model` | `qwen3-embedding:0.6b` |
| `PERFUME_AI_SEARCH_READ_TIMEOUT_MS` | `perfume.ai.search.read-timeout-ms` | `60000` |
| `PERFUME_AI_SEARCH_REQUEST_TIMEOUT_MS` | `perfume.ai.search.request-timeout-ms` | `120000` |
| `PERFUME_AI_SEARCH_MAX_CONCURRENT` | `perfume.ai.search.max-concurrent` | `1` |
| `PERFUME_AI_RECOMMEND_MODEL` | `perfume.ai.recommend.model` | `qwen3:4b-instruct` |
| `PERFUME_AI_RECOMMEND_READ_TIMEOUT_MS` | `perfume.ai.recommend.read-timeout-ms` | `300000` |
| `PERFUME_AI_RECOMMEND_GENERATION_TIMEOUT_MS` | `perfume.ai.recommend.generation-timeout-ms` | `300000` |
| `PERFUME_AI_RECOMMEND_REQUEST_TIMEOUT_MS` | `perfume.ai.recommend.request-timeout-ms` | `420000` |
| `PERFUME_AI_RECOMMEND_MAX_CONCURRENT` | `perfume.ai.recommend.max-concurrent` | `1` |

추천 제한은 backend 전체 **420초 → Nginx AI 추천 엔드포인트 450초 → React 480초** 순서를 유지한다. 검색·추천 모두 자동 재시도하지 않는다. Ollama CPU 3개, 메모리 6GB, 병렬 1개, 동시 로드 모델 1개, context 4096은 기존 값을 유지한다.

`scripts/validate-perfume-compose.py`는 플래그의 `true/false`, Digest 형식·필수 조건, 추천 활성화 시 검색도 활성화되어야 하는 조건, 모델·기한·동시 실행 수를 검증한다. 잘못된 설정은 앱 build/up 전에 거부한다. 이전 Compose에 AI 환경변수가 전혀 없으면 최초 업그레이드를 위한 기존 비활성 상태로 허용한다. 새 블록의 일부 누락, 추가 배치/Import/임의 Spring 환경변수는 거부한다.

## 1. 먼저 비활성 상태로 코드 배포

1. 변경을 검토하고, 관리자가 별도 승인·작업으로 main에 반영한다. 이번 작업에서는 Commit/Push하지 않는다.
2. 기존 Jenkins main Pipeline을 실행한다. 처음에는 AI 플래그를 미설정하거나 둘 다 `false`로 둔다.
3. Jenkins는 이전 checkout 사전 검증 → 새 revision 가져오기 → 새 Compose 검증 → build → backend/frontend만 `up --no-deps --no-build` 순서로 처리한다. DB/Ollama는 재생성하지 않는다.
4. Pipeline 성공을 기다린다. 아래 `.env` 편집 중에는 Job의 자동/수동 실행을 중지하고, 기존 배포 잠금을 삭제하거나 우회하지 않는다.

기존 흐름은 [Jenkins 배포](jenkins-perfume-deployment.md), DB 이미지 승인은 [pgvector 배포](pgvector-production-deployment.md)를 참고한다. AI를 활성화해도 DB 비밀번호 일치, 승인 이미지, 고정 볼륨, 읽기 전용 스키마 검증, Import 비활성화, 배포 잠금 검사는 유지한다. Jenkins는 `.env`를 덮어쓰지 않는다.

## 2. Ubuntu 관리자 셸 준비

기존 `/srv/perfume/.env`를 그대로 사용한다. `.env.example`을 기존 파일에 복사하지 않는다. `cat .env`, 전체 `compose config`, 필터 없는 `docker inspect`, `set -x`로 DB 비밀번호를 출력하지 않는다.

```bash
cd /srv/perfume
set +x
umask 077
test -f .env && test ! -L .env
test ! -L .perfume-deploy.lock
unset COMPOSE_FILE COMPOSE_PROJECT_NAME COMPOSE_PROFILES
unset DB_NAME DB_USERNAME DB_PASSWORD DB_URL SCENTREV_API_KEY PERFUME_DB_IMAGE
unset PERFUME_AI_SEARCH_ENABLED PERFUME_AI_SEARCH_MODEL_DIGEST
unset PERFUME_AI_RECOMMEND_ENABLED PERFUME_AI_RECOMMEND_MODEL_DIGEST
compose() {
  docker compose --project-name perfume-dev --project-directory /srv/perfume \
    --env-file /srv/perfume/.env --file /srv/perfume/compose.yaml "$@"
}
```

셸의 같은 이름 변수가 `.env`보다 우선하지 않도록 해제한다. Jenkins 배포 스크립트도 동일한 AI 변수 4개를 해제한다. `.env`를 `source`하지 않는다.

## 3. 기존 모델의 전체 Digest 확인

상시 실행 중인 backend 컨테이너에서 내부 네트워크의 Ollama `/api/tags`를 읽는다. 추론·재임베딩·모델 다운로드는 실행하지 않는다. backend 런타임에는 기존 curl이 있다.

```bash
compose exec -T backend curl --fail --silent --show-error --max-time 10 \
  http://ollama:11434/api/tags | python3 -c '
import json, re, sys
models = json.load(sys.stdin).get("models", [])
for name in ("qwen3-embedding:0.6b", "qwen3:4b-instruct"):
    found = [m for m in models if (m.get("name") or m.get("model")) == name]
    if len(found) != 1 or not re.fullmatch(r"(?:sha256:)?[0-9a-f]{64}", found[0].get("digest", "")):
        sys.exit("Required installed model or full digest is unavailable; stop here.")
    print(name + "=" + found[0]["digest"])
'
```

단축 ID, Docker 이미지 Digest, 태그 이름으로 대체하지 않는다. 조회 값에 이미 `sha256:`가 있으면 접두사를 중복 추가하지 않는다. 검색용과 생성용은 각각 해당 모델의 값을 사용한다. 검색 모델은 기존 임베딩에 사용한 모델과 일치해야 한다. 불일치 시 기존 데이터를 자동 재임베딩하거나 모델을 pull하지 않는다.

## 4. `.env`의 AI 4개 행만 편집

Jenkins가 실행 중이 아닌지 확인하고 기존 배포 잠금을 사용한다.

```bash
flock --exclusive /srv/perfume/.perfume-deploy.lock nano /srv/perfume/.env
```

다음 **4개 행만** 추가/변경한다. `<...>`는 앞에서 확인한 실제 소문자 16진수 64자리로 바꿀 자리표시자이며, 그대로 사용하면 검증에 실패한다. 같은 이름의 행을 중복 추가하지 않는다. DB 연결·비밀번호·승인 DB 이미지 등 기존 행은 변경하지 않는다.

```dotenv
PERFUME_AI_SEARCH_ENABLED=true
PERFUME_AI_SEARCH_MODEL_DIGEST=sha256:<embedding_model_full_64_hex>
PERFUME_AI_RECOMMEND_ENABLED=true
PERFUME_AI_RECOMMEND_MODEL_DIGEST=sha256:<generation_model_full_64_hex>
```

비활성 상태를 유지하려면 두 플래그를 `false`, 두 Digest를 빈 값으로 둘 수 있다. 비활성 상태라도 빈 값이 아닌 Digest는 형식을 검사한다. 타임아웃이나 모델 이름은 `.env`에 추가하지 않아도 된다.

## 5. 민감한 정보 출력 없이 Compose 확인

아래 절차는 DB 비밀번호를 화면에 표시하지 않고 입력받아, Jenkins와 같은 검증기로 기존 DB 설정과의 일치를 검사한다. 비밀번호를 명령 인자·기록에 쓰지 않는다. JSON은 검증기에 직접 전달하고 파일이나 화면에 출력하지 않는다.

```bash
(
  set -euo pipefail
  read -r -s -p 'Existing DB password, same as Jenkins credential: ' PERFUME_EXPECTED_DB_PASSWORD
  printf '\n'
  export PERFUME_EXPECTED_DB_PASSWORD
  compose config --quiet
  compose config --format json | python3 scripts/validate-perfume-compose.py \
    --deploy-directory /srv/perfume \
    --approval-file /srv/perfume/.env.pgvector-approval.json
)
```

`Perfume deployment configuration verified.`를 확인한다. 이 단계에서는 컨테이너/DB 갱신이나 모델 호출을 하지 않는다. Digest의 형식은 검사하지만 실제 모델과의 일치는 기존 백엔드가 요청 시 확인한다. 형식이 올바르더라도 실제 값이 다르면 `503/MODEL_MISMATCH` 등으로 안전하게 실패하며 다른 모델로 전환하지 않는다.

## 6. Jenkins에서 환경변수 반영

같은 main의 기존 Pipeline을 다시 실행하고 사전 검증·테스트·빌드·앱 배포 성공을 기다린다. `restart backend`만으로는 변경한 환경변수가 반영되지 않는다. 기존 스크립트의 Compose `up --no-deps --no-build`를 통한 앱 재생성을 사용한다.

Jenkins 검사는 저장된 카탈로그를 사용하며 자동으로 AI 추론을 호출하지 않는다. DB·모델 볼륨을 유지하고 DB 컨테이너 ID와 `.env` 해시를 배포 전후에 검증한다. 자동 임베딩 예약·ScentRev Import는 활성화하지 않는다.

## 7. 정상 동작 수동 확인

AI 검증은 한 번에 요청 하나씩 순서대로 진행하며 다른 AI/임베딩 작업과 겹치지 않는다. 자동 재시도를 추가하지 않는다.

```bash
curl --fail --silent --show-error --max-time 15 \
  http://192.168.0.19:8088/healthz
compose exec -T backend curl --fail --silent --show-error --max-time 135 \
  -H 'Content-Type: application/json' \
  --data '{"query":"시트러스 노트가 있는 향수를 찾아줘","topK":5}' \
  --write-out '\nHTTP %{http_code}\n' \
  http://127.0.0.1:8081/api/ai/perfumes/search
curl --fail --silent --show-error --max-time 480 \
  -H 'Content-Type: application/json' \
  --data '{"query":"산뜻한 시트러스 향을 좋아해요. 저장된 정보를 바탕으로 추천해 주세요.","topK":5}' \
  --write-out '\nHTTP %{http_code}\n' \
  http://192.168.0.19:8088/api/ai/perfumes/recommend
```

검색은 `HTTP 200`과 `results/returnedCount`, 추천은 `HTTP 200`과 `answer/sources/returnedCount`를 확인한다. 후보가 없으면 `200`과 빈 배열도 정상이다. 웹 `/recommend`에서도 제출·근거 카드·상세 링크를 확인한다.

검색 단독 확인은 backend 내부에서 수행한다. 기존 Nginx의 일반 `/api` 읽기 제한은 30초이며, 추천의 정확한 경로만 450초다. 검색 API를 frontend 경유로 직접 호출하면 backend의 120초보다 먼저 Nginx에서 타임아웃될 수 있다. 이번 변경에서는 frontend를 수정하지 않는다.

`503/*_DISABLED`는 플래그와 앱 재생성, `503/MODEL_MISMATCH`는 전체 Digest, `429/*_BUSY`는 진행 중 요청, `504`는 제한 시간을 확인한다. 타임아웃이나 클라이언트 연결 종료 후에도 모델 계산이 즉시 끝나지 않을 수 있다. 재시도를 연속 실행하거나 DB 초기화·임베딩 삭제·모델 재다운로드로 해결하려 하지 않는다.

## 8. AI 비활성화 롤백

Jenkins 실행을 중지하고 잠금 아래에서 `.env`의 **두 플래그만** 다음으로 되돌린 후, 검증을 거쳐 같은 Jenkins Pipeline을 다시 실행한다. 올바른 Digest는 남겨도 된다. 앱 환경만 다시 반영하며 DB·Ollama·임베딩 데이터에는 손대지 않는다.

```dotenv
PERFUME_AI_SEARCH_ENABLED=false
PERFUME_AI_RECOMMEND_ENABLED=false
```

배포 성공 후 일반 카탈로그는 동작하고 위 AI POST는 각각 `503/SEARCH_DISABLED`, `503/RECOMMEND_DISABLED`가 되는지 확인한다. `curl --fail`은 예상된 503에도 비정상 종료 코드를 반환한다. 검색만 유지하려면 검색 `true`·추천 `false`로 두고 검색의 올바른 Digest를 유지한다.

`docker compose down`, `down -v`, DB/Ollama `up`/재생성, 마이그레이션, 전체 `.env` 덮어쓰기, 잠금 삭제는 필요 없다.

## 오프라인 검증 범위

Java는 실제 Spring 환경변수 해석·조건부 bean·시작 전 Digest 거부·schema validate/SQL init never를 검증한다. Python은 실제 파일의 AI 문자열 설정 추출과 Compose 메타데이터, Jenkins 셸을 사용한 Mock 배포, DB/볼륨/자격증명/잠금 보호를 검증한다. 설정 추출은 일반 YAML parser를 대체하지 않으며, 실제 `docker compose config`와 위 실 API 확인은 관리자가 이후 수행한다.

2026-10-18 로컬 검증 결과:

- 관련 Maven 테스트 **345개 통과**, 실패·오류·skipped 0개. 새 환경변수 테스트 14개와 기존 검색·추천·Ollama Mock·자동 임베딩 설정 회귀를 포함한다. 원본 POM의 의존성을 유지하고 빌드 출력·임시 경로만 분리한 `target/ai-lan-validation-pom.xml` 및 기존 Maven 캐시로 오프라인 실행했다. 원본 `pom.xml`은 수정하지 않았다.
- 배포 보호 Python 테스트 **79개 중 76개 통과, 3개 skipped**. 실제 Docker CLI를 호출하는 기존 Compose 검사 3개는 이번 작업의 호출 금지에 따라 의도적으로 건너뛰었다. 새 AI Compose/배포 테스트 9개를 포함하며 모든 배포 외부 동작은 Mock이다. 검색 단독 활성 상태에서 생성 모델 Digest를 비워 두는 경우도 별도로 통과했다.
- Jenkins 배포 스크립트와 이 문서의 Bash 블록 **5개** 문법 검사, `git diff --check` 통과. 문서의 운영 명령을 실제로 실행한 결과가 아니다.

로그는 `target/ai-lan-maven-tests.log`, `target/ai-lan-deployment-tests.log`, Java 상세 결과는 `target/ai-lan-validation/surefire-reports/`에 있다. 실제 Docker/DB/Ollama 호출, Ubuntu 접속, Jenkins 배포, Commit/Push, 실제 `.env` 변경은 하지 않았다.
