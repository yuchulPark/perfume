# PostgreSQL 17.11 / pgvector 0.8.7 운영 전환

대상은 `devops@192.168.0.19`의 `/srv/perfume`, Compose `perfume-dev`의 **db 서비스만**이다. DB `perfume`, 사용자 `perfume_user`, 기존 비밀번호, `perfume-dev_pgdata:/var/lib/postgresql/data`를 유지한다. 주식 프로젝트와 Ollama는 전환 대상이 아니다.

**아래 명령은 별도 승인 후 운영자가 Ubuntu에서 수동 실행할 절차다. 이번 코드 준비에서는 SSH 접속, 운영 이미지 전환, DB 중지·재생성, `.env` 변경, extension/schema 생성, Commit/Push, Jenkins 실행을 하지 않았다.** 이미지 승인과 실제 전환은 별개의 수동 단계다.

## 이미지 선택과 보관

| 설정/파일 | 역할 |
| --- | --- |
| `PERFUME_DB_IMAGE` 없음/빈 값 | 기존 `postgres:17-bookworm` 유지 |
| `PERFUME_DB_IMAGE=sha256:<전체 Image ID>` | 서버 승인 파일과 일치하는 파생 이미지만 허용 |
| 원본 `postgres:17-bookworm@sha256:3645570cccdfa447589da9f57dd740faa29b30938e861289a5574b6ca6b03826` | 확인된 17.11; extension 생성 전 롤백용 |
| `.env.pgvector-approval.json` | 운영자 승인 기록; Git 제외, symlink 및 그룹/전체 쓰기 권한 금지 |
| `.perfume-db-ready` | 기존 `perfume-dev_pgdata` 값 유지 |
| `.perfume-deploy.lock` | 기존 Jenkins와 수동 전환이 공유하는 잠금 |

이번 구성은 **Ubuntu Docker Engine의 로컬 이미지 저장소**를 사용한다. Compose에는 태그 대신 전체 Image ID를 넣는다. 식별용 `perfume-postgres:pg17.11-vector0.8.7` 태그는 보조 이름이다. `docker image save` archive와 SHA-256, 승인 JSON을 비공개 `backups/`에 보존한다. registry 업로드는 필요하지 않으며 자동 pull도 하지 않는다. 로컬 이미지가 없어지면 배포를 중단하고 보존한 archive를 검증해 복구한다.

승인에는 base digest·Image ID·PostgreSQL/package/platform·빌드 label·PostgreSQL 실행 파일 및 vector 라이브러리/control 해시가 들어간다. label만으로 출처를 증명하지 않으므로 **검토한 `docker/pgvector/Dockerfile`로 직접 제작하고 리허설을 통과한 이미지**만 승인한다. 후보 태그를 한 번 해석한 뒤 모든 probe는 해당 Image ID로 수행한다. base와 후보의 실행 기본값과 PostgreSQL 바이너리가 같아야 한다.

Compose 환경변수 치환은 허용 목록 검사 기능이 아니다. 검증을 생략한 raw `docker compose up db`는 보호 검사기를 우회하므로 아래 순서를 지킨다. Jenkins 경로는 항상 검사기를 거친다.

## 1. 코드 적용 승인, 쓰기 중단, 배포 잠금

로컬 변경을 검토하고 **사용자의 별도 승인 후** Commit/Push와 Jenkins의 앱 배포를 진행한다. 이 최초 코드 배포에서 DB는 원본 17.11을 유지한다. 서버의 이전 검사기를 호출하는 첫 preflight는 원본 digest만 허용하며, 새 checkout 적용 후에는 새 검사기를 반드시 사용한다. Jenkins Job이나 Credentials를 추가·수정할 필요는 없다.

실제 전환 일정에는 Eclipse Import, 다른 Import, 향수 등록·수정 등 모든 DB 쓰기를 종료한다. 전환·검증 완료까지 다시 실행하지 않는다. **PuTTY 로그인 셸에서 `set -euo pipefail`을 직접 실행하지 않는다.** 작업용 자식 Bash 안에서만 오류 즉시 중단을 적용한다.

