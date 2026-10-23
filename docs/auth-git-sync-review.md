# HTTP 로그인 소스·배포 동기화 검토

2026-10-24 KST. Windows 로컬 소스, GitHub main, `devops@192.168.0.19:22`의 `/srv/perfume`을 비교했다. 서버에서는 읽기 전용 진단과 로그인 검증만 수행했다. Commit/Push, Git fetch/reset, Jenkins 실행, 컨테이너 빌드·재시작·재배포, DDL, 계정/비밀번호 변경은 수행하지 않았다. 기존 Git 스테이징도 변경하지 않았다.

## 현재 차이와 판단

| 대상 | 확인 결과 |
|---|---|
| 로컬 HEAD / GitHub main / 서버 HEAD | 모두 `ce48caca235756c08ad77ca32e1bb8a2c647fd2d` |
| 로컬 변경 | HTTP 지원 소스·설정·테스트·문서 19개가 이미 스테이징되어 있었다. 이번에는 검토 문서·최신 기록 링크와 브라우저 테스트의 고정 포트 수정을 추가했다. |
| 서버 체크아웃 | `scripts/validate-perfume-compose.py` 수정, `compose.auth-dev-http.yaml` 미추적. 서버의 Java 소스와 `jenkins-deploy.sh`는 이전 커밋 상태다. |
| 실행 backend | 수동 교체한 HTTP 지원 JAR로 실행 중이다. 서버 소스 체크아웃과 실행 아티팩트의 상태가 다르다. |
| JAR 일치 | 실행 `/app/app.jar`, 서버 보관 JAR, 로컬 `target/perfume-auth-dev-http.jar`의 SHA-256이 `9cdf73c482ad24744cce47a7c349b4b3d31f21221849560ddc017577af0d4b68`로 일치한다. |
| 소스와 JAR | 로컬에서 테스트하며 컴파일한 운영 클래스 248개가 보관 JAR의 클래스와 모두 일치한다. 기본 properties와 HTTP profile properties도 줄바꿈을 정규화하면 일치한다. |
| 서버와 같은 파일 | 기본 Compose, HTTP override, validator, 기존 HTTP Nginx 구성이 로컬 파일과 바이트 단위로 일치한다. |

현재 main을 그대로 재배포하면 HTTP 전용 코드와 Compose 선택 로직이 없어 현재 로그인 동작을 보장할 수 없다. 준비된 변경을 **한 커밋에 함께 포함**해야 한다. 테스트를 통과한 소스 반영 준비와 실제 Jenkins/Docker 재배포 성공은 구분한다.

최근 커밋은 `ce48cac` 로그인/추천 인증, `33b5e2a` rerank prototype, `2578181` 추천 평가, `16dace5` 임베딩 이어하기, `7b5be9d` 근거 번호 개선 순서다. 이번 변경에 AI 알고리즘·임베딩 배치 변경은 없다.

## Jenkins에서 HTTP 설정이 유지되는 경로

실제 `perfume-main` job 설정은 저장소 `yuchulPark/perfume`, 브랜치 `*/main`, Pipeline 경로 `Jenkinsfile`, 활성 상태, SCM 주기 `H/5 * * * *`였다. 자격증명 내용은 출력하지 않았다. 따라서 main Push는 자동 배포로 이어질 수 있다.

1. Jenkins가 main을 체크아웃하고 해당 커밋의 `scripts/jenkins-deploy.sh`를 SSH 표준입력으로 전송한다. 서버에 남은 이전 스크립트를 직접 실행하는 구조가 아니다.
2. 새 스크립트는 상속된 인증·profile·AI 환경변수를 제거하고 서버 `.env`의 `PERFUME_AUTH_DEV_HTTP_ENABLED` 리터럴 값만 읽는다. 중복·잘못된 값·셸 표현식은 실행하지 않고 거부한다.
3. true이면 모든 Compose 검증·빌드·앱 교체 단계에서 기본 Compose와 `compose.auth-dev-http.yaml`을 함께 사용한다. 기존 `auth-dev` profile은 사용하지 않는다.
4. 서버 체크아웃 갱신 전 최초 preflight에도 override와 새 validator가 필요하다. 두 파일은 현재 서버에 있고 로컬과 일치한다. 이 조건은 이후 소스가 main에 반영될 때도 유지해야 한다.
5. Git에 포함된 `.env*` 비밀 파일/백업 등은 reset 전에 거부하고 서버 `.env`, DB 준비 표식 및 승인 파일의 해시를 확인한다. `.env`를 Git에서 복원하거나 예제로 덮어쓰지 않는다.
6. backend/frontend만 `up --no-deps --no-build`로 교체한다. DB 컨테이너 ID·볼륨과 `.env`를 교체 전후 검사한다. Ollama는 앱 교체 대상에 없다.

