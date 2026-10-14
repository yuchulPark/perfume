# PostgreSQL 17 / pgvector 0.8.7 복제 검증

이번 변경은 이미지 제작 파일과 별도 복제 검증 환경만 제공한다. `compose.yaml`의 `postgres:17-bookworm`, `perfume-dev_pgdata`, Jenkins 검사기·배포 스크립트, 주식 프로젝트, Spring Boot, React, Ollama 설정은 변경하지 않는다. 운영 DB의 extension 활성화, 이미지 전환, `ai` 스키마 생성은 다음 단계다.

**아래 명령은 Ubuntu 운영자가 검증 일정에 맞춰 수동 실행하는 절차다. 이번 파일 작성 과정에서는 Ubuntu 접속, 백업·복원, 컨테이너 실행·중지, 운영 DB 작업을 실행하지 않았다.** Jenkins 작업에 추가하지 않는다. 운영 DB 컨테이너는 모든 준비·복제 검증 동안 그대로 실행한다.

## 1. 실행 중인 이미지와 서버 버전 확인 — 읽기 전용

Ubuntu의 기존 프로젝트 디렉터리에서 Bash로 실행한다. `docker inspect`의 전체 출력이나 `.env` 내용을 로그에 남기지 않는다.

PuTTY 로그인 셸에는 strict 옵션을 직접 적용하지 않는다. 먼저 아래 자식 Bash를 열고 이후 블록들을 그 안에서 실행한다. 오류로 자식 셸이 종료되어도 기존 로그인 셸은 유지되며, 다음 블록을 계속 붙여 넣지 않는다. 운영 이미지 전환의 잠금 절차는 [운영 전환 문서](pgvector-production-deployment.md)를 따른다.

```bash
set +e
set +u
set +o pipefail
PS1='(perfume-rehearsal) \w\$ ' HISTFILE=/dev/null \
  bash --noprofile --norc -e -u -o pipefail -i || \
  printf '리허설 자식 셸이 종료됐습니다. 기존 로그인 셸은 유지됩니다.\n'
```

```bash
cd /srv/perfume
umask 077

SOURCE_CONTAINER="$(docker compose --project-name perfume-dev --env-file .env --file compose.yaml ps --quiet db)"
test -n "$SOURCE_CONTAINER"
SOURCE_IMAGE_ID="$(docker inspect --format '{{.Image}}' "$SOURCE_CONTAINER")"
SOURCE_VOLUME="$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/var/lib/postgresql/data"}}{{.Name}}{{end}}{{end}}' "$SOURCE_CONTAINER")"
test "$SOURCE_VOLUME" = perfume-dev_pgdata

# Inspect the actual container's image ID, not the current value of a floating tag.
docker image inspect --format '{{json .RepoDigests}}' "$SOURCE_IMAGE_ID"
docker image inspect --format '{{.Os}}/{{.Architecture}}' "$SOURCE_IMAGE_ID"
docker exec "$SOURCE_CONTAINER" postgres --version
docker exec "$SOURCE_CONTAINER" cat /etc/os-release

source_psql() {
  docker exec --interactive "$SOURCE_CONTAINER" sh -ec \
    'exec psql -X --no-password --host /var/run/postgresql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" "$@"' sh "$@"
}

source_psql -qAt <<'SQL'
SELECT current_database(), current_setting('server_version'), current_setting('server_version_num');
SELECT pg_get_userbyid(datdba), pg_encoding_to_char(encoding), datcollate, datctype,
       datlocprovider, datlocale, daticurules, datcollversion, datacl
FROM pg_database WHERE datname = current_database();
SELECT EXISTS (SELECT FROM pg_roles WHERE rolname = 'perfume_pgvector_admin') AS reserved_test_role_exists;
SELECT spcname FROM pg_tablespace WHERE spcname NOT IN ('pg_default', 'pg_global');
SQL
```

다음 값을 기록한 뒤 진행한다.