PuTTY의 기존 로그인 셸에서 아래 실행 명령을 사용한다. 이전에 strict 옵션을 켰다면 먼저 해제한다. `||` 분기는 작업용 셸이 실패해도 로그인 셸이 종료되지 않도록 한다. 스크립트를 `source`하거나 `. scripts/...`로 실행하지 않는다.

```bash
# Restore the login shell if strict options were enabled there previously.
set +e
set +u
set +o pipefail
bash /srv/perfume/scripts/pgvector-maintenance-shell.sh || \
  printf '작업용 셸이 종료됐습니다. 기존 PuTTY 로그인 셸은 유지됩니다.\n'
```

`(perfume-maintenance)` 프롬프트가 보일 때만 다음 블록들을 실행한다. helper는 기존 `.perfume-deploy.lock`을 FD 9로 잠근 뒤 작업용 Bash로 이어진다. 잠금 실패 시 작업 셸을 열지 않는다. 작업 셸 정상 종료·오류 종료 시 마지막 FD가 닫히면서 잠금이 자동 해제된다. 하위 명령이 FD를 상속한 채 실행 중이면 그 명령 종료까지 유지될 수 있다. 잠금 파일은 삭제하지 않고, 진행 중인 명령이 있는 상태에서 `flock -u`로 강제 해제하지 않는다. 잠금 동안 향수 Jenkins 배포는 preflight에서 중단되며 주식 Job에는 영향을 주지 않는다.

명령 실패 후 로그인 프롬프트로 돌아왔다면 **다음 블록을 계속 붙여 넣지 않는다.** 쓰기/Import를 중단한 상태로 원인을 확인하고 helper를 다시 실행한다. 세션 변수는 사라지므로 읽기 전용 상태 확인으로 `SOURCE_CONTAINER`와 승인 Image ID를 다시 설정하고, 정확한 기존 백업 폴더를 직접 선택한다. 이미지 전환이나 extension 생성의 성공 여부가 불명확하면 상태 확인부터 하며 변경 명령을 자동 재실행하지 않는다.

```bash
cd /srv/perfume
set +x
umask 077
test "$(pwd -P)" = /srv/perfume
test -f .env
test ! -L .env
test -f .perfume-db-ready
test ! -L .perfume-db-ready
test "$(cat .perfume-db-ready)" = perfume-dev_pgdata
# FD 9 is already held by pgvector-maintenance-shell.sh; do not reopen it.
unset COMPOSE_FILE COMPOSE_PROJECT_NAME COMPOSE_PROFILES PERFUME_DB_IMAGE
unset DB_NAME DB_USERNAME DB_PASSWORD DB_URL SCENTREV_API_KEY
compose() {
  docker compose --project-name perfume-dev --project-directory /srv/perfume \
    --env-file /srv/perfume/.env --file /srv/perfume/compose.yaml "$@"
}
SOURCE_CONTAINER="$(docker ps --all --quiet \
  --filter label=com.docker.compose.project=perfume-dev \
  --filter label=com.docker.compose.service=db)"
[[ "$SOURCE_CONTAINER" =~ ^[0-9a-f]+$ ]]
test "$(docker inspect --format '{{.State.Running}}' "$SOURCE_CONTAINER")" = true
test "$(docker inspect --format '{{.State.Health.Status}}' "$SOURCE_CONTAINER")" = healthy
SOURCE_IMAGE_ID="$(docker inspect --format '{{.Image}}' "$SOURCE_CONTAINER")"
test "$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/var/lib/postgresql/data"}}{{.Type}} {{.Name}}{{end}}{{end}}' "$SOURCE_CONTAINER")" = 'volume perfume-dev_pgdata'
docker volume inspect --format '{{.Name}}' perfume-dev_pgdata
source_psql() {
  docker exec --interactive "$SOURCE_CONTAINER" psql -X --no-password \
    --host /var/run/postgresql --username perfume_user --dbname perfume --set ON_ERROR_STOP=1 "$@"
}
source_psql -qAt <<'SQL'
SELECT current_database(), current_user, current_setting('server_version_num');
SELECT count(*) AS current_perfumes FROM public.perfumes;
SELECT count(*) AS current_brands FROM public.brands;
SELECT extname, extversion FROM pg_extension WHERE extname = 'vector';
SQL
```