현재 서버 `.env`에 유지할 값은 다음 두 개다. 이미 적용되어 있으므로 이번 작업에서 다시 수정하지 않았다.

```dotenv
PERFUME_AUTH_ENABLED=true
PERFUME_AUTH_DEV_HTTP_ENABLED=true
```

`SPRING_PROFILES_ACTIVE=auth-dev-http`, origin `http://192.168.0.19:8088`, Secure=false는 HTTP override가 제공한다. profile properties가 `PERFUME_DEV_SESSION`, HttpOnly=true, SameSite=Lax, 쿠키 기반 추적을 제공한다. 기본 Compose와 일반 profile의 Secure=true는 유지한다. `auth-dev-http` 단독 profile·명시적 flag·고정 DB/origin·사설 프록시 IP·Host/Origin 검사가 필요하며 CSRF, BCrypt, 세션 ID 회전, 로그아웃 무효화, 추천 API 401은 유지한다.

로컬 루트 `.env`는 없다. Git의 `.env.example`은 인증/HTTP/AI 활성화 기본 false이며 비밀번호는 placeholder다. 서버는 네 활성화 flag가 true이고 모델 식별자·DB 이미지·DB 비밀번호 등 서버 전용 키를 가진다. 비밀번호나 모델 식별자 값을 소스에 복사하지 않았다. 서버 `.env` 권한은 0600이며 해시는 기존 HTTP 전환 직후 기록과 동일하다.

수동 읽기 전용 Compose 검증 시에도 두 파일을 명시한다. 전체 렌더링 결과에는 비밀번호가 포함되므로 터미널에 출력하지 않는다.

```bash
cd /srv/perfume
docker compose --project-name perfume-dev --project-directory /srv/perfume \
  --env-file /srv/perfume/.env --file /srv/perfume/compose.yaml \
  --file /srv/perfume/compose.auth-dev-http.yaml config --quiet
```

기본 Compose만 사용한 수동 `up`이나 이전 커밋으로의 rollback은 HTTP 예외를 유지하지 못할 수 있다. 현재 인증 계정·스키마는 이미 준비되어 있어 DDL과 Bootstrap을 다시 실행할 필요가 없다. HTTP에는 전송 암호화가 없으므로 개인의 신뢰하는 LAN에서만 사용하고 외부 공개/포트포워딩을 하지 않는다. HTTPS/TLS는 다시 추가하지 않는다.

## HTTPS 제거와 데이터 보존

- 8443 TCP 연결 불가, 전용 HTTPS 프로젝트 컨테이너 없음. 서버의 `deploy/auth-https/{compose.yaml,nginx.conf}`, `.env.auth-https`, `.env.auth-tls/{server.pem,server-key.pem}`은 모두 없다. 로컬 HTTPS 전용 소스도 없다. 이 파일들은 main에 포함된 적이 없어 Git 삭제 항목이 없는 것이 정상이다.
- 기존 HTTP Nginx는 유지되며 실행 컨테이너에서 `nginx -t`가 통과했다. Windows CA는 요청대로 보존하며 이번 작업에서는 접근·제거하지 않았다.
- DB system identifier는 기존 기록과 같은 `7694641528401440806`이다. DB 컨테이너 `8219655794b5`, Ollama `bccad792dd64`와 각각 `perfume-dev_pgdata`, `perfume-dev_ollama-data` mount가 기존 기록과 같다.
- 읽기 전용 SQL로 public/ai 테이블 16개의 행 수를 기록했다. 향수 4,048개, 임베딩 678개다. 기존 관리자 테이블 내용의 집계 digest도 이전 HTTP 적용 후 기록과 같으며 원문/BCrypt 해시는 출력하지 않았다.
- backend/frontend/DB/Ollama는 모두 healthy였다. 모델 목록에서 기존 `qwen3-embedding:0.6b`, `qwen3:4b-instruct` 두 개를 확인했다. 모델 다운로드·추론·임베딩 작업은 실행하지 않았다.
- 행 수·클러스터/볼륨 ID·모델 목록은 보존 근거이며 전체 데이터 블록의 checksum 검증은 아니다. 임베딩 체크포인트 내부 파일은 권한상 미검증이며 파일이나 권한을 변경하지 않았다.

