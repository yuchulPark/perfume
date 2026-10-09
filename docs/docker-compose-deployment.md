# Ubuntu 24.04 Docker Compose 개발서버

기존 Java/React/Import 코드와 DB 구조를 유지합니다. 향수 프로젝트 전용 PostgreSQL을 사용하며 주식의 stock-db, stock-network, stock-db-data 및 Jenkins 설정은 변경하거나 공유하지 않습니다. 이 문서의 서버 실행, 백업, 복원 명령은 운영자가 실행할 명령이며 이번 작업에서는 실행하지 않았습니다.

## 구성

| 서비스 | 빌드/실행 | 포트 | 영구 저장 |
| --- | --- | --- | --- |
| db | PostgreSQL 17, postgres:17-bookworm | Docker 내부 5432, Ubuntu 127.0.0.1:15432 → 5432 | pgdata named volume |
| backend | Temurin Java 17 JDK + 기존 Maven Wrapper → Java 17 JRE | Docker 내부 8081 | 기존 DB 사용 |
| frontend | Node 24 + npm ci + Vite → Nginx stable-alpine | 서버 8088 → 컨테이너 80 | 빌드된 정적 파일 |

외부에는 frontend의 8088만 게시합니다. PostgreSQL은 Ubuntu 호스트의 127.0.0.1:15432에만 바인딩하므로 Windows에서 SERVER_IP:15432로 직접 연결하지 않습니다. SSH 터널로만 접속하며 서버 방화벽에 15432를 개방하지 않습니다. Nginx는 원래 URI와 검색 파라미터를 유지하여 /api 요청을 backend:8081로 전달하며, 상세 URL 새로고침은 index.html로 처리합니다. React 코드와 이미지 Placeholder는 그대로입니다. backend는 db:5432의 perfume DB에 perfume_user로 연결합니다. [Docker 로컬 포트 바인딩](https://docs.docker.com/engine/network/port-publishing/)

Docker Engine 28.0.0 이상을 사용하세요. 이전 버전에서는 localhost에 게시한 포트에 같은 L2 네트워크의 다른 호스트가 접근할 수 있는 예외가 있으므로, 서버 버전을 먼저 확인합니다. 기존 주식 서비스가 운영 중이면 Docker 업그레이드를 이 배포와 함께 자동으로 수행하지 말고 별도 운영 절차로 진행하세요. 위 공식 문서에 이 버전 제한이 설명되어 있습니다.

컨테이너명은 고정하지 않고 perfume-dev 프로젝트로 구분합니다. Compose 프로젝트명 perfume-dev와 DB 볼륨 perfume-dev_pgdata는 고정하며 변경하지 않습니다. stock-dividend 프로젝트명이나 stock-network/stock-db-data는 사용하지 않습니다. 다른 이름의 기존 향수 볼륨이 있다면 자동 전환하지 말고 별도 이전 절차를 검토하세요. [Compose 네트워크/프로젝트 이름](https://docs.docker.com/compose/how-tos/networking/)

backend 이미지의 기본 실행 인수는 다음과 같습니다. 기존 application.properties를 수정하지 않고 명령행 우선순위로 적용합니다.

~~~text
--server.port=8081
--scentrev.brand-import=false
--spring.jpa.hibernate.ddl-auto=validate
--spring.sql.init.mode=never
--spring.jpa.show-sql=false
~~~

DB_URL, DB_USERNAME, DB_PASSWORD 환경변수를 재사용합니다. DB_URL은 Compose가 Docker 서비스명으로 생성합니다. 호스트의 Windows localhost 주소나 SSH 터널 포트를 전달하지 않습니다. 웹 서비스에는 SCENTREV_API_KEY를 빈 값으로 전달하고 Import 옵션을 제공하지 않습니다. 기존 MCP 클라이언트는 호출 시에만 초기화됩니다. backend의 app/data 네트워크는 internal이므로 일반 인터넷 경로도 없습니다. DB에만 별도 일반 bridge 네트워크 db-access를 연결하여 호스트 포트 게시를 지원합니다. frontend는 DB 네트워크에 연결하지 않습니다. healthcheck는 저장된 향수 1개를 조회하는 REST API만 사용합니다.

## 서버 준비

Ubuntu 24.04에 Docker Engine, Buildx 및 Compose 플러그인을 준비하세요. 기존 Docker 설치가 있다면 그대로 사용합니다. [공식 설치 안내](https://docs.docker.com/engine/install/ubuntu/)

프로젝트 전체를 서버의 /srv/perfume에 준비하세요. Jenkins 자동 배포에는 develop 브랜치의 Git 작업사본이 필요하며 [향수 Jenkins 배포 문서](jenkins-perfume-deployment.md)의 최초 clone 절차를 사용합니다. 현재 미추적 src/frontend/배포 파일도 Commit/Push에 포함해야 합니다. .mvn, mvnw, pom.xml, src, frontend의 소스와 package-lock.json이 필요합니다. Windows node_modules, target, dist, .env는 서버로 복사할 필요가 없습니다.

~~~bash
cd /srv/perfume
docker compose version
docker version --format '{{.Server.Version}}'
docker compose ls
ss -ltn '( sport = :8088 )'
ss -ltn '( sport = :15432 )'
if [ ! -f .env ]; then
    cp .env.example .env
fi
chmod 600 .env
nano .env
mkdir -p backups
~~~

cp는 .env가 없는 최초 설정 시에만 실행됩니다. DB_PASSWORD를 실제 긴 비밀번호로 설정하고 COMPOSE_PROJECT_NAME=perfume-dev, DB_NAME=perfume, DB_USERNAME=perfume_user를 유지하세요. 주식 .env를 복사하지 않으며 기존 배포의 .env는 덮어쓰지 않습니다. 비밀번호에 $ 또는 #이 포함되면 예제처럼 작은따옴표로 감싸세요. .env와 DB 덤프는 Git 및 Docker 빌드 컨텍스트에서 제외합니다. ScentRev API 키는 필요하지 않습니다. 위 포트가 다른 서비스에서 사용 중이면 기존 서비스를 중지하지 말고 배포를 보류하여 충돌을 확인하세요.

서버에는 Java, Maven, Node를 별도로 설치하지 않아도 됩니다. 이미지 빌드 시 Maven/npm/이미지 저장소 접근은 필요하지만 빌드에 ScentRev 키나 DB 비밀번호를 넘기지 않습니다.

~~~bash
docker compose config --quiet
docker compose build
docker compose up -d --wait db
docker compose port db 5432
~~~

처음에는 DB만 시작합니다. 포트 출력이 127.0.0.1:15432인지 확인하세요. 빈 DB에는 애플리케이션 테이블을 자동 생성하지 않습니다. 아래 전체 복원을 완료한 후 backend/frontend를 시작하세요. 이미 복원된 DB라면 백업/복원 단계를 건너뛰고 웹 서비스를 실행하세요.

## Windows PostgreSQL 전체 백업

향수 Import 등 Windows DB 쓰기 작업을 종료하고 최초 이전과 검증이 끝날 때까지 다시 시작하지 마세요. 원본 서버 버전과 pg_dump 버전을 운영자가 확인해야 합니다. 아래 예제는 PostgreSQL 17 도구와 기존 기본 DB/사용자/포트를 사용합니다. 실제 설치 경로와 접속 사용자에 맞게 바꾸세요. 백업 대상 127.0.0.1:5432는 이전할 Windows 원본 DB이며 아래 SSH 터널의 25432가 아닙니다.

PostgreSQL 17로 복원할 백업은 17 버전의 pg_dump를 사용하세요. 원본 서버가 17보다 최신이면 이 절차의 호환성을 먼저 검토해야 합니다. [pg_dump 공식 문서](https://www.postgresql.org/docs/17/app-pgdump.html)

Windows PowerShell:

~~~powershell
cd C:\dev\projects\perfume
New-Item -ItemType Directory -Path backups -Force | Out-Null
& 'C:\Program Files\PostgreSQL\17\bin\pg_dump.exe' --version
& 'C:\Program Files\PostgreSQL\17\bin\psql.exe' -h 127.0.0.1 -p 5432 -U perfume_user -d perfume -W -c 'SHOW server_version;'
& 'C:\Program Files\PostgreSQL\17\bin\pg_dump.exe' -h 127.0.0.1 -p 5432 -U perfume_user -d perfume -W --format=custom --verbose --file=backups\perfume.dump
if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL backup failed.' }
Get-FileHash -LiteralPath backups\perfume.dump -Algorithm SHA256
scp .\backups\perfume.dump ubuntu@SERVER_IP:/srv/perfume/backups/perfume.dump
~~~

암호는 도구의 프롬프트에 입력합니다. 명령행에 암호를 적거나 PowerShell 파이프로 바이너리 덤프를 전달하지 않습니다. scp의 사용자와 SERVER_IP는 실제 서버 값으로 바꾸세요. 서버에서 sha256sum backups/perfume.dump로 전송 파일의 해시를 비교할 수 있습니다.

테이블/스키마 필터 없이 전체 DB를 custom 형식으로 백업합니다. Phase 1·2 테이블, 노트/어코드/조향사 관계, 모든 metric/opinion/similarity 데이터, perfume_provider_payloads.payload JSONB, PK/UNIQUE/FK/인덱스와 시퀀스도 포함합니다. 애플리케이션 JAR나 일부 SQL 파일을 DB 백업으로 대신 사용하지 않습니다.

## Docker PostgreSQL 전체 복원

아래는 Ubuntu Bash 명령입니다. backend/frontend가 정지한 상태에서 **새롭고 비어 있는 대상 DB에 최초 한 번** 복원합니다. 기존 데이터가 있는 볼륨에 재복원하지 않습니다.

~~~bash
cd /srv/perfume
docker compose stop backend frontend
docker compose up -d --wait db
docker compose cp ./backups/perfume.dump db:/tmp/perfume.dump
docker compose exec -T db sh -s <<'RESTORE'
set -eu
relation_count="$(psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At -v ON_ERROR_STOP=1 -c "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname !~ '^pg_' AND n.nspname <> 'information_schema' AND c.relkind IN ('r', 'p', 'S', 'v', 'm', 'f');")"
if [ "$relation_count" -ne 0 ]; then
    echo "Restore refused: target database already contains user objects." >&2
    exit 1
fi
pg_restore --verbose --exit-on-error --single-transaction --no-owner --no-privileges \
    -U "$POSTGRES_USER" -d "$POSTGRES_DB" /tmp/perfume.dump
RESTORE
~~~

**복원 명령이 0으로 종료했을 때만** 다음 단계로 진행하세요. pg_restore는 단일 트랜잭션으로 실행되어 실패하면 이번 복원을 롤백합니다. --clean/--create/테이블 삭제/볼륨 삭제는 사용하지 않습니다. 소유권과 접근 권한은 Docker의 DB_USERNAME 계정으로 맞추며, 스키마와 데이터는 전체 복원합니다. [pg_restore 옵션](https://www.postgresql.org/docs/17/app-pgrestore.html)

복원 후 운영자가 확인할 명령:

~~~bash
docker compose exec -T db sh -s <<'VERIFY'
set -eu
psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 \
    -c '\dt public.*' \
    -c 'SELECT count(*) AS brands FROM brands;' \
    -c 'SELECT count(*) AS perfumes FROM perfumes;' \
    -c 'SELECT count(*) AS provider_payloads FROM perfume_provider_payloads;' \
    -c "SELECT data_type FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'perfume_provider_payloads' AND column_name = 'payload';"
VERIFY
~~~

원본 DB와 테이블/행 수를 비교하고 마지막 결과가 jsonb인지 확인하세요. 보조 관계/평가 테이블도 원본과 비교하세요. 기존에 필요한 스키마가 백업에 없다면 validate가 실패하며, 자동 DDL로 보충하지 않습니다.

## Windows SSH 터널: Eclipse와 DBeaver가 같은 Ubuntu DB 사용

Windows PowerShell에서 다음 명령을 실행하고 터널 창을 열어 두세요. ubuntu와 SERVER_IP를 실제 SSH 계정/서버로 바꿉니다. 키 인증이 필요하면 -i 옵션으로 기존 개인키 경로를 지정하세요.

~~~powershell
ssh -N -T -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -L 127.0.0.1:25432:127.0.0.1:15432 ubuntu@SERVER_IP
~~~

연결 경로는 Windows 127.0.0.1:25432 → SSH → Ubuntu 127.0.0.1:15432 → 향수 db:5432입니다. Windows의 기존 PostgreSQL 5432 포트와 겹치지 않도록 터널은 25432를 사용합니다. 이미 25432가 사용 중이면 다른 로컬 포트를 선택하고 아래 두 도구의 포트도 함께 바꾸세요. Ctrl+C로 터널을 종료합니다. SSH 서버에서 TCP 포워딩이 허용되어 있어야 하며 Ubuntu의 SSH 포트만 접근 가능하면 됩니다. [OpenSSH -L 및 -N 옵션](https://man.openbsd.org/ssh.1)

Eclipse의 PerfumeApplication 전용 Run Configuration을 만들거나 기존 웹 실행 설정을 선택합니다. Environment에 다음 값을 지정하세요. 비밀번호는 서버 향수 .env의 DB_PASSWORD와 같은 값을 입력하며 문서의 예제 문자열을 사용하지 않습니다.

~~~text
DB_URL=jdbc:postgresql://127.0.0.1:25432/perfume
DB_USERNAME=perfume_user
DB_PASSWORD=<Ubuntu perfume DB 비밀번호>
SCENTREV_API_KEY=
~~~

Program arguments에는 다음을 지정합니다. 기존 VM/Program arguments의 import 실행 옵션과 별도 datasource URL 덮어쓰기를 제거하고, 일반 웹 실행 설정에 API 키를 넣지 마세요.

~~~text
--server.port=8081
--scentrev.brand-import=false
--spring.jpa.hibernate.ddl-auto=validate
--spring.sql.init.mode=never
--spring.jpa.show-sql=false
~~~

Docker의 Spring Boot는 계속 db:5432로 연결하고 Eclipse만 127.0.0.1:25432를 사용합니다. 복원 후 Windows의 예전 DB는 백업으로 보관하되 향수 앱을 localhost:5432로 실행하지 않습니다. 이후 필요한 수동 Import 역시 운영자가 별도로 설정할 때 이 Ubuntu DB 연결을 사용해야 하며 이 문서의 웹 실행은 Import를 실행하지 않습니다.

DBeaver에서는 PostgreSQL 연결을 만들고 다음 값을 사용하세요.

| 항목 | 값 |
| --- | --- |
| Host | 127.0.0.1 |
| Port | 25432 |
| Database | perfume |
| Username | perfume_user |
| Password | Ubuntu 향수 DB 비밀번호 |
| DBeaver 내장 SSH 터널 | 비활성화: 위 PowerShell SSH 터널을 공용으로 사용 |

같은 SSH 터널을 Eclipse와 DBeaver가 함께 사용합니다. 터널을 열고 DBeaver의 Test Connection을 실행하세요. 연결 후 SELECT current_database(), current_user; 결과가 perfume/perfume_user인지 확인하고 Docker에서 확인한 브랜드·향수 행 수와 비교하세요. 터널이 닫히거나 DB가 정지하면 두 도구 모두 연결되지 않습니다.

## 웹 서비스 실행 및 확인

복원과 위 확인을 마친 후:

~~~bash
cd /srv/perfume
docker compose up -d --no-deps --no-build --wait backend frontend
docker compose ps
docker compose logs --tail=100 backend frontend
curl -fsS http://localhost:8088/healthz
curl -fsS http://localhost:8088/api/perfumes?size=1
~~~

브라우저 주소는 **http://SERVER_IP:8088**입니다. 검색/브랜드 필터/페이지 이동과 실제 DB의 상세 URL을 새로고침하여 확인하세요. 서버 네트워크에서 TCP 8088 접근을 허용해야 합니다. Docker의 게시 포트는 UFW 규칙을 우회할 수 있으므로 실제 서버의 네트워크/방화벽 규칙을 함께 확인하세요. [Docker Ubuntu 네트워크 주의사항](https://docs.docker.com/engine/install/ubuntu/#firewall-limitations)

앞 단계에서 기존 DB가 healthy임을 확인한 후 backend/frontend만 시작합니다. healthcheck 또는 validate가 실패하면 logs를 확인하세요. 일반 웹 실행은 Import를 실행하지 않습니다. Jenkins를 사용한다면 모든 복원/웹 검증을 마친 후 위 Jenkins 문서의 수동 복원 완료 표식을 작성하세요.

## 이후 업데이트와 데이터 유지

코드/배포 파일을 업로드하고 기존 .env와 COMPOSE_PROJECT_NAME을 유지한 상태에서:

~~~bash
cd /srv/perfume
docker compose build backend frontend
docker compose up -d --no-deps --no-build --wait backend frontend
~~~

이미 복원한 DB를 다시 복원할 필요가 없습니다. 정지가 필요하면 docker compose stop을 사용하세요. docker compose down은 기본적으로 named volume을 유지하지만 **down -v**, volume rm, 볼륨 정리 작업은 DB를 삭제할 수 있으므로 이 배포 절차에서 사용하지 않습니다.

PostgreSQL 이미지의 초기화는 빈 데이터 디렉터리에만 적용됩니다. 기존 pgdata가 있으면 기존 데이터를 사용하고 자동 초기화 SQL은 실행하지 않습니다. 이 구성에는 initdb 스크립트 마운트나 DB 삭제 스크립트가 없습니다. 최초 볼륨 생성 이후 .env의 DB_PASSWORD/DB_USERNAME/DB_NAME을 바꾸는 것만으로 기존 DB 자격증명이나 DB명이 변경되지는 않습니다. [PostgreSQL 공식 이미지](https://hub.docker.com/_/postgres)

## 검증 범위

- Compose config 문법과 127.0.0.1:15432 DB 바인딩, 웹 8088, 내부 JDBC 주소, 프로젝트 전용 네트워크/영구 볼륨, 주식 리소스 참조가 없음을 로컬에서 확인했습니다.
- Java 17·Maven 3.9.16으로 오프라인 package 성공: 490개 테스트 중 475개 통과, 실패/오류 0, live/manual 15개 skipped.
- 기존 React 프로덕션 빌드 및 로직/REST 클라이언트 테스트 8개가 통과했습니다.
- 문서의 Ubuntu Bash 명령 6개 블록은 bash -n 문법 검사를 통과했습니다. 백업/복원/배포 명령 자체는 실행하지 않았습니다.
- Docker 엔진이 실행되어 있지 않아 실제 컨테이너 이미지 빌드와 Nginx 실행 검증은 수행하지 못했습니다. Ubuntu에서 위 docker compose build를 실행하면 frontend 이미지 빌드 중 nginx -t도 실행합니다.
- 실제 서버 접속, 배포, Windows DB 백업, Docker DB 복원/변경, ScentRev 호출 및 Import는 수행하지 않았습니다.
- Dockerfile이 사용하는 기존 Linux Maven Wrapper는 Git Bash에서 -v로 Maven 3.9.16 실행을 확인했습니다. Windows mvnw.cmd는 로컬 PowerShell의 디렉터리 Target 처리에서 실패하여, package는 동일 버전의 캐시된 Maven을 직접 실행하고 기존 로컬 저장소를 명시했습니다. Wrapper 원본은 수정하지 않았습니다.
- Java/React/Import 소스와 백엔드 Dockerfile/Nginx 및 주식 Compose/Jenkinsfile은 유지합니다. 프론트엔드 Dockerfile은 기존 테스트 8개 모두를 빌드에 포함하도록 복사 범위만 보완했습니다. 향수 Jenkins 자동 배포 검증 범위는 위 Jenkins 문서에 기재합니다.

참고: [Maven Wrapper](https://maven.apache.org/tools/wrapper/), [Nginx 프록시 URI 처리](https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_pass), [React Router 정적 경로 fallback에 사용한 try_files](https://nginx.org/en/docs/http/ngx_http_core_module.html#try_files).