`perfume / perfume_user / 170011`, vector 미생성을 확인한다. **향수 848개·브랜드 161개·테이블 15개를 고정값으로 사용하지 않는다.** 현재 건수와 다음 manifest가 전환 시점의 기준이다. Unix socket 인증이 실패하면 기존 안전한 인증 방법을 먼저 준비한다. `.env`를 `source`하거나 비밀번호를 인수·로그에 넣지 않는다.

## 2. 최신 백업·무결성·복원 가능성

```bash
mkdir -p backups/pgvector-production
BACKUP_DIR="backups/pgvector-production/$(date -u +%Y%m%dT%H%M%SZ)"
mkdir "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"
docker exec "$SOURCE_CONTAINER" pg_dump --host /var/run/postgresql \
  --username perfume_user --dbname perfume --format custom > "$BACKUP_DIR/perfume.dump"
docker exec "$SOURCE_CONTAINER" pg_dumpall --host /var/run/postgresql \
  --username perfume_user -l perfume --roles-only --no-role-passwords > "$BACKUP_DIR/roles.sql"
source_psql -qAt --set expected_db=perfume \
  < docs/sql/pgvector-catalog-manifest.sql > "$BACKUP_DIR/before.jsonl"
cp -- .env "$BACKUP_DIR/operator-env-before"
printf '%s\n' "$SOURCE_IMAGE_ID" > "$BACKUP_DIR/source-image-id"
docker image save --output "$BACKUP_DIR/postgres-original.tar" "$SOURCE_IMAGE_ID"
sha256sum "$BACKUP_DIR/perfume.dump" "$BACKUP_DIR/roles.sql" "$BACKUP_DIR/before.jsonl" \
  "$BACKUP_DIR/postgres-original.tar" > "$BACKUP_DIR/SHA256SUMS"
sha256sum --check "$BACKUP_DIR/SHA256SUMS"
docker exec --interactive "$SOURCE_CONTAINER" pg_restore --list < "$BACKUP_DIR/perfume.dump" \
  > "$BACKUP_DIR/dump-toc.txt"
chmod 600 "$BACKUP_DIR/"*
```

해시와 TOC 확인만으로 복원 성공을 가정하지 않는다. **이번 최신 dump를 독립 리허설 DB에 복원**하여 최신 `before.jsonl`과 비교한다. [리허설 절차](pgvector-rehearsal.md)의 `start`, 빈 테스트 DB에 `restore --roles ... --dump ... --owner <현재 DB owner>`, `manifest`, `compare`를 따른다. 테스트 DB가 이미 채워져 있다면 보호된 리허설 cleanup으로 테스트 환경만 정리한다. 운영 DB에는 복원하지 않는다.

검증 완료까지 쓰기를 재개하지 않는다. pg_dump와 별도 manifest는 같은 snapshot을 공유하지 않고 sequence도 MVCC snapshot 대상이 아니다. 쓰기를 통제할 수 없다면 진행하지 않는다. 백업은 전체 데이터와 권한·시퀀스를 포함한다. `--no-role-passwords`라도 role 설정에 민감정보가 있을 수 있고 `.env` 사본은 비밀이므로, 이 폴더를 Git·CI artifact·공개 로그에 올리지 않는다.

role 백업은 해당 Ubuntu 17.11에서 정상 작동을 확인한 **`pg_dumpall -l perfume`**을 사용한다. 실패한 긴 연결 옵션을 다시 사용하지 않는다. `pg_dump --dbname`과 `psql --dbname`은 별도 도구의 기존 정상 옵션이므로 유지한다.

## 3. 이미지 검증과 수동 승인