| 확인 항목 | 진행 조건 |
| --- | --- |
| 컨테이너 이미지 | 위 `SOURCE_IMAGE_ID`의 PostgreSQL 공식 저장소 `RepoDigests`에서 확인한 digest |
| 배포판·아키텍처 | Debian **bookworm**, Linux, 같은 CPU 아키텍처에서 제작·검증 |
| PostgreSQL | 실행 파일과 SQL 서버 모두 **17의 동일한 minor 버전**; `server_version_num`도 기록 |
| DB locale | 이 구성은 **UTF8 + libc(`datlocprovider=c`) + LC_COLLATE와 LC_CTYPE 동일**일 때 사용 |
| 전용 bootstrap role | 원본에 `perfume_pgvector_admin`이 없어야 함 |
| 별도 tablespace | 없어야 함; 있으면 해당 저장 위치까지 별도 검토 후 진행 |

예를 들어 SQL이 `170006`이면 minor는 `17.6`이다. 이 숫자는 예시이며 실제 서버 값을 대체하지 않는다. locale이 ICU/builtin이거나 encoding·LC_COLLATE·LC_CTYPE 조건이 다르면 현재 예제 환경으로 시작하지 않는다. 정확한 원본 조건을 수용하는 별도 초기화 절차를 먼저 정한다.

`RepoDigests`가 `postgres@sha256:...`라면 환경 파일에는 `postgres:17-bookworm@sha256:...`로 기입한다. digest 부분은 그대로 보존한다. 확인한 고정 참조가 실제 이미지와 일치하는지 검사한다.

```bash
# Assign the REAL digest from SOURCE_IMAGE_ID, not this placeholder.
POSTGRES_BASE_IMAGE='postgres:17-bookworm@sha256:<verified-64-hex-digest>'
docker pull "$POSTGRES_BASE_IMAGE"
test "$(docker image inspect --format '{{.Id}}' "$POSTGRES_BASE_IMAGE")" = "$SOURCE_IMAGE_ID"
```

이 pull은 확인된 digest만 가져오며 실행 컨테이너를 변경하지 않는다. RepoDigest가 없거나 Image ID가 일치하지 않으면 중단한다. 현재 `postgres:17-bookworm` 태그의 digest를 대신 쓰거나 최신 17.x로 교체하지 않는다. 이미지를 보존하기 위한 `docker image save "$SOURCE_IMAGE_ID"`도 별도로 가능하다.

## 2. 전용 환경 파일과 이미지 제작

```bash
cp docker/pgvector/rehearsal.env.example .env.pgvector-rehearsal
chmod 600 .env.pgvector-rehearsal
# Edit locally: verified digest, exact 17.x, server_version_num, source locale,
# and a NEW test password. This .env.* file is ignored by Git.

python3 scripts/pgvector-rehearsal.py config
python3 scripts/pgvector-rehearsal.py build
```

digest·버전의 자동 기본값은 없다. 예제의 `REPLACE_...` 값을 남기면 보호 검사에서 실패한다. 공백·`#`·`$` 등이 포함된 테스트 비밀번호는 Compose 환경 파일 문법에 따라 작은따옴표로 감싼다. 환경 파일을 `source`하거나 `set -x`로 실행하지 않는다. 운영 `.env`의 비밀번호를 복사하지 않는다.

Dockerfile의 두 stage는 같은 digest에서 시작한다. 빌드 시 설치된 PostgreSQL 서버·클라이언트 패키지를 hold하고, **원본 `PG_VERSION`과 동일한 `postgresql-server-dev-17` 패키지**를 요구한다. `make -j1 OPTFLAGS=""`로 CPU에 종속적인 `-march=native` 기본값을 제거한다. 런타임에는 vector 라이브러리·extension SQL·라이선스만 복사한다. PostgreSQL 실행 파일의 버전과 SHA-256을 빌드 전·후와 최종 stage에서 검사하고, `vector.so`의 누락된 동적 라이브러리도 검사한다.