## 이번 테스트

실행 로그와 비밀값을 제거한 증거는 Git 제외 경로 `target/auth-sync-review/`에 있다. 최종 집계는 아래에 기록한다.

| 검사 | 결과 |
|---|---|
| Java 전체 | 1,193건: 실행 1,176 통과, 17 생략, 실패/오류 0 |
| React Node 단위 | 50 통과, 실패/생략 0 |
| Vite production build | 성공 |
| React desktop/tablet/mobile 브라우저 모의 회귀 | 최초 전체 99건 중 96 통과·3 실패; 고정 포트 수정 후 해당 3건 모두 통과. 미해결 실패 0 |
| Compose/배포 보호/Jenkins 모의 배포 전체 | 87 통과, 실패/생략 0; Jenkins 모의 배포 10건 포함 |
| 실제 HTTP8088 Chrome | 11개 검증 그룹 모두 통과 |
| 실제 서버 구성 | HTTP Compose validator 통과, `nginx -t` 통과, 기존 네 서비스 healthy |
| JAR/로컬 클래스 비교 | SHA-256 및 운영 클래스 248개 일치 |
| Git diff/비밀 패턴 검사 | 기존 staged diff 공백 오류 없음; 변경 후보에 개인키·고정 BCrypt 해시·실제 비밀번호 할당 발견 없음 |

실제 HTTP Chrome에서는 공개 목록·상세·검색, 비로그인 추천의 모든 진입점 JSON 401, 안내창/로그인 이동, admin 로그인/추천 복귀/질문 유지, 새로고침 세션 복원, HttpOnly/SameSite=Lax/Secure=false 쿠키, 세션 ID 교체, CSRF 누락·이전 토큰 403, 잘못된 Host/Origin 403, 로그아웃 200/기존 세션 무효화/추천 401을 확인했다. 인증 후에는 유효한 세션/CSRF로 잘못된 입력 400 및 존재하지 않는 Job 404를 확인해 추론 없이 인증 경계 통과를 확인했다.

Java 17개 생략은 외부 공급자·실제 DB 등 별도 실행 gate 15개와 새 격리 PostgreSQL 초기화/통합 gate 2개다. 기존 DB/계정의 초기화 테스트를 강제로 켜지 않았다. 실제 Ollama 추천 완료·취소는 사용자 확인과 구분해 이번에는 미실행으로 기록한다. 실제 Jenkins 실행, 새 Docker 이미지 빌드/재배포, 배포 후 브라우저 확인, 자연 세션 만료 대기는 미검증이다.

브라우저 최초 실패 3건은 같은 검색 초기화 테스트의 desktop/tablet/mobile 결과다. 임시 preview를 4184로 실행했지만 테스트가 최종 URL을 `http://127.0.0.1:4173/`로 고정해 정상적인 4184 복귀를 실패로 처리했다. `frontend/tests/browser/catalog.spec.js`에서 Playwright `baseURL` fixture로 정확한 루트 URL을 구성하도록 수정했다. 경로/쿼리 초기화 검증은 그대로 유지한다. 앱의 검색·인증 구현은 변경하지 않았다.

수정 후 실패했던 3건만 재실행해 3건 모두 통과했다. 최종 결과는 기존 96건 통과와 수정 후 3건 통과를 합친 것이며, 수정 후 전체 99건을 한 번 더 실행한 것은 아니다.

배포 검사 로그의 test volume 제거·복구 메시지는 대체 Docker 명령을 사용하는 격리 fixture 출력이다. 실제 서버 볼륨을 제거/복구한 결과가 아니다. 실제 Docker CLI 검사는 Compose 오프라인 렌더링과 서버의 읽기 전용 inspect/exec에 한정했다.

로컬에서 실행한 주요 명령은 다음과 같다. Maven은 이미 준비된 Maven 3.9.16과 기존 로컬 cache를 사용했다. Chrome 모의 회귀는 기존 preview를 중단하지 않도록 임시 config로 4184를 사용했다. 현재 실행의 Maven log에 나온 100개 suite만 집계했으며 과거에 남은 surefire XML은 제외했다.

```powershell
& .\target\phase1-validation\apache-maven-3.9.16\bin\mvn.cmd -o -B -ntp `
  "-Dmaven.repo.local=$env:USERPROFILE\.m2\repository" "-Duser.home=$env:USERPROFILE" test
