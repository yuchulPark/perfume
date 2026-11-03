# Jenkins 배포 후 Elasticsearch 검색 유지

2026-11-03 로컬 구현. 이번 작업은 로컬 코드 수정과 테스트만 수행했다. Jenkins 실행, Ubuntu 접속·파일 변경, 컨테이너/DB 조작, 색인 실행, Git commit/push는 수행하지 않았다. 아래 서버 설정은 운영자가 기존 설정을 검토할 때의 기준이며 자동 적용하지 않는다.

## 활성화 결정

`scripts/jenkins-deploy.sh`는 **Ubuntu `/srv/perfume/.env`만** 기준으로 두 flag를 각각 읽는다. Jenkins Credentials/Job 변수나 SSH 로그인 환경의 Elasticsearch·인증 설정은 지우고, `.env`를 shell `source`로 실행하지 않는다.

| 서버 `.env` | 적용하는 Compose 파일 (순서대로) |
| --- | --- |
| ES flag 없음 또는 `false`, HTTP flag 없음 또는 `false` | `compose.yaml` |
| ES flag 없음 또는 `false`, HTTP flag `true` | `compose.yaml` → `compose.auth-dev-http.yaml` |
| ES flag `true`, HTTP flag 없음 또는 `false` | `compose.yaml` → `compose.elasticsearch-app.yaml` → `compose.elasticsearch-jenkins.yaml` |
| ES flag `true`, HTTP flag `true` | `compose.yaml` → `compose.auth-dev-http.yaml` → `compose.elasticsearch-app.yaml` → `compose.elasticsearch-jenkins.yaml` |

Flag 이름은 `PERFUME_ELASTICSEARCH_ENABLED`, `PERFUME_AUTH_DEV_HTTP_ENABLED`다. 허용 값은 소문자 `true`/`false`이며 단일/이중 따옴표, 앞뒤 공백, 주석은 허용한다. 누락은 `false`, 빈 값·`TRUE`·`1`·보간식·중복 선언은 배포 오류다. 잘못된 값을 임의로 비활성화하지 않는다. 이 결정은 `perfume-dev` 고정 개발서버 배포에만 사용한다.

기존 HTTP 개발 환경의 검토 예시는 다음과 같다. **기존 `.env`를 이 예제로 교체하지 않는다.** DB/AI digest/기타 기존 값은 그대로 두고, 이미 설정된 ES flag가 `true`라면 추가 설정이 필요하지 않다.

```dotenv
PERFUME_AUTH_ENABLED=true
PERFUME_AUTH_DEV_HTTP_ENABLED=true
PERFUME_SUPPORT_ENABLED=true
PERFUME_SUPPORT_ALLOWED_ORIGINS=http://192.168.0.19:8088
PERFUME_ELASTICSEARCH_ENABLED=true
```

`compose.auth-dev-http.yaml`의 `auth-dev-http` profile, 기존 LAN origin, HTTP 개발용 쿠키 설정은 그대로 병합한다. Secure cookie가 기본인 다른 개발 구성도 기존 인증 검증을 유지한다. AI 검색·RAG, 고객센터 flag/origin은 기존 경로로 전달하며 ES flag와 독립적이다. URL·Alias·CA/key 경로는 검토된 연결 override의 고정값을 사용하고, 임의의 SSH 환경변수로 바꾸지 못한다.

## Elasticsearch 수명 주기 분리

Jenkins는 ES 서비스 정의가 있는 `compose.elasticsearch.yaml`과 수동 job 정의인 `compose.elasticsearch-reindex.yaml`을 사용하지 않는다. 선택하는 `compose.elasticsearch-app.yaml`에는 backend의 연결 설정만 있고, 새 `compose.elasticsearch-jenkins.yaml`에는 다음 네트워크 참조만 있다.

```yaml
networks:
  search:
    name: perfume-dev_search
    external: true
```