apt 저장소에 해당 오래된 개발 패키지 버전이 없으면 빌드는 실패한다. 이때 base digest나 PostgreSQL minor를 올려 우회하지 않는다. 원본 버전과 일치하는 신뢰할 수 있는 PGDG 보관 저장소/패키지와 그 무결성을 별도로 확인한 뒤 빌드 공급 경로를 정한다. 빌드 오류 상세가 필요하면 아래 **독립 Compose의 build만** 실행한다. 이 명령에 DB 비밀번호를 build argument로 전달하지 않는다.

```bash
docker compose --project-name perfume-pgvector-rehearsal --env-file .env.pgvector-rehearsal \
  --file compose.pgvector-rehearsal.yaml build rehearsal-db
```

원본과 파생 이미지의 실행 파일 버전·바이너리 해시도 별도로 확인할 수 있다. 운영 DB 컨테이너를 실행하거나 재생성하는 명령이 아니다.

```bash
# Use the same verified image reference and exact 17.x tag from the env file.
DERIVED_IMAGE='perfume-pgvector-rehearsal-db:pg17.<verified-minor>-vector0.8.7'
docker run --rm --network none --entrypoint postgres "$POSTGRES_BASE_IMAGE" --version
docker run --rm --network none --entrypoint postgres "$DERIVED_IMAGE" --version
docker run --rm --network none --entrypoint sha256sum "$POSTGRES_BASE_IMAGE" /usr/lib/postgresql/17/bin/postgres
docker run --rm --network none --entrypoint sha256sum "$DERIVED_IMAGE" /usr/lib/postgresql/17/bin/postgres
docker image inspect --format '{{.Id}}' "$DERIVED_IMAGE"
```

**두 바이너리 해시와 버전이 같아야 한다.** 마지막 파생 Image ID도 검증 기록에 남긴다. `pgvector/pgvector:pg17`처럼 PostgreSQL minor가 별도로 바뀔 수 있는 이미지를 대신 쓰지 않는다.

## 3. 원본 백업과 검증 기준선 — 읽기 전용

`backups/`는 Git에서 제외되어 있다. 백업, role SQL, manifest는 권한 600의 별도 보관 파일로 관리하고 CI artifact나 공개 로그에 올리지 않는다. `--no-role-passwords`는 role 비밀번호·해시를 제외하지만 role 설정이나 백업 데이터에는 민감한 내용이 있을 수 있다.

**정확한 복원 비교를 위해 백업과 원본 manifest 수집 사이에는 향수 데이터의 쓰기가 없어야 한다.** 단일 브랜드 Import, 다른 Import, 향수 등록·수정 등의 실행이 끝난 검증 시간을 선택한다. PostgreSQL 컨테이너를 중지할 필요는 없다. `pg_dump`는 일관된 스냅샷을 만들지만 따로 실행한 manifest와 시퀀스 값까지 같은 시점을 보장하지 않으며, 시퀀스는 MVCC 스냅샷 대상도 아니다. 쓰기를 통제할 수 없다면 시점 차이를 고려해 비교를 다시 준비하며, 불일치를 무시하지 않는다. 쓰기 제어 작업은 이 도구가 수행하지 않는다.

```bash
mkdir -p backups/pgvector-rehearsal
chmod 700 backups/pgvector-rehearsal
# Use a NEW run directory/name each time; do not overwrite a prior backup.
BACKUP_DIR="backups/pgvector-rehearsal/$(date -u +%Y%m%dT%H%M%SZ)"
mkdir "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"

# Entire database: includes perfume/brand/join tables, provider payloads and
# Import progress, ownership, object ACLs and sequence setval values.
docker exec "$SOURCE_CONTAINER" sh -ec \
  'exec pg_dump --host /var/run/postgresql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --format=custom' \
  > "$BACKUP_DIR/perfume.dump"

docker exec "$SOURCE_CONTAINER" sh -ec \
  'exec pg_dumpall --host /var/run/postgresql --username "$POSTGRES_USER" -l "$POSTGRES_DB" --roles-only --no-role-passwords' \
  > "$BACKUP_DIR/roles.sql"

# Use the actual source database name from step 1 (default: perfume).
SOURCE_DB='perfume'
source_psql -qAt --set expected_db="$SOURCE_DB" \
  < docs/sql/pgvector-catalog-manifest.sql > "$BACKUP_DIR/source.jsonl"

sha256sum "$BACKUP_DIR/perfume.dump" "$BACKUP_DIR/roles.sql" "$BACKUP_DIR/source.jsonl" \
  > "$BACKUP_DIR/SHA256SUMS"
chmod 600 "$BACKUP_DIR/"*
```

