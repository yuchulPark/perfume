# SCENT ARCHIVE 세션 인증

현재 Ubuntu HTTP 배포와 GitHub main의 차이, Jenkins 재배포 조건 및 2026-10-24 재검증은 [Git 동기화 검토](auth-git-sync-review.md)에 정리했다. 아래 과거 배포 안전성 기록의 HTTPS 준비 조건과 당시 미검증 항목은 개인 VM의 명시적인 HTTP 예외 적용 전 기록이다.

추가 실제 로컬 검증 결과와 현재 브라우저 URL/실행 명령은 [로컬 검증 기록](auth-local-verification.md)에 정리했다.
Commit/Push 전 최종 코드·배포 검토와 최신 테스트 결과는 [배포 안전성 검토](auth-deployment-review.md)를 기준으로 한다. 아래 최초 구현 당시의 테스트 수와 프록시 검증 범위는 이 최신 기록과 구분한다.

개인 Ubuntu 개발 VM `192.168.0.19:8088`의 HTTP 로그인 예외와 HTTPS 구축 취소 상태는 [Ubuntu 개발서버 HTTP 인증](auth-ubuntu-development.md)을 참조한다. 기본 Secure 쿠키와 로컬 `auth-dev` 격리 제한은 유지하며, 별도 `auth-dev-http` 프로필·명시적 opt-in·LAN 프록시 보호 조건을 모두 요구한다.

인증은 기본 비활성(`PERFUME_AUTH_ENABLED=false`)이다. 이번 변경은 운영 DB에 적용하지 않았다. 일반 회원가입과 `/signup` API/화면은 제공하지 않으며 최초 관리자 한 명만 별도 Bootstrap 인증을 거친 CLI에서 생성한다.

## 구현 파일

- `src/main/java/com/perfume/auth/`: 보안 필터, 사용자 조회/등록, API, 활성화 검증, 콘솔 Bootstrap.
- `PerfumeApplication.java`: Bootstrap CLI를 일반 Spring/JPA/AI 기동 전에 분기.
- `pom.xml`: Security, Security Test, 테스트 전용 H2 추가. 기존 로컬 WebSocket 의존성 보존.
- `application.properties`: 기본 인증 OFF, JPA `validate`, 세션 쿠키 기본값.
- `application-auth-dev.properties`: 검증된 격리 개발 DB 전용 설정.
- `docs/sql/auth-schema.sql`, `auth-dev-marker.sql`, `compose.auth-dev.yaml`: 수동 인증 DDL와 별도 개발 DB.
- `frontend/src/api/auth.js`, `auth/AuthContext.jsx`, `pages/LoginPage.jsx`, `components/LoginNotice.jsx`: 인증 클라이언트, 로그인 상태, 로그인 화면, 접근 가능한 안내창.
- `App.jsx`, `RecommendPage.jsx`, `components/RecommendationResults.jsx`, `api/recommendations.js`, `lib/recommendationJobs.js`, `styles.css`: 헤더, 버튼 인증, 401/403, CSRF, 질문 복원, 반응형 스타일.
- `compose.yaml`, `.env.example`: 인증 OFF 기본값만 전달. 기존 `.env` 수정 없음.
- `scripts/validate-perfume-compose.py`, `tests/deployment/test_compose_guard.py`: 배포 환경변수 허용 목록에 인증 활성화 플래그와 Secure 쿠키 설정만 추가. Bootstrap 비밀값/개발 profile은 계속 거부.
- `src/test/java/com/perfume/auth/`, `frontend/tests/auth.test.js`, `frontend/tests/browser/auth.spec.js`: 인증/동시 등록/회귀 테스트. 기존 MVC 테스트는 비활성 보안 설정을 명시적으로 import하고 브라우저 fixture는 인증 비활성 응답을 추가했다.

## Spring Security와 DB

`SecurityFilterChain` → `UserDetailsService` → JDBC `auth.app_users` 조회 → BCrypt 검증 → `HttpSession`에 SecurityContext 저장 순서다. JWT나 브라우저 로그인 토큰은 없다. 향후 `USER`/`ADMIN` 역할을 쓸 수 있도록 `ROLE_ADMIN` 권한을 부여한다. 현재 신규 생성은 ADMIN 한 명으로 제한된다.