```bash
BASE_IMAGE='postgres:17-bookworm@sha256:3645570cccdfa447589da9f57dd740faa29b30938e861289a5574b6ca6b03826'
docker image inspect --format '{{.Id}} {{.Os}}/{{.Architecture}}' "$BASE_IMAGE"
test "$(docker image inspect --format '{{.Id}}' "$BASE_IMAGE")" = "$SOURCE_IMAGE_ID"
docker image inspect --format '{{.Id}}' perfume-pgvector-rehearsal-db:pg17.11-vector0.8.7

# Verification only: no approval write and no DB changes.
python3 scripts/verify-pgvector-production-image.py
# Only after reviewing the build and rehearsal results:
python3 scripts/verify-pgvector-production-image.py --write-approval
chmod 600 .env.pgvector-approval.json
PROD_IMAGE_ID="$(python3 -c 'import json; print(json.load(open(".env.pgvector-approval.json"))["image_id"])')"
docker image inspect --format '{{.Id}}' "$PROD_IMAGE_ID"
docker image tag "$PROD_IMAGE_ID" perfume-postgres:pg17.11-vector0.8.7
docker image save --output "$BACKUP_DIR/pgvector-approved.tar" "$PROD_IMAGE_ID"
cp -- .env.pgvector-approval.json "$BACKUP_DIR/image-approval.json"
sha256sum "$BACKUP_DIR/pgvector-approved.tar" "$BACKUP_DIR/image-approval.json" \
  > "$BACKUP_DIR/IMAGE-SHA256SUMS"
sha256sum --check "$BACKUP_DIR/IMAGE-SHA256SUMS"
chmod 600 "$BACKUP_DIR/pgvector-approved.tar" "$BACKUP_DIR/image-approval.json" "$BACKUP_DIR/IMAGE-SHA256SUMS"
```

이미지가 로컬에 없으면 중단하고 확인된 digest나 보존 archive만 별도 절차로 가져온다. 최신 PostgreSQL 태그를 대신 pull하지 않는다. 검증 도구는 base/후보의 동일 PostgreSQL 바이너리, 버전·Bookworm·package·platform·실행 기본값과 실제 vector 파일을 확인한다. 운영 중인 perfume DB의 이미지·볼륨·네트워크·실행 파일·서버 버전도 읽기 전용으로 확인한다.

probe는 `--entrypoint sh --network none --read-only --pull never`의 임시 container에서 파일만 읽는다. **PostgreSQL 서버를 기동하거나 원본 볼륨을 mount하지 않는다.** 이미지가 선언한 익명 볼륨은 임시 container 삭제 시 함께 제거된다. Jenkins는 이 probe container 생성이나 승인 작성을 수행하지 않는다. 기존 승인 파일은 덮어쓰지 않으며 재승인이 필요하면 기존 기록을 보관하고 별도 검토한다.

원본 이미지 archive를 load하면 RepoDigest 연결이 복구되지 않을 수 있다. 롤백용 고정 digest 참조가 로컬에서 조회되지 않으면 해당 **정확한 digest만** 별도로 가져와 보관한 원본 Image ID와 대조한 뒤 진행한다. pgvector archive는 승인된 전체 Image ID로 직접 식별하므로 태그/RepoDigest 재등록에 의존하지 않는다.

## 3-1. 운영 전환 전 읽기 전용 Compose 검증

승인 파일이 만들어진 뒤, **운영 `.env`를 수정하기 전에** 작업용 셸에서 실행한다.

```bash
python3 scripts/verify-pgvector-production-image.py --preflight
```

이 모드는 승인 기록·로컬 Image ID·빌드 출처, 현재 DB의 이미지/볼륨/네트워크·PostgreSQL 버전·실행 파일을 검사한다. 기존 `.env`는 읽기만 하고, 자식 프로세스의 환경에만 승인 Image ID를 넣어 Compose를 렌더링한다. 이어서 **`docker compose --dry-run up --detach --no-deps --no-build --pull never db`** 계획 검증으로 현재 Compose/Engine이 로컬 Image ID를 인식하는지 확인한다. `config` 성공만으로 실제 Engine 이미지 인식을 검증했다고 간주하지 않는다.