백업은 원본 볼륨의 라이브 파일을 복사하거나 `tar`로 묶지 않고 `pg_dump`로 만든다. 원본 컨테이너에 백업 파일을 `docker cp`하지 않는다. source 접속 함수는 기존 컨테이너 내부의 Unix socket을 사용하며 API 키나 DB 비밀번호를 출력하지 않는다. `set -euo pipefail` 환경에서 하나라도 실패하면 복원 단계로 진행하지 않는다.

복원 전에 직접 관리하는 DB에서 생성된 백업인지 확인하고 role SQL 및 `pg_restore --list`/`pg_restore --file`의 내용을 비공개로 검토한다. 별도 tablespace, 외부 접속 객체, subscription, event trigger, 외부 시스템을 호출하는 함수 등이 있다면 먼저 영향과 복원 범위를 검토한다. 내부 Docker 네트워크도 source SQL을 임의로 신뢰하는 근거로 삼지 않는다.

## 4. 독립 테스트 DB 실행·복원

```bash
python3 scripts/pgvector-rehearsal.py start
python3 scripts/pgvector-rehearsal.py restore \
  --roles "$BACKUP_DIR/roles.sql" --dump "$BACKUP_DIR/perfume.dump" \
  --owner 'perfume_user'
# --owner must be the EXACT source database owner queried in step 1.
```

| 자원 | 테스트 전용 값 |
| --- | --- |
| Compose 프로젝트 | `perfume-pgvector-rehearsal` |
| 유일한 서비스 | `rehearsal-db` |
| 영구 볼륨 | `perfume-pgvector-rehearsal_pgdata` |
| 네트워크 | `perfume-pgvector-rehearsal_rehearsal` (internal) |
| DB / 초기 관리 role | `perfume_pgvector_rehearsal` / `perfume_pgvector_admin` |
| 외부 포트 | 없음; `docker exec`로만 검증 |
| 자원 제한 | CPU 1개, RAM 2GiB; 빌드는 `make -j1` |

`compose.pgvector-rehearsal.yaml`은 **단독으로** 사용한다. 운영 `compose.yaml`과 여러 `-f`로 합치지 않는다. Python 도구는 파일·프로젝트를 고정하고 렌더링된 설정을 검증한다. 실제 container mount, 네트워크, image provenance label, volume/network의 Compose 소유권도 검사한다. 같은 이름의 수동 생성/다른 프로젝트 소유 볼륨이면 재사용하지 않는다. 운영 DB·Ollama·주식 네트워크를 연결하지 않으며 백업 디렉터리도 mount하지 않는다.

복원은 신규 빈 DB에만 허용한다. user relation·추가 schema·추가 extension이 있으면 중단하며, `--clean`이나 `DROP`으로 덮어쓰지 않는다. role을 먼저 복원하고 `pg_restore --exit-on-error --single-transaction`으로 전체 DB를 복원한다. **`--no-owner`·`--no-privileges`·`--disable-triggers`는 쓰지 않는다.** 역할 비밀번호는 복사하지 않으므로 원본 사용자 비밀번호로 테스트 DB에 인증하려고 하면 실패하는 것이 정상이다. 검증은 테스트 관리 role로 수행한다.