`auth.app_users`에는 identity `id`, UNIQUE `username`, `password_hash`, `role`, `enabled`, `created_at`, `updated_at`이 있다. `ADMIN`에 대한 PostgreSQL 부분 UNIQUE 인덱스로 동시 최초 등록도 하나만 성공한다. 비활성 계정이 있어도 추가 Bootstrap은 열리지 않는다. 수정 기능은 이번 범위에 없으며 향후 계정 변경 시 `updated_at`도 갱신해야 한다.

인증 테이블은 JPA 엔티티로 등록하지 않는다. 스키마/계정을 앱 시작 때 자동 생성하지 않는다. 기본 `ddl-auto=update`를 `validate`로 바꾸어 기존 카탈로그를 수정하지 않도록 했다. Docker의 기존 `validate`/`sql.init=never` 설정도 유지한다. 인증 OFF에서는 인증 DB 조회가 없고 추천 API를 포함한 기존 동작이 유지된다. OFF 상태는 로그인 보호가 적용된 상태가 아니므로, 운영 준비 후 명시적으로 ON 해야 한다.

ON 시 단일 활성 관리자, BCrypt 형식/비용, 단일 ADMIN 인덱스, 안전한 DDL 설정, 운영 Secure 쿠키를 확인한다. 준비가 누락되면 시작을 거부한다. 기본 비밀번호도 운영에서 거부한다. 강한 비밀번호 정책은 승인된 등록 CLI가 적용한다. SQL로 계정/해시를 수동 삽입하지 않는다.

## 로그인·로그아웃과 React

1. SPA가 `GET /api/auth/me`로 `{enabled,user}`를 확인한다. 인증 ON일 때 `GET /api/auth/csrf`에서 XOR 마스킹된 세션 CSRF 토큰을 받는다.
2. `POST /api/auth/login`은 form URL encoding(`username`, `password`)과 `X-CSRF-TOKEN` 헤더를 사용한다. Spring의 표준 인증 필터가 세션 ID를 변경한다. 응답에는 사용자 ID/역할만 있으며 비밀번호/해시가 없다.
3. 로그인 후 CSRF 토큰을 새로 받고 `/me`를 다시 조회한다. 비밀번호는 입력 DOM에서 읽고 제출 후 지우며 React 상태·스토리지에 보관하지 않는다.
4. `POST /api/auth/logout`도 CSRF가 필요하다. 세션 무효화, SecurityContext 제거, 세션 쿠키 삭제 후 새 CSRF와 `/me`를 조회한다.

