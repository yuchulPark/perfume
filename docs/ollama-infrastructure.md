# 1단계: CPU 기반 Ollama 인프라

이 단계는 Docker 구성과 운영 절차만 추가합니다. Spring Boot/React 코드, PostgreSQL 17 이미지·DB·스키마·기존 볼륨은 유지합니다. pgvector, Spring AI, RAG 및 추천 화면은 다음 단계에서 구현합니다. **이 문서의 Ubuntu 실행 명령은 운영자가 최초 설정 때 수동 실행할 안내입니다. 이번 작업에서 서버 배포나 DB 변경은 수행하지 않습니다.**

## 구성

| 항목 | 설정 |
| --- | --- |
| Compose 프로젝트 / 서비스 | `perfume-dev` / `ollama` |
| 이미지 | `ollama/ollama:0.40.4` (버전 고정) |
| 실행 | 이미지 기본 `ollama serve`, CPU 추론, GPU 장치/런타임 사용 없음 |
| API | Docker 내부 `http://ollama:11434`, 호스트 `ports` 없음 |
| 모델 저장 | `perfume-dev_ollama-data` → `/root/.ollama` |
| 대화 모델 | `qwen3:4b-instruct` |
| 임베딩 모델 | `qwen3-embedding:0.6b` |
| 자원 상한 | CPU 3코어 상당, RAM 6 GiB |
| 추론 기본값 | 병렬 요청 1, 동시 로드 모델 1, 컨텍스트 4096, 유휴 유지 5분 |
| 로컬 실행 | `OLLAMA_NO_CLOUD=1`, `OLLAMA_VULKAN=0` |
| healthcheck | `ollama list`: API 응답만 검사, 모델 로드·다운로드 없음 |