`--create`는 dump에 들어 있는 DB 이름으로 접속·생성하기 때문에 사용하지 않는다. 그 결과 **DB 자체의 사용자 정의 ACL, `ALTER DATABASE ... SET`, `ALTER ROLE ... IN DATABASE ... SET`, DB comment 등은 직접 접속 방식의 pg_restore에서 재현되지 않을 수 있다.** 도구는 DB owner를 원본과 같게 설정한다. 기본 ACL이면 비교가 일치하지만 추가 ACL/DB별 설정이 있으면 manifest 비교에서 실패한다. 이 실패를 권한을 제외하는 옵션으로 무시하지 않는다.

그 경우 source의 읽기 전용 catalog 또는 검토한 schema dump에서 해당 설정을 확인하고, DB 이름만 `perfume_pgvector_rehearsal`로 바꾼 **검토된 SQL**을 복제 DB에만 적용한다. 아래는 대상 고정 접속 방법과 문법 예시이며 실제 GRANT/REVOKE/SET 값은 원본을 따라야 한다.

```bash
TEST_CONTAINER="$(python3 scripts/pgvector-rehearsal.py container)"
test -n "$TEST_CONTAINER"
# Source metadata goes into a local reviewed file. Never replay the original
# source database name or \connect from a dump without inspecting it.
docker exec --interactive "$TEST_CONTAINER" psql -X --no-password --set ON_ERROR_STOP=1 \
  --host /var/run/postgresql --username perfume_pgvector_admin --dbname perfume_pgvector_rehearsal \
  < "$BACKUP_DIR/reviewed-test-db-properties.sql"
```

```sql
-- Illustrative only: use the source's actual roles/privileges/settings.
-- Add a current_database/current_user assertion at the beginning of the file.
-- GRANT CONNECT ON DATABASE perfume_pgvector_rehearsal TO "source_role";
-- ALTER DATABASE perfume_pgvector_rehearsal SET timezone TO 'UTC';
-- ALTER ROLE "source_role" IN DATABASE perfume_pgvector_rehearsal SET search_path TO 'public';
```

## 5. 실제 향수 데이터·권한·시퀀스 비교와 vector 테스트

먼저 extension 활성화 전 복원을 검증한다.

```bash
python3 scripts/pgvector-rehearsal.py manifest --output "$BACKUP_DIR/clone-before-vector.jsonl"
python3 scripts/pgvector-rehearsal.py compare "$BACKUP_DIR/source.jsonl" "$BACKUP_DIR/clone-before-vector.jsonl"
```

manifest는 원본과 복제 DB에서 동일한 **READ ONLY / REPEATABLE READ** SQL로 생성한다. 비교 대상은 다음과 같다.

- PostgreSQL `server_version_num`, DB owner·유효 ACL·encoding·locale·collation version
- 전체 사용자 table 수, schema/relation/column 소유권·ACL·default privilege, role 속성·membership·DB별 설정 해시
- 테이블 column 타입·nullability·identity/default, 제약 조건 정의/유효성, index 정의/유효성
- `public.perfumes`, `public.brands`와 모든 사용자 테이블/물리화 view의 정확한 행 수 및 순서에 무관한 전체 행 SHA-256
- 모든 sequence의 정의·ownership·`last_value`·`is_called`; **`nextval`을 호출하지 않음**

향수 row 수가 같아도 내용, foreign key/index, owner·ACL, 시퀀스 상태가 다르면 실패한다. 성공 시 table 수와 향수·브랜드 row 수도 출력하므로 예상한 실제 향수 개수와 비교한다. DB 이름, 내부 OID, 파일 경로, 복제용 bootstrap role, PostgreSQL 사전 정의 role 사이의 내부 membership은 비교 대상에서 제외한다. application role과 사전 정의 role 사이의 grant는 포함한다. column 순서는 삭제된 column의 내부 번호가 아니라 남아 있는 column의 순서로 비교한다. vector extension 자체는 아래 테스트에서 검사한다. 이 manifest는 전체 함수·trigger·RLS policy 정의까지 비교하는 완전한 schema diff 도구는 아니다. 그러한 사용자 정의 객체가 있다면 schema dump도 추가 대조한다. 현재 Import 테이블과 provider payload도 전체 user table 검증에 포함된다. 원본 row 본문과 role 비밀번호는 출력하지 않는다.