container/volume 생성, 실제 up·재시작·pull/build, 승인 JSON 작성, DB 변경은 수행하지 않는다. 전후 기존 DB container ID와 Image ID·실행 상태도 대조한다. 원시 Compose JSON과 비밀번호를 출력하지 않는다. 서버 SQL은 READ ONLY로 실행하며 TCP 비밀번호 인증 검증은 기존 Jenkins 보호 검사가 담당한다.

`--dry-run`을 지원하지 않거나 이미지를 찾지 못하면 실패로 종료한다. `--dry-run`을 제거한 실제 up으로 우회하지 않는다. 성공 메시지에 출력된 Image ID가 승인 기록과 같은지 확인하고 나서만 4절의 실제 전환을 별도로 승인·실행한다. 이 확인은 DB 전환·Jenkins 실행의 승인이 아니다. [Compose dry-run 공식 문서](https://docs.docker.com/reference/cli/docker/compose/#use-dry-run-mode-to-test-your-command)

3절의 기존 후보 이미지 검증은 임시 probe container를 사용하고 `--write-approval`은 파일을 작성하므로, 이 읽기 전용 모드와 구분한다.

## 4. DB 이미지만 수동 전환

여기서 처음 운영 DB container를 재생성한다. **이번 코드 준비에서는 실행하지 않았다.** 운영자가 `.env`에 `PERFUME_DB_IMAGE=sha256:<승인된 전체 Image ID>` 한 줄만 추가한다. `PROD_IMAGE_ID`와 같은 값이어야 하며 DB 이름·사용자·비밀번호는 유지한다.

```bash
nano .env
chmod 600 .env
compose config --quiet
read -rsp 'Existing perfume DB password: ' PERFUME_EXPECTED_DB_PASSWORD
printf '\n'
export PERFUME_EXPECTED_DB_PASSWORD
compose config --format json | python3 scripts/validate-perfume-compose.py \
  --approval-file /srv/perfume/.env.pgvector-approval.json
unset PERFUME_EXPECTED_DB_PASSWORD
test "$(docker image inspect --format '{{.Id}}' "$PROD_IMAGE_ID")" = "$PROD_IMAGE_ID"
test "$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/var/lib/postgresql/data"}}{{.Name}}{{end}}{{end}}' "$SOURCE_CONTAINER")" = perfume-dev_pgdata
compose up --detach --no-deps --no-build --pull never --force-recreate \
  --wait --wait-timeout 180 db
```

단일 `db`만 지정한다. 전체 서비스 up, volume 갱신, down, prune을 사용하지 않는다. backend/frontend/Ollama container를 교체하지 않는다. 기존 볼륨은 그대로 연결하며 pg_upgrade나 dump 재복원은 하지 않는다. 재생성 동안 웹 DB 요청은 잠시 실패할 수 있다.

## 5. 데이터·권한·시퀀스와 웹 검증

```bash
NEW_CONTAINER="$(compose ps --quiet db)"
test -n "$NEW_CONTAINER"
test "$NEW_CONTAINER" != "$SOURCE_CONTAINER"
test "$(docker inspect --format '{{.Image}}' "$NEW_CONTAINER")" = "$PROD_IMAGE_ID"
docker inspect --format '{{json .}}' "$NEW_CONTAINER" | python3 scripts/validate-perfume-compose.py \
  --db-container --expected-db-image "$PROD_IMAGE_ID" --approval-file /srv/perfume/.env.pgvector-approval.json
SOURCE_CONTAINER="$NEW_CONTAINER"
source_psql -qAt --set expected_db=perfume \
  < docs/sql/pgvector-catalog-manifest.sql > "$BACKUP_DIR/after-image.jsonl"
python3 scripts/pgvector-rehearsal.py compare "$BACKUP_DIR/before.jsonl" "$BACKUP_DIR/after-image.jsonl"
source_psql -qAt -c "SELECT name, default_version, installed_version FROM pg_available_extensions WHERE name='vector';"
compose ps backend frontend
curl --fail --silent --show-error http://127.0.0.1:8088/healthz > /dev/null
curl --fail --silent --show-error 'http://127.0.0.1:8088/api/perfumes?size=1' > /dev/null
```

PostgreSQL 170011, 전체 user table 수·행 hash·소유권/ACL/role, sequence 정의·ownership·last_value·is_called가 이전 manifest와 같아야 한다. vector default_version은 0.8.7, installed_version은 아직 NULL이어야 한다. 브라우저 `http://192.168.0.19:8088`에서 검색·브랜드 필터·페이지 이동·실제 향수 상세·새로고침을 확인한다. 불일치나 접속 복구 실패가 있으면 extension 단계로 진행하지 않는다.

## 6. 별도 extension 적용과 Import 재개

이미지 전환·기존 기능 검증 후 **별도 migration 승인**을 받고 실행한다. `ai` schema 및 영구 embedding table은 생성하지 않는다. 리허설 smoke SQL의 테스트 DB guard를 제거하여 운영에 재사용하지 않는다.

```bash
source_psql --set ON_ERROR_STOP=1 <<'SQL'
BEGIN;
DO $$
BEGIN
  IF current_database() <> 'perfume' OR current_user <> 'perfume_user'
     OR current_setting('server_version_num') <> '170011' THEN
    RAISE EXCEPTION 'Unexpected extension target';
  END IF;
END $$;
CREATE EXTENSION vector WITH SCHEMA public VERSION '0.8.7';
SELECT extname, extversion FROM pg_extension WHERE extname = 'vector';
COMMIT;
SQL
# Read-only cosine check, no ai or persistent embedding tables.
source_psql --set ON_ERROR_STOP=1 <<'SQL'
WITH vectors AS (
  SELECT array_agg(CASE WHEN i = 1 THEN 1::real ELSE 0::real END ORDER BY i)::public.vector(1024) AS a,
         array_agg(CASE WHEN i = 2 THEN 1::real ELSE 0::real END ORDER BY i)::public.vector(1024) AS b
  FROM generate_series(1, 1024) AS i
)
SELECT public.vector_dims(a) AS dimensions,
       1 - (a OPERATOR(public.<=>) a) AS self_similarity,
       1 - (a OPERATOR(public.<=>) b) AS orthogonal_similarity FROM vectors;
SQL
source_psql -qAt --set expected_db=perfume \
  < docs/sql/pgvector-catalog-manifest.sql > "$BACKUP_DIR/after-extension.jsonl"
python3 scripts/pgvector-rehearsal.py compare "$BACKUP_DIR/before.jsonl" "$BACKUP_DIR/after-extension.jsonl"
curl --fail --silent --show-error 'http://127.0.0.1:8088/api/perfumes?size=1' > /dev/null
# Leave the maintenance child shell; closing FD 9 releases the lock automatically.
exit
```

결과는 1024 / 1 / 0, extension 0.8.7, 기존 데이터·권한·시퀀스 일치여야 한다. 웹 확인과 별도로 승인한 Jenkins 검증을 완료한 뒤 Import를 재개한다. Jenkins는 승인된 Image ID와 변경되지 않은 DB container를 검사하며 backend/frontend만 배포한다. 이 단계에서 `.env`와 승인 기록은 Jenkins가 수정하지 않는다.

extension 단계를 나중에 진행한다면 5절 검증 완료 후에도 `exit`로 작업 셸을 종료할 수 있다. 이후 별도 작업에는 잠금을 다시 얻고 읽기 전용으로 현재 상태를 확인한다. 정상/오류 종료나 SSH 연결 단절 후에도 `.perfume-deploy.lock` 파일을 삭제하지 않는다.

## 롤백

**extension 생성 전:** 쓰기를 멈추고 같은 잠금을 유지한 상태에서 `.env`의 `PERFUME_DB_IMAGE`만 위 표의 원본 고정 digest로 변경한다. 최초 백업의 Image ID와 일치하는 로컬 이미지가 있고 vector가 미설치인지 확인한다. 기존 비밀번호와 볼륨은 유지한다. 변경한 Compose를 4절과 같은 보호 검사로 검증한 다음 `db`만 재생성한다.

원본 기본 태그와 이 **정확한 원본 digest**는 pgvector 승인 JSON을 보관한 상태에서도 허용된다. 다른 digest·임의 태그·PostgreSQL 버전은 허용되지 않는다. 로컬 테스트에서 승인 파일을 유지한 실제 Compose 렌더링·검사기 CLI·모의 Jenkins 배포의 롤백 경로를 검증한다.

```bash
test "$(source_psql -qAt -c "SELECT count(*) FROM pg_extension WHERE extname='vector';")" = 0
test "$(docker image inspect --format '{{.Id}}' "$BASE_IMAGE")" = "$(cat "$BACKUP_DIR/source-image-id")"
# After changing only PERFUME_DB_IMAGE in .env and validating the config:
compose up --detach --no-deps --no-build --pull never --force-recreate --wait --wait-timeout 180 db
```

복귀 후 원본 digest·17.11·운영 볼륨, 최신 before manifest와 웹 동작을 다시 확인한다. 승인 JSON과 archive는 기록으로 보관한다. 원본 이미지 경로에서 DB에 vector가 설치되어 있으면 Jenkins 검사도 중단된다.

**extension 또는 vector 테이블 생성 후:** vector 라이브러리가 없는 원본 이미지로 즉시 바꾸지 않는다. DB가 vector type/function에 의존하므로 AI 기능·worker/app 변경을 먼저 되돌리고 승인된 pgvector 이미지와 현재 DB/볼륨은 유지한다. 의존 객체를 삭제하는 extension 제거를 롤백 수단으로 쓰지 않는다.

vector 비의존 상태로 완전 복구해야 한다면 전환 전 백업을 **별도 새 복구 볼륨**에 복원·검증하고 별도 전환 계획을 세운다. 기존 `perfume-dev_pgdata`는 보존하며 오래된 dump로 덮어쓰지 않는다. 백업 이후 Import/수정분의 손실·재반영 범위도 먼저 정한다. 현재 검사기는 운영 볼륨 이름을 고정하므로 복구 볼륨을 운영에 연결하는 작업은 별도 승인과 보호 설정 검토가 필요하다. 이 코드는 자동 복원·볼륨 전환을 제공하지 않는다.

## 검증 범위

```bash
python3 -m unittest discover -s tests/deployment -v
bash -n scripts/jenkins-deploy.sh
bash -n scripts/pgvector-maintenance-shell.sh
git diff --check
```

로컬 테스트는 실제 Compose 렌더링과 순수 metadata 검증, 이미지 출처/버전/hash/볼륨/네트워크 차단을 수행한다. 실제 deploy shell을 임시 checkout에서 실행하되 Docker/Git/curl은 모두 mock으로 교체하여 기본/pgvector/최초 검사기 업그레이드 경로, 앱만 배포, DB ID 변경·미승인 이미지·Git 승인 파일 혼입 시 중단을 검증한다. 실제 Docker engine/Ubuntu/Jenkins 통합 실행과 구분한다.

승인 기록을 유지한 원본 이미지 롤백도 실제 Compose 렌더링·검사기 CLI·모의 Jenkins 배포로 검사한다. 작업용 Bash 오류·정상 종료·잠금 획득 실패 시 부모 셸 유지와 FD 상속을 로컬에서 검사한다. Windows Git Bash에 flock이 없어 잠금 명령은 stub을 사용한다. Ubuntu의 실제 커널 잠금과 PuTTY 연결 동작, Engine을 사용하는 Image ID dry-run은 현지에서 별도로 확인한다. 읽기 전용 preflight의 명령 제한·실패 중단도 Docker mock으로 검사한다.

로컬 Docker engine이 꺼져 있어 실제 이미지 probe, 운영 DB 재생성, Jenkins plugin/Job 실행은 검증하지 않았다. Ubuntu 리허설 성공은 사용자 보고이며 이번 작업에서 재실행하지 않았다.

참고: [Compose image](https://docs.docker.com/reference/compose-file/services/#image), [Compose up/no-deps/pull](https://docs.docker.com/reference/cli/docker/compose/up/), [image inspect](https://docs.docker.com/reference/cli/docker/image/inspect/), [PostgreSQL 17 pg_dump](https://www.postgresql.org/docs/17/app-pgdump.html).
