# 기존 Jenkins를 사용하는 향수 전용 Pipeline

주식 Jenkinsfile의 Docker CLI 빌드 방식을 참고합니다. 기존 Jenkins/주식 컨테이너, stock-db, stock-network, stock-db-data 및 주식 Job은 변경하지 않습니다. 향수 배포는 SSH로 Ubuntu의 /srv/perfume에서 실행하므로 Jenkins에 새 디렉터리를 마운트하거나 컨테이너를 재시작할 필요가 없습니다.

## 고정 구성과 보호 범위

- GitHub: https://github.com/yuchulPark/perfume.git, 브랜치 develop
- Compose 프로젝트: perfume-dev
- PostgreSQL 17 서비스: db, 볼륨: perfume-dev_pgdata
- DB: perfume, 사용자: perfume_user
- 웹: 8088, DB 호스트 바인딩: 127.0.0.1:15432:5432
- 서버 비밀 설정: /srv/perfume/.env (Git 제외, 운영자가 최초 설정)
- 복원 완료 표식: /srv/perfume/.perfume-db-ready (Git 제외, 운영자가 수동 작성)

Pipeline은 체크아웃 → 서버 사전 검사 → 오프라인 배포 보호 테스트 → Maven Wrapper package/테스트 → React 테스트/빌드 → 향수 앱 배포 순서입니다. 두 Dockerfile의 build 단계를 CI에서 빌드합니다. 서버에서 Compose 빌드 시 같은 Docker 엔진의 BuildKit 캐시를 재사용할 수 있습니다. 테스트용 PostgreSQL은 만들지 않으며 ScentRev 키와 라이브 테스트 플래그를 빌드에 전달하지 않습니다.