hash 계산은 전체 데이터를 읽으며 테이블별 행당 약 64byte의 집계 입력과 정렬 작업이 필요하다. 큰 DB에서는 실행 시간·디스크 여유·메모리를 확인해 검증 시간을 잡는다. 작은 perfume 복제 검증을 위한 자원 제한은 부하 격리 목적이며, VM 전체 여유 공간과 Docker build 소비까지 제한하는 설정은 아니다. 빌드/복원/검증은 순차 실행하고 기존 Ollama나 DB를 자동 중지하지 않는다.

비교 성공 후 복제 DB에서 vector 기능을 검증한다.

```bash
python3 scripts/pgvector-rehearsal.py verify
python3 scripts/pgvector-rehearsal.py manifest --output "$BACKUP_DIR/clone-after-vector.jsonl"
python3 scripts/pgvector-rehearsal.py compare "$BACKUP_DIR/source.jsonl" "$BACKUP_DIR/clone-after-vector.jsonl"
```

`docs/sql/pgvector-smoke-test.sql`은 테스트 DB 이름·초기 관리 role·정확한 PostgreSQL 버전이 일치해야 실행된다. `CREATE EXTENSION vector ... VERSION '0.8.7'`, `vector(1024)`에 세 개의 합성 벡터 저장, `<=>` 코사인 거리 검색을 검증한다. 유사도 `1 - distance`는 순서대로 **1 / 0 / -1**, 차원은 모두 **1024**여야 한다. 잘못된 2차원 벡터의 삽입 거부도 검사한다. 임시 테이블은 commit 시 삭제되고, 영구 임베딩 테이블과 `ai` 스키마는 만들지 않는다. extension 생성 후 원본 데이터·권한·시퀀스 manifest가 다시 같아야 한다.

## 6. 실패 또는 검증 완료 후 테스트 환경 정리

```bash
# Remove only the verified test container/network; keep its data for diagnosis.
python3 scripts/pgvector-rehearsal.py cleanup

# When the test data is no longer needed, explicitly remove THAT test volume.
python3 scripts/pgvector-rehearsal.py cleanup --delete-test-volume
```

도구는 이름뿐 아니라 프로젝트 label과 실제 mount를 확인하고, 다른 container가 테스트 network/volume을 사용하면 삭제 전 중단한다. volume 삭제는 정확한 `perfume-pgvector-rehearsal_pgdata`에만 허용하며 `--force`를 쓰지 않는다. 정상 기존 `.env.pgvector-rehearsal`은 정리 완료까지 보관한다. 정리 도구는 운영 `compose.yaml`을 사용하지 않으며 `down -v`, `docker volume prune`, `docker system prune`, 운영 DB/주식 프로젝트/Ollama 자원 삭제 명령을 실행하지 않는다. 이름이 같은 자원의 소유권 검사 실패 시 수동으로 강제 삭제하지 않고 실제 소유자를 먼저 확인한다.

복원 실패 후 재시도는 위의 테스트 환경 정리·**테스트 볼륨만** 삭제·`start`·빈 DB `restore` 순서다. 백업·manifest·원본 이미지·파생 이미지는 정리 도구가 삭제하지 않는다. 민감한 백업의 폐기는 별도 보관 정책에 따른다.

## 7. 향후 운영 전환과 롤백 — 이번에 실행하지 않음