16GB RAM / 4코어 VM에서 기존 stock, PostgreSQL, Jenkins와 웹 앱에 자원을 남기기 위한 초기 상한입니다. 모델 전환 시 로딩 지연이 있으며 CPU 응답 시간은 실제 VM에서 측정해야 합니다. API 요청에서 컨텍스트를 크게 늘리면 6 GiB 상한에 도달할 수 있으므로 우선 기본값을 유지합니다. [Ollama 동시성·메모리 설정](https://docs.ollama.com/faq)

통신 경로는 `backend → perfume-dev_ai (internal) → ollama:11434`입니다. `ai`에는 backend와 Ollama만 연결하며 frontend와 DB는 연결하지 않습니다. 모델을 수동 다운로드할 수 있도록 Ollama에만 별도 bridge 네트워크 `perfume-dev_ollama-download`를 연결합니다. backend는 기존처럼 인터넷 경로가 없는 내부 네트워크에만 연결됩니다. `expose: 11434`는 Docker 내부 포트 설명이며 호스트 포트를 게시하지 않습니다. [Compose 네트워크](https://docs.docker.com/reference/compose-file/networks/), [expose와 ports](https://docs.docker.com/reference/compose-file/services/#expose)

Ollama는 backend/frontend의 `depends_on`에 추가하지 않습니다. 모델이 없거나 Ollama가 정지해도 기존 웹 앱의 실행 조건은 유지됩니다. 현재 backend에는 AI 연결 설정이나 의존성을 추가하지 않았으며 다음 단계에서 내부 API 주소를 사용합니다. React에서 Ollama API를 직접 호출하지 않습니다.

이미지 기본 실행과 named volume 경로는 [Ollama Docker 안내](https://docs.ollama.com/docker)를 따릅니다. 이미지 버전은 [공식 v0.40.4 릴리스](https://github.com/ollama/ollama/releases/tag/v0.40.4)로 고정했습니다. 모델 이름은 공식 목록의 [qwen3:4b-instruct](https://ollama.com/library/qwen3:4b-instruct), [qwen3-embedding:0.6b](https://ollama.com/library/qwen3-embedding:0.6b)를 사용합니다.

## Ubuntu 최초 설치와 서비스 시작

기존 Ubuntu 24.04 개발서버의 Docker Engine / Compose 플러그인을 사용합니다. Docker가 없는 새 VM의 설치 절차는 [공식 Ubuntu 설치 안내](https://docs.docker.com/engine/install/ubuntu/)를 따릅니다. 기존 stock/Jenkins가 실행 중인 서버의 Docker 엔진을 이 작업과 함께 재설치하거나 재시작하지 않습니다. GPU 드라이버, NVIDIA Container Toolkit, 호스트 Ollama 설치는 필요 없습니다.

변경된 소스가 `/srv/perfume`에 준비되고 기존 `.env`와 DB가 유지된 상태에서 아래 명령을 실행합니다. 기존 `.env`를 복사하거나 덮어쓰지 않습니다. 설치·수동 관리 중에는 향수 Jenkins 배포가 겹치지 않도록 실행 시간을 조정합니다.

~~~bash
cd /srv/perfume
docker compose version
docker version --format '{{.Server.Version}}'
test -f /srv/perfume/.env

# 이후 예제는 같은 Bash 세션에서 이 함수를 사용합니다.
unset COMPOSE_FILE COMPOSE_PROJECT_NAME COMPOSE_PROFILES DB_NAME DB_USERNAME DB_PASSWORD
perfume_compose() {
    docker compose --project-name perfume-dev --project-directory /srv/perfume \
        --env-file /srv/perfume/.env --file /srv/perfume/compose.yaml "$@"
}

perfume_compose config --quiet
perfume_compose pull ollama
perfume_compose up --detach --no-deps --no-build --wait --wait-timeout 120 ollama
perfume_compose ps ollama
perfume_compose exec -T ollama ollama list
docker volume inspect perfume-dev_ollama-data --format '{{.Name}}'
~~~

서비스 대상은 `ollama`로 한정합니다. 기존 db/backend/frontend를 시작·교체하는 명령이 아닙니다. 모델이 없는 최초 실행에서도 `healthy`이고 `ollama list`는 빈 목록을 표시해야 합니다. 이미지 다운로드와 **모델 다운로드는 별개**입니다. 위 명령은 Ollama 이미지와 전용 모델 볼륨만 준비합니다.

## 모델 수동 다운로드

최초 설정 시 운영자가 아래 두 명령을 순서대로 실행합니다. 다운로드에는 Ollama 컨테이너의 외부 HTTPS 접근과 디스크 여유가 필요합니다. 모델/이미지/다운로드 임시 공간을 위해 먼저 `df -h`와 `docker system df`로 여유 공간을 확인합니다.

~~~bash
perfume_compose exec -T ollama ollama pull qwen3:4b-instruct
perfume_compose exec -T ollama ollama pull qwen3-embedding:0.6b
perfume_compose exec -T ollama ollama list
~~~

두 모델 이름이 목록에 표시되어야 합니다. 모델은 `perfume-dev_ollama-data` 안에 저장됩니다. Compose 시작, healthcheck, Jenkins에 모델 다운로드를 넣지 않았으므로 일반 앱 재배포에서 다운로드가 반복되지 않습니다. 다운로드가 중단되면 같은 모델의 `pull`을 수동 재실행합니다. 태그는 향후 내용이 바뀔 수 있으므로 설치 시 `ollama list`의 ID를 기록하고 모델 갱신은 별도 수동 작업으로 관리합니다.

## API 테스트: backend 컨테이너에서 실행

기존 backend가 새 Compose의 `ai` 네트워크에 연결된 이후 아래 명령을 실행합니다. 네트워크 추가는 컨테이너 생성 시 적용되므로, 기존 실행 중인 backend는 **다음 정상 Jenkins 앱 배포**를 거쳐야 연결됩니다. Ollama만 시작하는 앞선 명령은 backend를 재생성하지 않습니다.

backend 이미지에 이미 포함된 `curl`을 사용합니다. 테스트를 위해 호스트 11434를 게시하거나 Spring 코드를 수정할 필요가 없습니다. 모든 명령은 앞에서 정의한 `perfume_compose` 함수를 사용하는 Ubuntu Bash 명령입니다.

### API 준비 및 모델 목록

~~~bash
perfume_compose exec -T backend curl --fail --silent --show-error --max-time 10 \
    http://ollama:11434/api/version
perfume_compose exec -T backend curl --fail --silent --show-error --max-time 10 \
    http://ollama:11434/api/tags
~~~

`/api/version`은 버전 JSON, `/api/tags`는 두 모델을 포함하는 `models` 배열을 반환해야 합니다. DNS 오류가 나면 backend에 `perfume-dev_ai`가 연결되었는지 먼저 확인합니다.

### 대화 모델

~~~bash
perfume_compose exec -T backend curl --fail --silent --show-error --max-time 600 \
    -H 'Content-Type: application/json' --data-binary @- \
    http://ollama:11434/api/chat <<'JSON'
{
  "model": "qwen3:4b-instruct",
  "messages": [{"role": "user", "content": "시트러스 향과 우디 향의 차이를 한국어로 두 문장으로 설명해줘."}],
  "think": false,
  "stream": false,
  "options": {"num_ctx": 4096, "num_predict": 128, "num_thread": 3}
}
JSON
perfume_compose exec -T ollama ollama ps
~~~

HTTP 성공 응답에 `done: true`와 비어 있지 않은 `message.content`가 있어야 합니다. 곧바로 실행한 `ollama ps`의 `PROCESSOR`가 `100% CPU`인지 확인합니다. 이 요청은 모델 동작 확인이며 DB 향수 데이터에 근거한 추천은 다음 RAG 단계에서 구현합니다. [Chat API](https://docs.ollama.com/api/chat)

### 임베딩 모델

동시 로드 모델 상한이 1이므로 대화 모델을 메모리에서 내린 뒤 임베딩을 테스트합니다. `ollama stop`은 모델 파일을 삭제하지 않습니다.

~~~bash
perfume_compose exec -T ollama ollama stop qwen3:4b-instruct
perfume_compose exec -T backend curl --fail --silent --show-error --max-time 180 \
    -H 'Content-Type: application/json' --data-binary @- \
    http://ollama:11434/api/embed <<'JSON'
{
  "model": "qwen3-embedding:0.6b",
  "input": "여름에 어울리는 산뜻한 시트러스 향수",
  "truncate": false
}
JSON
~~~

HTTP 성공 응답에 입력 1건에 대응하는 `embeddings` 배열과 비어 있지 않은 숫자 벡터가 있어야 합니다. 여기서는 결과를 DB에 저장하지 않습니다. [Embed API](https://docs.ollama.com/api/embed)

## 포트·볼륨 확인 및 유지 관리

~~~bash
ollama_id="$(perfume_compose ps --quiet ollama)"
test -n "$ollama_id"
docker inspect --format '{{json .HostConfig.PortBindings}}' "$ollama_id"
docker inspect --format '{{range .Mounts}}{{if eq .Destination "/root/.ollama"}}{{.Type}} {{.Name}}{{end}}{{end}}' "$ollama_id"
docker inspect --format '{{json .NetworkSettings.Networks}}' "$ollama_id"
ss -ltn '( sport = :11434 )'
~~~

`PortBindings`는 `null` 또는 `{}`이고, 모델 마운트는 `volume perfume-dev_ollama-data`여야 합니다. 네트워크는 `perfume-dev_ai`와 `perfume-dev_ollama-download`만 표시되어야 합니다. 이 Compose는 호스트 11434 리스너를 만들지 않습니다. `ss`에 리스너가 있으면 다른 호스트 서비스인지 확인합니다. 컨테이너 IP를 직접 쓰거나 방화벽에 11434를 개방하지 않습니다.

Ollama 컨테이너만 재생성해도 같은 named volume을 다시 사용합니다. 운영자가 영속성을 확인하려면 다음 명령 전후의 모델 목록과 ID를 비교합니다.

~~~bash
perfume_compose exec -T ollama ollama list
perfume_compose up --detach --no-deps --no-build --force-recreate --wait --wait-timeout 120 ollama
perfume_compose exec -T ollama ollama list
~~~

로그·자원 사용 확인과 Ollama만 정지/재시작하는 명령:

~~~bash
perfume_compose logs --tail 100 ollama
docker stats --no-stream "$(perfume_compose ps --quiet ollama)"
perfume_compose stop ollama
perfume_compose start ollama
~~~

모델을 유지하려면 `down --volumes`, 모델 볼륨 삭제, `ollama rm`, Docker 전역 prune을 실행하지 않습니다. 기존 DB 볼륨 `perfume-dev_pgdata`와 stock의 볼륨·네트워크는 사용하지 않습니다.

## Jenkins 보호와 로컬 검증

`Jenkinsfile`, SSH 전달 및 앱 배포 스크립트는 유지합니다. Pipeline은 계속 `backend`/`frontend`만 `--no-deps`로 갱신하며 Ollama 설치·기동·모델 다운로드는 수동 관리합니다. 최초 서버 사전 검사는 서버의 기존 Compose/검사기를 사용하고, 소스 갱신 후에는 확장된 검사기가 새 구성을 검사합니다.

`scripts/validate-perfume-compose.py`는 기존 프로젝트명, PostgreSQL 이미지·볼륨·호스트 바인딩, DB 자격증명, 앱 빌드 컨텍스트 및 자동 스키마 변경 방지 조건을 유지합니다. Ollama에 대해서는 이미지·자원 상한·전용 볼륨, 외부 포트 미게시, 네트워크 분리, GPU 미사용 및 자동 모델 다운로드 금지를 추가 검증합니다.

서버 연결이나 컨테이너 시작 없이 실행할 수 있는 검사:

~~~bash
DB_PASSWORD=offline-test-password docker compose --project-name perfume-dev \
    --env-file .env.example --file compose.yaml config --quiet
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tests/deployment -v
~~~

Python 테스트는 Docker CLI가 있으면 실제 `compose.yaml`을 JSON으로 렌더링하여 보호 검사까지 검증합니다. Docker CLI가 없으면 그 검사 1건만 건너뛰고 나머지 오프라인 보호 테스트를 실행합니다. Docker 엔진이나 모델 다운로드는 필요하지 않습니다. 실제 컨테이너 health, CPU 추론, API 응답 및 재생성 후 모델 영속성은 위 수동 절차로 검증합니다.

이번 작업의 로컬 검증 결과: Compose 문법 및 실제 JSON 보호 검사 통과, 배포 보호 테스트 19개 통과, 이 문서의 Bash 블록 9개 문법 검사와 API JSON 2개 파싱 통과, 기존 Jenkins Bash 스크립트 2개 문법 검사 통과. 로컬 Docker 엔진이 실행되지 않아 컨테이너 기동·실제 API·모델 영속성 실행 테스트는 수행하지 못했습니다. 모델 다운로드, 실제 서버 접속·배포 및 DB 변경은 수행하지 않았습니다.