& "$env:LOCALAPPDATA\Programs\pgAdmin 4\python\python.exe" -B -m unittest discover -s tests/deployment -v
npm.cmd --prefix frontend test
npm.cmd --prefix frontend run build
node frontend/node_modules/@playwright/test/cli.js test --config target/auth-sync-review/playwright.config.mjs
node frontend/node_modules/@playwright/test/cli.js test --config target/auth-sync-review/playwright.config.mjs --grep 'debounced search'
git diff --cached --check
git diff --check
```

## 커밋 범위와 제외 목록

기존 스테이징 19개 파일을 함께 반영한다. 이번 문서 링크 수정은 해당 파일의 unstaged 변경이고 새 검토 문서는 미추적 상태다. 사용자의 staging 상태를 임의로 갱신하지 않았다.

- 설정/수동 적용: `.env.example`, `compose.auth-dev-http.yaml`, `deploy/auth-dev-http/Dockerfile.runtime`.
- Java: `AuthDevelopmentHttp.java`, `AuthDataConfiguration.java`, `AuthSecurityConfiguration.java`, `application-auth-dev-http.properties`, `application.properties`.
- 배포/진단: `scripts/jenkins-deploy.sh`, `scripts/validate-perfume-compose.py`, `scripts/diagnose-auth-dev-server.sh`.
- Java 회귀: `AuthDevelopmentHttpTests.java`, `AuthDevelopmentHttpApiTests.java`, `AuthHttpSessionTests.java`.
- 배포 회귀: `tests/deployment/test_auth_dev_http_compose.py`, `test_compose_guard.py`, `test_jenkins_db_preservation.py`.
- 문서: `docs/authentication.md`, `docs/auth-ubuntu-development.md`, 새 `docs/auth-git-sync-review.md`.
- 브라우저 테스트 수정: `frontend/tests/browser/catalog.spec.js`.

총 소스/설정/테스트/문서 후보는 21개다. 추가 런타임 코드 수정은 필요하지 않았다. `Jenkinsfile`, 기본 Compose, 기존 HTTP Nginx, 프런트엔드 앱 소스, AI 알고리즘·임베딩 배치에는 추가 변경이 없다. 새 브라우저 테스트 수정은 unstaged 상태다.

다음은 Git에서 제외한다. ignore 우회를 위해 `git add -f`를 사용하지 않는다.

- 실제 `.env`, `.env.*` 및 서버 `.env.auth-http-build/` 등 비공개 staging; 공개 `.env.example`만 예외.
- `target/` 전체: 테스트 DB, JAR/classes, 진단 JSON, 로그, 배포 증거/임시 스크립트, 다운로드 도구.
- DB dump/backup, PostgreSQL/Ollama 볼륨 데이터, 임베딩 체크포인트, 관리자 비밀번호·BCrypt 해시·Bootstrap 비밀값, 세션/CSRF 덤프.
- 인증서/개인키, AppData의 CA와 DPAPI 관리자 파일. 이들은 저장소 밖에 있고 추가하지 않는다.
- `frontend/dist/`, `node_modules/`, `frontend/test-results/`, `frontend/playwright-report/`, 브라우저 trace/screenshot, Python cache와 로그.

실제 비공개 환경 파일·JAR·생성물에 ignore가 적용되고, 추적된 `.env`·개인키·인증서·DPAPI·DB 백업·로그·JAR이 없음을 확인했다. 패턴 검사는 비밀 누출의 완전한 증명은 아니며 최종 커밋 diff도 검토한다.

추천 커밋 메시지:

```text
feat(auth): preserve private HTTP login across Jenkins deployments
```

## Push 후 재배포 판단

이번 검증 결과 **준비된 21개 파일을 누락 없이 함께 반영하고, 서버의 기존 `.env` 두 flag 및 현재 override/validator를 보존하는 조건으로 재배포 준비가 가능하다.** 현재 main 그대로 재배포하는 것은 적절하지 않다. Push하면 활성 Jenkins job이 자동으로 배포할 수 있으므로 별도 배포 일정/승인을 먼저 확정한다.

실제 재배포 시에는 같은 HTTP8088에서 로그인·새로고침·CSRF·로그아웃/401·공개 조회를 다시 확인하고, DB/Ollama ID·볼륨·행 수·기존 계정·모델 보존을 재확인해야 한다. DB/계정 재등록과 HTTPS 구축은 필요하지 않다. 이번 작업에서 Commit/Push/Jenkins/재배포를 실행하지 않았다.