1. 검증 결과, 원본 Image ID/RepoDigest, 원본 PostgreSQL 버전, 파생 Image ID 또는 registry digest, 백업 무결성을 보관한다. 백업의 별도 복원 성공이 전환의 전제다.
2. 별도 유지보수 일정에서 향수 쓰기를 제어하고 최신 backup/manifest를 다시 확보한다. 기존 `perfume-dev_pgdata`는 같은 PGDATA 경로로 **계속 보존**한다. 새로운 빈 볼륨으로 갈아끼우지 않는다.
3. 그 단계에서만 운영 Compose의 DB 이미지를 검증한 파생 **digest**로 변경한다. PG 버전·아키텍처·바이너리 동일성을 다시 확인한다. DB 컨테이너 재생성은 그 전환 작업에만 포함되며 이번 단계에서는 하지 않는다.
4. Jenkins의 기존 검사기는 `postgres:17-bookworm`을 정확히 요구하므로 변경된 운영 이미지가 현재 검사기에 거부되는 것이 정상이다. 다음 단계에서 허용된 고정 digest와 동일 volume을 검사하도록 별도 검토한다. `.perfume-db-ready`, lock, Import 비활성화, DB container/mount/password 검사와 backend/frontend만 배포하는 보호 원칙은 유지한다. stock 프로젝트에는 적용하지 않는다.
5. 처음에는 운영 extension/schema를 생성하지 않고 기존 향수 기능, table·권한·시퀀스·DB 버전을 확인한다. 이후 별도 migration으로만 `CREATE EXTENSION`과 `ai` schema/임베딩 테이블을 도입한다.

롤백은 적용된 DB 객체에 따라 구분한다.

- **vector extension/객체 생성 전:** DB 컨테이너 이미지 참조만 기록한 원본 digest로 되돌리고 동일 영구 볼륨을 연결한다. PostgreSQL version은 같아야 하며 기존 data·권한·시퀀스를 다시 검증한다.
- **vector extension 또는 vector column 생성 후:** vector 라이브러리가 없는 원본 PostgreSQL 이미지로 즉시 바꾸지 않는다. 해당 DB는 vector 함수/type을 참조하므로 앱 기능을 먼저 되돌리고 pgvector가 포함된 이미지를 유지하는 방법을 우선 사용한다. `DROP EXTENSION vector CASCADE`로 의존 데이터를 삭제하는 롤백은 사용하지 않는다.
- **원본 이미지로 완전 복구가 필요한 경우:** 전환 전 백업을 별도의 새 복구 볼륨에 복원·검증한 뒤 계획된 전환을 한다. 기존 운영 볼륨은 그대로 보존한다. 백업 이후 쓰기 손실/재반영 범위를 먼저 확인한다. 쓰기가 진행된 운영 볼륨 위에 오래된 백업을 덮어쓰지 않는다.

## 로컬 검증과 공식 참고

```bash
python3 -m unittest discover -s tests/deployment -v
git diff --check
```

테스트는 실제 Compose 렌더링, 잘못된 digest/minor·운영 volume·공유 network 차단, 실제 자원 소유권 검사, 테스트 자원만 정리, data hash/ACL/sequence 불일치 판별, 복원 옵션과 오류 시 민감정보 비출력을 검증한다. 이 테스트는 Docker daemon·Ubuntu·실제 DB에 접속하지 않는다. 실제 이미지 빌드·복원·vector SQL 실행 검증은 Docker engine과 확인된 서버 값/백업이 있는 별도 환경에서 위 절차로 수행한다.

- [pgvector v0.8.7 Dockerfile](https://github.com/pgvector/pgvector/blob/v0.8.7/Dockerfile), [v0.8.7 Makefile와 OPTFLAGS](https://github.com/pgvector/pgvector/blob/v0.8.7/Makefile)
- [PostgreSQL 17 pg_dump](https://www.postgresql.org/docs/17/app-pgdump.html), [pg_dumpall](https://www.postgresql.org/docs/17/app-pg-dumpall.html), [pg_restore](https://www.postgresql.org/docs/17/app-pgrestore.html)
- [PostgreSQL 17 pg_database](https://www.postgresql.org/docs/17/catalog-pg-database.html)