쿠키는 `HttpOnly`, `SameSite=Lax`, 운영 `Secure`, cookie-only tracking, 세션 제한 30분이다. 기본 세션 저장소는 서버 메모리이므로 앱 재시작 시 재로그인이 필요하다. 로그인/로그아웃 직후 CSRF를 재발급하는 방식은 [Spring Security 6.5 CSRF 문서](https://docs.spring.io/spring-security/reference/6.5/servlet/exploits/csrf.html)를 따른다.

`/login`은 기존 녹색/아이보리 테마와 모바일 레이아웃을 사용한다. 아이디/비밀번호 검증, 비밀번호 표시 전환, 중복 제출 방지, 오류 안내를 제공한다. 로그인 상태에서 `/login`에 오면 안전한 내부 복귀 경로로 이동한다.

비로그인 방문자는 `/recommend`를 열고 질문/개수를 입력할 수 있다. 추천 버튼은 활성 상태이며 클릭 시 인증을 검사한다. 조회 중/조회 실패 상태에서는 추천을 보내지 않는다. 비로그인 안내창의 확인을 누르면 `/login` → 성공 후 `/recommend`로 돌아온다. 질문/개수는 같은 SPA 탭 메모리에만 유지한다. 로그인 화면에서 새로고침하거나 탭을 닫으면 질문은 사라진다. 질문과 인증정보는 URL이나 localStorage에 넣지 않는다.

추천 생성/상태 조회/취소에서 401을 받으면 폴링을 중단하고 로그인 안내를 연다. 이미 존재하는 Job의 capability는 기존 방식대로 sessionStorage에 유지해 재로그인 후 조회를 재개한다. **이 capability는 로그인 세션 토큰이 아니며 기존 Job 소유권 검증에 계속 필요하다.** 신규 생성이 401로 거절되면 그 생성용 handle은 제거한다. 403은 권한/CSRF 오류로 구분하며 자동 재시도하지 않는다.

## API 경계

| 구분 | 경로/동작 |
|---|---|
| 세션 인증 필수 | `/api/ai/perfumes/recommend` 및 모든 하위 경로. 동기 추천, Job 생성/조회/복구/취소 포함 |
| 공개 카탈로그 | GET `/api/perfumes`, `/api/perfumes/search`, `/api/perfumes/{id}`, `/api/brands` |
| 기존 공개 읽기 검색 | POST `/api/ai/perfumes/search` (읽기 전용이라 이 경로만 CSRF 예외) |
| 공개 인증 API | GET `/api/auth/me`, GET `/api/auth/csrf`, POST `/api/auth/login` (로그인은 CSRF 필수) |
| 로그아웃 | POST `/api/auth/logout` (CSRF 필수) |
| 화면·헬스체크 | SPA 화면, `/healthz`, 기존 backend catalog healthcheck 유지 |
| 그 외 `/api/**` | 인증 ON에서는 거부. 새 API를 만들 때 공개/역할 규칙을 명시적으로 추가 |

인증 없는 추천 요청은 CSRF 누락 여부와 무관하게 JSON 401이다. 인증된 요청의 권한/CSRF 오류는 JSON 403이다. 로그인 실패는 동일한 JSON 401로 처리하고 HTML 302 리다이렉트나 내부 오류를 반환하지 않는다. 기존 추천 Job 토큰, 추천 알고리즘, 임베딩 배치는 수정하지 않았다.

## 최초 관리자 등록 CLI

패키징한 실행 JAR를 **대화형 터미널**에서 사용한다. IDE Run 콘솔, 입력 리다이렉트, Jenkins 로그, 명령행 인자에 비밀번호를 넣는 방식은 지원하지 않는다. 모든 토큰/비밀번호는 숨김 입력한다. 아래의 `target/perfume-0.0.1-SNAPSHOT.jar`는 로컬 `mvn package` 결과물이다.

1. 별도 무작위 Bootstrap 토큰(32자 이상, UTF-8 최대 72바이트)을 준비하고 다음 명령에서 숨김 입력한다. 출력은 salt가 포함된 BCrypt 해시뿐이다.

   ```powershell
   java -jar target/perfume-0.0.1-SNAPSHOT.jar --auth-admin=hash-token
   ```

2. 출력 해시를 `PERFUME_AUTH_BOOTSTRAP_TOKEN_HASH` 환경변수로 설정한다. PowerShell에서는 `$`가 보존되도록 작은따옴표로 감싼다. 원문 토큰과 비밀번호는 환경변수/파일/SQL/소스에 쓰지 않는다. DB 접속 정보는 기존 승인된 비밀 관리 방식으로 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` 환경변수에 주입한다. Bootstrap은 검토된 loopback 접속 또는 `sslmode=verify-full` TLS 접속만 허용한다.
3. **운영 DB 적용 승인을 별도로 받은 후에만** 관리자가 `docs/sql/auth-schema.sql`을 적용하고 다음 CLI를 실행한다.

   ```powershell
   java -jar target/perfume-0.0.1-SNAPSHOT.jar --auth-admin=create
   ```

4. Bootstrap 토큰, 관리자 ID, 관리자 비밀번호, 비밀번호 확인을 차례로 입력한다. 비밀번호는 12자 이상, 영문 대/소문자·숫자·기호 중 3종류 이상, UTF-8 최대 72바이트이며 ID/기본 비밀번호를 포함할 수 없다. 비밀번호는 실행 시 BCrypt로 해싱된다.
5. 계정이 하나라도 존재하면 같은 CLI/다른 ID로 재등록해도 거절한다. 동시 실행도 DB UNIQUE 인덱스로 차단한다. 완료 후 Bootstrap 해시 환경변수도 제거한다. 웹 컨테이너에는 Bootstrap 인증정보를 전달하지 않는다.

계정 복구/비밀번호 변경 CLI와 공개 회원가입은 이번 구현 범위에 없다. 최초 설정 시 비밀번호 관리 도구로 강한 비밀번호를 생성해 보관한다.

## 격리 개발 DB에서 admin/admin 준비

아래는 **운영 DB와 무관한 로컬 환경에서만** 실행할 절차다. 이 작업 중 실제 컨테이너 생성은 실행하지 않았다.

1. Docker Desktop을 시작하고 일회용 `AUTH_DEV_DB_PASSWORD`를 환경변수로 준비한다.

   ```powershell
   docker compose -f compose.auth-dev.yaml up -d
   ```

   별도 프로젝트 `perfume-auth-isolated`, 포트 `127.0.0.1:25432`, DB `perfume_auth_dev`를 사용한다. 데이터 디렉터리는 tmpfs이므로 중지/재생성 시 계정이 사라질 수 있다. 기존 `perfume-dev_pgdata`, Ollama 볼륨은 참조하지 않는다.

2. `DB_URL=jdbc:postgresql://127.0.0.1:25432/perfume_auth_dev`, `DB_USERNAME=perfume_auth_dev`, `DB_PASSWORD`에 위 임시 DB 암호를 환경변수로 설정한다. 기존 `.env`를 복사/덮어쓰거나 SSH 터널을 사용하지 않는다.
3. 앞 절차대로 Bootstrap 해시를 준비하고 아래 명령을 실행한다.

   ```powershell
   java -jar target/perfume-0.0.1-SNAPSHOT.jar --auth-admin=dev-create
   ```

   전용 URL + 실제 DB 이름 + `auth.development_only` 표식이 모두 일치해야 한다. ID는 `admin`으로 고정된다. 숨김 비밀번호 입력과 확인에 각각 `admin`을 직접 입력하면 개발용 계정이 생성된다. 평문 비밀번호/고정 해시는 소스나 SQL seed에 없다. 일반 `create` 경로에서는 이 약한 비밀번호를 허용하지 않는다.

4. 로컬 인증 서버 실행:

   ```powershell
   java -jar target/perfume-0.0.1-SNAPSHOT.jar --spring.profiles.active=auth-dev
   ```

   전용 DB에는 인증 테이블만 있으므로 `ddl-auto=none`으로 카탈로그 DDL을 실행하지 않는다. 이 환경은 로그인 테스트용이다. 카탈로그/실제 추천까지 테스트하려면 이 **격리 DB에 별도로 승인된 합성 카탈로그 fixture**를 준비해야 한다. 기존 운영 카탈로그/임베딩을 연결하지 않는다. 프런트엔드는 기존 Vite `/api` 프록시를 사용한다.

## 검증과 실제 배포 전 준비

MockMvc/JUnit은 H2 메모리 DB에서 BCrypt 로그인, 단일/중복/동시 Bootstrap, 실패 응답, 세션 교체, CSRF, 로그아웃, 공개 API, Job 토큰 전달을 검증한다. H2는 PostgreSQL 부분 인덱스를 완전히 검증하지 않으므로 배포 전 위 격리 PostgreSQL에서 DDL·인덱스·CLI·쿠키를 확인해야 한다. Playwright는 합성 API로 데스크톱/태블릿/모바일의 로그인 복귀, 질문 유지, 만료, 입력 검증, 안내창 포커스를 확인한다.

이번 작업에서는 로컬 PostgreSQL 17 바이너리로 `target/auth-postgres/data`에 **새 클러스터**를 초기화하고 `127.0.0.1:25432`에만 열어 추가 검증했다. 기존 서비스에 접속하기 전에 `SHOW data_directory`의 실제 경로가 이 작업 폴더와 일치하는지 확인했다. `PostgresAuthIntegrationTests`는 `PERFUME_AUTH_TEST_PGDATA`가 지정되고 실제 클러스터 경로·DB 이름·격리 표식이 모두 일치할 때만 실행된다. 실제 SQL/부분 인덱스/동시 등록/단일 개발 계정/BCrypt 인증을 통과했다. 테스트가 검증된 신규 클러스터의 인증 행을 정리하고 개발 계정을 생성한다. 운영 DB/기존 로컬 DB에서는 실행하지 않는다.

임시 JAR 서버(`127.0.0.1:18081`, `auth-dev`)에서도 실제 HTTP로 로그인·`me`·로그아웃, HttpOnly/SameSite 쿠키, 세션 ID 교체, 이전/누락 CSRF의 403, 갱신 토큰, 비로그인 추천 401을 확인했다. 유효 세션/CSRF의 추천 요청은 인증 경계를 통과한 뒤 기존 `RECOMMEND_DISABLED` 503에 도달했다. 실제 AI/카탈로그 데이터가 없는 격리 환경이므로 모델 추론이나 운영 healthcheck 실행을 뜻하지 않는다. 검증 후 임시 앱/DB 서버를 종료했다.

실행 명령은 `mvn test`, `cd frontend; npm test`, `npm run build`, `npm run test:browser`다. 현재 Windows의 Maven wrapper에서 기존 `$HOME/.m2` 경로 판별 오류가 발생하여 검증에는 이미 workspace에 있는 Maven 배포본을 직접 사용했다. 결과와 실제 실행 범위는 최종 작업 보고를 참조한다.

검증 결과:

- 전체 Java 회귀: 1,176건, 실패/오류 0, 기존 외부 환경 조건 15건 생략. 이후 CLI 인증 검증을 추가한 최종 인증 테스트 15건도 모두 통과했고 JAR 패키징을 완료했다.
- 별도 실제 PostgreSQL 통합 테스트 1건 통과. 운영/기존 DB 접속 없음.
- 프런트엔드 단위 테스트 50건, 전체 Playwright 99건(3개 화면 크기), 최종 복귀 경로 수정 후 데스크톱 인증 6건 통과. Vite 빌드 성공.
- Compose 보호 검사 20건 통과. `docker compose config`가 실제 저장소 설정을 검사했으며 컨테이너는 시작하지 않았다.
- Windows JUnit 정리 단계의 임시 폴더 권한 오류는 fork JVM의 `-Djava.io.tmpdir`를 작업 폴더 `target/auth-test-tmp`로 지정해 해결했다. 테스트 동작을 바꾸거나 생략하지 않았다.
- Docker 데몬/실제 컨테이너 healthcheck와 모델 추론은 실행하지 않았다. 기존 healthcheck 경로와 구성은 유지된다.

실제 운영에는 아직 인증 스키마/관리자/HTTPS 쿠키/활성화를 적용하지 않았다. Git commit/push, Jenkins 실행, SSH, 운영 DB 수정, 볼륨 변경도 실행하지 않았다. 배포 전 관리자는 다음을 별도 승인하여 진행한다.

1. 기존 DB/AI 볼륨과 `.env`를 보존한 상태에서 인증 전용 SQL을 검토하고 적용한다. 웹 DB 계정은 인증 테이블 SELECT만 부여하고, INSERT는 Bootstrap 전용 DB 계정에만 부여하는 구성을 권장한다.
2. 위 CLI에서 강한 관리자 비밀번호를 설정하고 Bootstrap 인증정보를 제거한다.
3. HTTPS 경로를 준비한다. 현재 Nginx 8088 설정은 HTTP이므로 그대로 인증을 켜지 않는다. TLS 종료 지점에서 `Secure` 세션 쿠키가 유지되는지 확인한다. Compose는 운영 쿠키 Secure를 true로 고정한다.
4. 기존 `.env`에서 필요한 키만 추가하여 `PERFUME_AUTH_ENABLED=true`를 설정한다. `auth-dev` profile/개발 DB/개발 계정은 배포에 사용하지 않는다.
5. 승인된 Jenkins 배포 이후 기존 `/healthz`와 `/api/perfumes?size=1`, 공개 카탈로그, 실제 브라우저 로그인·추천·취소·만료를 확인한다. Jenkinsfile과 배포 흐름은 유지한다. Compose 검사기의 인증 설정 허용 목록을 확장했고, 최종 검토에서는 배포 셸이 상속된 인증 플래그를 제거해 운영자 관리 `.env`만 사용하도록 보완했다.

## 이후 WebSocket 고객센터에서 재사용

동일 출처의 WebSocket HTTP handshake에서 기존 `HttpSession`의 인증 Principal을 사용할 수 있다. 향후 채팅 구현 시 별도로 handshake 인증과 목적지별 `hasRole('ADMIN')` 권한, STOMP CONNECT CSRF/Origin 검증, 메시지 접근 제어를 추가한다. 현재 `ROLE_ADMIN`과 표준 SecurityContext를 그대로 사용할 수 있다. 이번에는 WebSocket endpoint/채팅 기능을 추가하지 않았다.