여기서 `external`은 네트워크를 Compose가 만들거나 삭제하지 않는다는 의미다. 인터넷 공개 설정이 아니다. 스크립트는 실제 네트워크가 `perfume-dev_search`, local bridge, `Internal=true`, 프로젝트 `perfume-dev`/네트워크 `search` 라벨인지 읽기 전용 검사한다. 네트워크가 없거나 격리가 다르면 자동 생성·수정하지 않고 배포를 중단한다. [Docker external network](https://docs.docker.com/reference/compose-file/networks/#external)

실제 갱신 대상은 기존과 같은 `backend`, `frontend`뿐이다. build도 이 두 서비스만 대상으로 하고, `up`에는 `--no-deps --no-build`를 유지한다. ES·PostgreSQL·Ollama 컨테이너를 start/recreate/remove 하지 않고 기존 데이터·모델 볼륨을 구성에 추가하지 않는다. DB 이미지 승인·확장/버전·볼륨·컨테이너 ID 보호 검증도 유지한다.

ES는 Jenkins 모델에 없으므로 Compose가 orphan 경고를 출력할 수 있다. 이를 지우려고 `--remove-orphans`를 추가하면 안 된다. 배포 스크립트는 `COMPOSE_REMOVE_ORPHANS=false`를 강제하여 SSH 환경이나 `.env`의 자동 orphan 삭제 설정도 덮어쓴다. `down`, `prune`, `rm`, 재색인·Alias 교체 명령은 자동 배포에 없다. [Compose orphan 환경변수](https://docs.docker.com/compose/how-tos/environment-variables/envvars/#compose_remove_orphans)

## 최초 적용과 이후 배포

Jenkins는 테스트한 새 `jenkins-deploy.sh`를 SSH stdin으로 전달하지만, 첫 사전 검사 시 서버 작업사본은 이전 revision일 수 있다. 따라서 다음 두 단계로 처리한다.

1. 이전 validator에 `--elasticsearch-enabled` 기능이 없으면, 첫 사전 검사는 기존 base/HTTP 구성과 DB 보호 검증으로 수행한다. `Elasticsearch connection validation deferred...`를 출력하며 기존 앱은 바꾸지 않는다. pgvector를 지원하는 이전 validator와 최초 구형 validator 호환 경로를 유지한다.
2. 검토된 revision으로 checkout한 뒤에는 새 validator, 두 ES 연결 override, 네트워크와 secret 검증이 반드시 성공해야 한다. ES opt-in이 `true`인데 새 검증 기능이 없으면 backend/frontend build·교체 전에 중단한다. 조용히 SQL 모드로 배포하지 않는다.

새 validator가 이미 있는 이후 배포는 첫 사전 검사부터 ES 연결 구성을 검증한다. `.env`·DB 표식·이미지 승인 파일의 보존 검사는 기존처럼 수행한다. 서버 `.env`가 배포 중 바뀌면 배포를 중단한다. 성공 로그에는 비밀 값 대신 `Catalog Elasticsearch opt-in from server .env: true|false`만 출력한다.

`Jenkinsfile`의 stage/credentials/trigger와 SSH 전달 절차는 바꾸지 않았다. ES key를 Jenkins Credentials에 추가하거나 Jenkins에 CA를 설치할 필요가 없다. 기존 서버의 승인된 파일을 backend에 마운트한다.

## TLS와 검색 key 보존

Jenkins가 사용하는 파일은 다음 두 개뿐이다.

| 호스트의 기존 파일 | backend 읽기 경로 |
| --- | --- |
| `/srv/perfume/.secrets/elasticsearch/node/ca.crt` | `/run/secrets/elastic_ca` |
| `/srv/perfume/.secrets/elasticsearch/search-api-key` | `/run/secrets/elastic_search_key` |

검색 key는 기존 읽기 전용 역할을 유지한다. 색인 key, bootstrap 비밀번호, CA 개인키, ES 서버 개인키는 backend에 전달하지 않는다. 값은 환경변수·빌드 인자·로그에 포함하지 않는다. 인증서는 기존 CA 검증과 hostname 검증을 유지하며 HTTP URL/trust-all로 바꾸지 않는다.

보호 검사는 고정 source/target의 정확한 두 Compose secret만 허용한다. 파일/상위 경로의 symlink, 빈 파일, POSIX에서 API key의 그룹·기타 사용자 권한 또는 CA의 그룹·기타 사용자 쓰기 권한을 거부한다. API key의 권한 예시는 기존 `0400` 또는 `0600`이며, backend UID가 읽을 수 있어야 한다. 배포 계정에는 상위 디렉터리 탐색과 파일 메타데이터 조회 권한이 필요하지만 key 내용을 읽는 권한은 요구하지 않는다. rootless/user namespace 환경의 UID 매핑은 운영자가 기존 정상 구성을 유지해야 한다.

스크립트는 inode/device·소유자/권한·크기·mtime/ctime의 메타데이터 fingerprint만 비교하며 key 내용을 읽거나 hash하지 않는다. 기존 파일을 생성·교체·chmod/chown하지 않는다. 배포 중 파일 회전/교체를 감지하면 중단하므로 key 갱신과 배포는 겹치지 않게 수행한다. incoming Git revision이 `.secrets` 또는 그 하위 파일을 추적하면 `git reset` 전에 거부한다. `.secrets`·`.env`를 지우는 Git clean도 수행하지 않는다.

ES 비활성 환경에서는 해당 secret 파일과 검색 네트워크를 요구하거나 검사하지 않는다.

## 장애와 PostgreSQL 복귀

네트워크와 key 파일이 유지되는 상태에서 ES 컨테이너만 잠시 중단돼도 자동 배포는 가능하다. 스크립트는 ES healthy 상태나 검색 API 응답을 요구하지 않으며, backend healthcheck는 검색어 없는 PostgreSQL 목록 조회를 사용한다.

ES 연결 실패·인증 실패·시간 초과에는 기존 웹 검색 서비스가 PostgreSQL LIKE로 복귀하고 15초 동안 ES 재호출을 건너뛴다. 복구 후 요청에서 ES를 다시 시도한다. 일반 웹 검색의 연결 제한 300ms/HTTP 요청 제한 1.5초는 유지한다. 실제 ES가 중단되면 노트·동의어·오타 검색과 관련성 순위는 일시적으로 기존 이름·브랜드 LIKE 범위로 줄어든다.

영구적으로 SQL 모드를 사용하려면 운영자가 **서버 `.env`의 기존 flag를 `false`로 변경**한 뒤 다음 승인된 앱 배포를 수행한다. Jenkins는 ES 연결 override 없이 기존 base/HTTP 구성만 사용한다. key 파일과 ES 컨테이너·네트워크·인덱스·Alias는 그대로 남는다. flag를 Jenkins Job 변수에서만 바꾸면 서버 `.env`를 덮어쓰지 못하므로 효과가 없다.

실제 네트워크까지 삭제됐거나 key 파일이 없고 flag는 `true`이면 새 앱 교체 전에 배포를 중단한다. 이 경우 기존 리소스를 운영자가 복구하거나 명시적으로 SQL 모드로 전환한다. 자동으로 key를 재발급하거나 ES를 재설치하지 않는다.

## 로컬 검증과 미검증 범위

2026-11-03 최종 결과:

| 검증 | 결과 |
| --- | --- |
| 배포 보호 전체 | 114개 중 113개 통과, POSIX 전용 1개 건너뜀, 실패/오류 0 |
| Java 전체 | 1,322개 중 1,304개 통과, 기존 조건부 18개 건너뜀, 실패/오류 0 |
| React | 60개 통과 |
| 실제 Compose 렌더링 | HTTP/ES/고객센터 결합 포함 4개 통과 |
| 셸/Python 문법·공백 | `bash -n`, Python 컴파일, diff 검사 통과 |

- 실제 Docker Compose CLI의 `config --format json` 렌더링으로 base/HTTP/ES 결합, 고정 secret 경로, 기존 서비스·볼륨·healthcheck·의존성 유지를 검사한다. daemon/컨테이너는 사용하지 않는다.
- 배포 shell은 임시 작업사본에서 Docker/Git/SSH 경계를 대체한 mock으로 실행한다. HTTP+ES+RAG+pgvector+고객센터, 비활성·불법 flag·상속 환경 무시, 최초 구버전 upgrade, 신규 검증 누락 중단, key 누락/회전, 네트워크 격리, incoming secret 추적 차단을 검증한다.
- Java 전체 회귀에는 ES 실패 시 SQL 복귀, 기존 RAG·인증·WebSocket 테스트가 포함된다. React 테스트도 수행한다.
- 실제 Jenkins/Ubuntu 배포, 실제 ES 중단 실험, 서버 파일 소유권·Docker 권한 검증은 미실행이다. 로컬 Windows에서 POSIX mode/symlink 통합 검증은 건너뛰며 해당 테스트는 Linux CI에서 실행된다.

표준 명령은 `python -m unittest discover -s tests/deployment -v`, `./mvnw test`, `cd frontend && npm test`다. 이번 sandbox에서는 Maven cache/출력을 기존 검증용 `target/elasticsearch-*` 경로에 격리해 실행했다. 결과 로그는 `target/elasticsearch-jenkins-*.log`에 있으며 Git에 저장하지 않는다.