배포는 기존 DB의 상태·인증·Phase 1/2 테이블 존재를 읽기 쿼리로 확인한 뒤 backend/frontend만 --no-deps로 갱신합니다. DB 컨테이너 ID와 .env 해시가 유지되는지도 확인합니다. DB 생성/기동/복원, 비밀번호 변경, 자동 DDL, down, Git clean, 볼륨 삭제 및 Docker 전역 정리는 Pipeline에 없습니다. [Compose --no-deps](https://docs.docker.com/reference/cli/docker/compose/up/)

## 기존 실행 환경 확인

기존 주식 Job이 사용하는 Jenkins 컨테이너명 jenkins, Docker CLI/소켓 접근, WORKSPACE가 포함된 Jenkins 볼륨을 재사용합니다. Docker Python 테스트 컨테이너는 --volumes-from jenkins로 작업공간을 읽습니다. 다른 컨테이너명/에이전트를 쓰는 경우 향수 Jenkinsfile만 맞추며 기존 Jenkins 컨테이너는 수정하지 않습니다.

Jenkins 실행 노드에 git, docker, bash, ssh, base64, tr이 필요합니다. Ubuntu SSH 계정에는 /srv/perfume의 읽기/쓰기 권한과 Docker 실행 권한, 비대화형 GitHub read 접근이 필요합니다. Ubuntu에는 Docker Compose, Python 3, flock, sha256sum, curl이 필요합니다. 이들 접근과 도구가 없으면 Pipeline은 실패하며 컨테이너 설정을 자동 수정하지 않습니다.

기존 Pipeline/Git/Credentials Binding/SSH Credentials 플러그인을 이용합니다. 설치 상태만 확인하며 이 구성 작업에서 플러그인 설치·Jenkins 재시작은 수행하지 않습니다. stock과 perfume 빌드가 동시에 실행되면 서버 CPU/메모리를 공유하므로 첫 실행은 주식 배포 시간과 겹치지 않게 잡으세요.

## 향수 Job용 Jenkins Credentials

새 향수 Folder/Job에서 접근 가능한 범위에 다음 항목을 등록합니다. 비밀번호/키 본문을 Jenkinsfile, Git URL, build parameters 또는 소스 파일에 적지 않습니다.

| ID | Kind | 값/용도 |
| --- | --- | --- |
| perfume-ubuntu-host | Secret text | Jenkins에서 접근 가능한 Ubuntu IP/DNS. SSH 포트는 22 |
| perfume-ubuntu-ssh | SSH Username with private key | Ubuntu 배포 계정과 전용 SSH 개인키. 이 구현은 비대화형 사용 가능한 passphrase 없는 전용 키를 사용 |
| perfume-ubuntu-known-hosts | Secret file | 위 IP/DNS에 대한 검증된 OpenSSH known_hosts 파일 |
| perfume-db-password | Secret text | 서버 향수 .env의 DB_PASSWORD와 정확히 같은 기존 DB 비밀번호 |
| perfume-github-read | Username with password 또는 SSH Username with private key | 비공개 GitHub 저장소일 때 SCM 조회용. 공개 저장소라면 생략 가능 |
| perfume-scentrev-api-key | Secret text, 선택 사항 | 향후 별도 수동 Import용 보관. 현재 웹/CI Pipeline에는 바인딩하지 않음 |

SSH 호스트 키는 Ubuntu의 실제 호스트 키 지문과 대조한 뒤 known_hosts 파일로 등록하세요. 예: 서버에서 ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub로 지문 확인. 호스트 키 검사를 끄지 않습니다. 접속 키의 공개키만 배포 계정의 authorized_keys에 등록합니다.

DB 비밀번호는 서버 .env와 Jenkins Credentials에서 관리합니다. Jenkins는 비교에만 사용하고 .env에 쓰거나 기존 DB 비밀번호를 변경하지 않습니다. 자격증명이 다르면 배포가 중단됩니다. 비밀번호를 회전할 때는 운영자가 DB/서버 .env/Jenkins Credential을 별도 절차로 함께 맞춰야 합니다.

withCredentials 범위에서만 SSH/DB 자격증명을 사용하고 셸 추적을 끕니다. DB 비밀번호는 SSH의 암호화된 stdin으로 전달하며 명령행 인수·아티팩트에 넣지 않습니다. 키 파일은 Credentials Binding이 생성하는 임시 파일을 사용합니다. [Jenkins Credentials Binding](https://www.jenkins.io/doc/pipeline/steps/credentials-binding/)

## 최초 배포: DB 이전은 운영자가 수동 수행

먼저 로컬 변경 파일을 검토하여 향수 저장소 develop에 Commit/Push합니다. 아래 명령은 운영자가 Ubuntu에서 실행할 안내이며 구현 중에는 실행하지 않았습니다.

1. /srv/perfume이 없으면 생성하고 배포 SSH 계정에 이 디렉터리의 권한을 부여합니다. 예시 계정 devops는 실제 계정으로 바꿉니다.

~~~bash
sudo install -d -o devops -g devops -m 0750 /srv/perfume
~~~

2. 배포 계정으로 빈 디렉터리에 최초 한 번 clone합니다. 기존 clone이 있으면 재생성하지 않습니다. 비공개 저장소는 서버 계정에도 별도의 GitHub 읽기 인증을 설정하세요. URL에 PAT를 넣지 않습니다.

~~~bash
cd /srv/perfume
git clone --branch develop https://github.com/yuchulPark/perfume.git .
if [ ! -f .env ]; then
    cp .env.example .env
fi
chmod 600 .env
nano .env
mkdir -p backups
docker compose --project-name perfume-dev config --quiet
docker compose --project-name perfume-dev build backend frontend
docker compose --project-name perfume-dev up -d --wait db
docker compose --project-name perfume-dev port db 5432
~~~

DB_NAME=perfume, DB_USERNAME=perfume_user, COMPOSE_PROJECT_NAME=perfume-dev를 유지하고 비밀번호만 실제 값으로 설정합니다. 기존 .env는 복사/덮어쓰기하지 않습니다. 포트는 127.0.0.1:15432여야 합니다. 빈 DB에서 웹 서비스부터 시작하지 않습니다. 이미 .env/백업이 있는 비Git 디렉터리라면 여기에 clone을 강행하지 말고 운영 파일을 보존한 Git 작업사본 준비를 먼저 검토하세요. Pipeline은 Git 작업사본을 자동 생성하지 않습니다.

3. [Docker 배포 문서](docker-compose-deployment.md)의 Windows 전체 백업 → /srv/perfume/backups 전송 → 빈 perfume DB에 최초 복원 → 원본과 행 수/JSONB/관계 테이블 비교를 수행합니다. 이미 복원된 perfume-dev_pgdata가 있으면 이 단계를 반복하지 않습니다. Windows 원본과 Ubuntu DB를 계속 별도로 갱신하지 말고 이후 Eclipse/DBeaver는 문서의 SSH 터널로 Ubuntu DB에 연결합니다.

4. 복원이 성공한 뒤 웹 서비스를 수동으로 한 번 시작하여 스키마 validate와 실제 REST 응답을 확인합니다.

~~~bash
cd /srv/perfume
docker compose --project-name perfume-dev up -d --no-deps --no-build --wait backend frontend
docker compose --project-name perfume-dev ps
curl -fsS 'http://127.0.0.1:8088/api/perfumes?size=1' >/dev/null
~~~

5. 위 복원/검증이 모두 완료된 경우에만 수동 완료 표식을 작성합니다.

~~~bash
cd /srv/perfume
printf '%s\n' perfume-dev_pgdata > .perfume-db-ready
chmod 600 .perfume-db-ready
~~~

Pipeline은 이 표식을 생성하거나 수정하지 않습니다. DB/볼륨/표식이 없거나 DB가 정지하면 최초 초기화를 시도하지 않고 실패합니다. 기존 볼륨 이름이 다른 경우도 자동 이전하지 않습니다.

## 새 Jenkins Pipeline 등록

1. 기존 Jenkins에서 New Item → 이름 perfume-develop → Pipeline을 선택합니다. 주식 Job을 복제하거나 수정하지 않습니다.
2. Definition: Pipeline script from SCM, SCM: Git.
3. Repository URL: https://github.com/yuchulPark/perfume.git. 비공개 저장소라면 위 SCM Credentials를 선택합니다.
4. Branch Specifier: */develop. Script Path: Jenkinsfile. 기본 refspec이 origin/develop을 가져오는지 확인합니다.
5. 저장 후 최초 DB 이전·표식·Credentials 설정이 끝난 상태에서 Build Now를 실행합니다.
6. 최초 실행부터 새 Job에만 H/5 * * * * SCM polling이 등록됩니다. 이후 develop 변경을 약 5분 주기로 감지합니다. 기존 Jenkins 전역 설정이나 주식 트리거는 변경하지 않습니다.

별도 GitHub Webhook이나 SSH Agent 플러그인은 필수로 추가하지 않습니다. 중복 배포는 disableConcurrentBuilds와 /srv/perfume/.perfume-deploy.lock으로 차단합니다. 체크아웃한 커밋 SHA를 서버 develop 이력에서 확인하고 그 SHA만 배포하므로 테스트 이후 추가된 다른 커밋을 자동으로 섞지 않습니다.

브라우저: http://SERVER_IP:8088. 배포 후 backend/frontend healthy, REST 응답, 동일한 DB 컨테이너 ID를 확인합니다.

## 이후 코드 재배포와 실패 대응

develop에 Push하면 같은 Pipeline이 코드/이미지만 갱신합니다. 서버 .env, backups, .perfume-db-ready 및 PostgreSQL 볼륨을 보존하며 dump를 재복원하지 않습니다. incoming 커밋에 .env/덤프/백업/운영 표식이 들어 있으면 Git reset 이전에 거부합니다.

서버 Git 작업사본은 자동 배포용으로 유지하고 소스 수정을 서버에서 직접 하지 않습니다. Git reset은 테스트한 커밋의 추적 소스만 갱신합니다. 보호된 운영 파일이 아닌 추적 소스의 현지 수정은 유지되지 않습니다.

사전 검사/테스트/이미지 빌드 실패 시 기존 앱 교체를 시작하지 않습니다. 앱 교체 후 health 검사에 실패하면 Job을 확인하고 이전 정상 코드/이미지로 앱만 수동 복구합니다. DB 롤백·스키마 변경·백업 덮어쓰기·자동 데이터 삭제는 수행하지 않습니다. DB를 교체/복원할 운영 작업 중에는 향수 Job만 중지하고 작업 후 표식과 접속을 다시 검증하세요.

## 로컬 검증

- Compose 모델의 프로젝트명/볼륨/포트/네트워크/비밀번호 일치 및 Import 비활성화 보호 검증.
- 배포 보호 Python 테스트 10개 통과. Bash 스크립트 2개, 문서 Bash 블록 10개, Jenkins 셸 단계 6개 문법 검사 통과.
- Maven 오프라인 package 성공: 490개 중 475개 통과, live/manual 15개 skipped, 실패/오류 0.
- 기존 React 테스트 8개와 프로덕션 빌드 통과.
- Jenkinsfile은 공개 체크섬을 검증한 Groovy 컴파일러로 오프라인 문법 컴파일을 통과했습니다. Jenkins Declarative 모델/플러그인에 대한 서버 Linter 검증과는 구분합니다.
- 실제 Docker 이미지 빌드/SSH/Declarative Pipeline 서버 검증은 기존 Jenkins와 Ubuntu에서 운영자가 수행해야 합니다. 로컬 Docker 엔진과 Jenkins 접속이 없어 해당 실행은 수행하지 않았습니다.
- 실제 서버 배포, DB 생성·복원·변경, ScentRev 호출은 수행하지 않았습니다.

필요하면 Jenkins의 Pipeline Syntax 화면에서 현재 플러그인에 맞는 DSL을 확인하고, 실행하지 않는 Declarative Linter로 Jenkinsfile을 검증하세요. [공식 Pipeline Linter](https://www.jenkins.io/doc/book/pipeline/development/#linter)
