# 인증 최종 코드 검토와 배포 안전성

2026-10-23 KST. Commit/Push, Jenkins 실행, Ubuntu/SSH/운영 DB 연결, Docker 볼륨 작업, 실제 Ollama 추론은 하지 않았다. 기존 `.env`와 인증정보를 읽어 복사하거나 덮어쓰지 않았다. AI 추천 알고리즘과 임베딩 배치는 변경하지 않았다.

## 이번 검토에서 수정한 내용

### Vite 프록시 연결 처리

기존 Vite 8.3.4/Node 24.21 설정에서 백엔드 직접 호출은 정상인데 프록시 응답이 `ECONNRESET`으로 끊어졌다. 같은 `/api/auth/me`를 100회 호출한 비교에서 기본 설정은 70회 성공/30회 실패했고, 백엔드 응답에는 모두 `Connection: close`가 있었다. 이 헤더를 다음 연결에 그대로 전달하지 않은 비교에서는 150회 모두 성공했다.

응답 헤더만 제거한 중간 수정에서는 모바일 반복 요청 검사 한 건이 30초 타임아웃으로 실패했다. 최종 설정은 loopback IPv4 대상, 명시적인 HTTP keep-alive Agent, 백엔드 요청의 `Connection: keep-alive`, 백엔드 응답의 `Connection` 제거를 함께 적용한다. 브라우저 연결과 백엔드 연결의 수명을 각각 관리한다. [RFC 9110 §7.6.1](https://www.rfc-editor.org/rfc/rfc9110.html#section-7.6.1)의 연결별 헤더 의미를 따른다. 로그인·추천 POST를 자동 재시도하는 방식은 추가하지 않았다.

최종 실제 Spring 대상 데스크톱/모바일 반복 요청 150회씩과 로그인 시나리오가 통과했다. 탐색 과정에서 작성한 Node 임시 서버도 `Connection: close`일 때 직접 호출부터 끊기는 현상이 있어, 그것을 정상 애플리케이션 회귀 테스트로 취급하지 않았다. 운영체제/로컬 HTTP 필터의 내부 원인까지 규명했다는 뜻은 아니다. 실제 검증 서버를 대상으로 재현·개선 여부를 확인했다.

`frontend/vite.config.js`의 동일 설정을 dev/preview에 사용한다. 테스트는 `frontend/tests/local/auth-live.spec.js`에 실제 서버 반복 요청 검사를 추가했다. API 응답을 mock하지 않으며 외부 AdGuard 주입 주소 등의 요청만 차단한다.

### 5173 EACCES와 로그아웃 403

- 이번 검사에서는 5173/4173 loopback bind가 성공했고 실제 Vite 5173도 기동했다. IPv4/IPv6 제외 포트 목록에 5173은 없었다. 이전 EACCES의 발생 원인은 확정하지 못했다. 포트 예약/방화벽/시스템 설정을 변경하지 않았다. 재발 시 Vite 종료 로그와 `netsh interface ipv4 show excludedportrange protocol=tcp`, 포트 소유자를 먼저 확인하고 아래 8081 동일 출처 실행을 사용할 수 있다. 필요하면 `npm.cmd run dev -- --port 5174`로 빈 로컬 포트를 지정한다.
- 현재 유효한 새 CSRF와 세션을 사용한 실제 로그아웃은 200이고 이후 추천 요청은 401이다. CSRF 누락·로그인 전 토큰은 403이 맞다. 이전 프록시 로그아웃 403의 토큰/세션 상태를 확정할 기록은 없어 원인을 단정하지 않았다. 보안 검사를 해제하거나 403을 성공으로 처리하지 않았다.

### 배포 환경변수 상속

`scripts/jenkins-deploy.sh`는 기존 AI 플래그를 정리하지만 새 인증 플래그는 정리하지 않았다. Compose에서는 셸 환경변수가 `--env-file`보다 우선하므로 SSH 실행 환경에 남은 `PERFUME_AUTH_ENABLED=true`가 운영자의 의도와 다르게 인증을 켤 수 있었다. 인증 플래그 두 개도 `unset`하고 서버의 운영자 관리 `.env`만 기준으로 삼도록 수정했다.

`test_jenkins_db_preservation.py`는 상속된 인증 ON/쿠키 false를 주입한 상태에서 두 플래그가 제거되는지, 인증 OFF/ON 구성 모두 기존 DB ID·볼륨·`.env`를 보존하고 backend/frontend만 교체하는지 검사한다. Docker/Git/SSH 등 외부 실행은 대체 명령으로 mock한다. 실제 Jenkins나 배포를 실행한 검사가 아니다.

Python 캐시가 Git 후보에 나타나지 않도록 `.gitignore`에 `__pycache__/`, `*.py[cod]`를 추가했다. 프런트엔드 Docker context에서도 로컬 브라우저 테스트를 제외했다.

## 인증 OFF와 ON의 결과

| 모드 | 확인한 동작 | 검증 범위 |
|---|---|---|
| OFF | `/me`는 enabled=false/user=null, `/csrf`는 토큰 없음, 세션 쿠키 생성 없음 | 실제 HTTP + 새 `AuthDisabledApiTests` |
| OFF | 비로그인 목록·상세·브랜드·검색 200, 기존 healthcheck 카탈로그 경로 200 | 실제 합성 PostgreSQL + 전체 MVC 회귀 |
| OFF | React 추천 클릭 시 안내창/로그인 이동 없이 추천 POST 전달, CSRF 불필요 | 실제 Chrome desktop/mobile + 기존 성공 추천 mock 회귀 |
| OFF | 동기 추천/Job/취소/의미 검색이 인증 필터를 통과 | 실제 서버는 기존 AI OFF 오류 503, mock 서비스는 기존 성공 DTO와 Job capability 처리 검증 |
| ON | 비로그인 추천 및 하위 경로가 CSRF 유무와 관계없이 JSON 401 | 전체 MockMvc + 실제 HTTP |
| ON | admin 로그인, 세션 ID 교체, 새로고침 복원, 질문/개수 유지 후 추천 화면 복귀 | 격리 DB + 실제 Chrome |
| ON | 정상 세션/CSRF 추천 요청은 인증 경계를 통과 | 실제 응답은 `503 RECOMMEND_DISABLED`; 추론 성공을 뜻하지 않음 |
| ON | 로그인 실패 401, 잘못된 CSRF 403, 정상 로그아웃 200, 이후 추천 401 | 실제 HTTP/Chrome + MockMvc |

공개 의미 검색 POST `/api/ai/perfumes/search`는 기존 읽기 전용 API라 ON에서도 CSRF 예외다. 나머지 새 API는 ON의 공개/보호 목록을 명시적으로 검토해야 한다. 기존 기본 JPA `update` → `validate` 변경 때문에 **스키마가 없는 새 일반 개발 DB는 자동 생성되지 않는다**. 이미 스키마가 준비된 카탈로그 기능은 회귀 검사했고, 운영 Docker는 변경 전부터 `validate`였다.

## DB 격리 근거와 데이터 보존

이전 작업에서 새로 만든 로컬 클러스터를 그대로 사용했다. 이번 최종 검토에서는 테이블/관리자를 만들거나 삭제·갱신하지 않았다.

| 항목 | 이번 읽기 전용 확인 값 |
|---|---|
| DB URL | `jdbc:postgresql://127.0.0.1:25432/perfume_auth_dev` |
| 실제 서버 IP/포트/DB | `127.0.0.1` / `25432` / `perfume_auth_dev` |
| `SHOW data_directory` | `C:/dev/projects/perfume/target/auth-local-check/data` |
| SQL `pg_control_system()` 시스템 식별자 | `7699549961318097988` |
| 로컬 `pg_controldata` 시스템 식별자 | 같은 값 |
| 격리 표식 | `auth.development_only.id=1`, isolated=true |
| 합성 데이터 | 활성 admin 1개, 테스트 향수 2개 (`local-auth-check-*`) |

전용 PG 17 프로세스가 위 물리 디렉터리와 `-h 127.0.0.1 -p 25432`로 실행된다. 운영 Compose의 `db:5432/perfume`, 호스트 포트 15432, Docker `perfume-dev_pgdata`와 별개다. 현재 25432는 Docker가 아니라 로컬 PostgreSQL 프로세스다. 별도 대안인 `compose.auth-dev.yaml`도 독립 프로젝트/tmpfs를 사용하며 운영 Compose와 합치지 않는다. 이번에는 어느 Compose 컨테이너도 시작하지 않았다.

검증 실행기는 정확한 DB URL, 실제 물리 경로, DB 이름, 격리 표식, 인증 스키마를 확인한 후 기동한다. JPA validate, SQL init never, importer/search/recommend/embedding OFF를 명시한다. 기존 운영 DB·15432·SSH 터널에 대한 연결 명령은 실행하지 않았다. 따라서 이 검증 경로가 운영 데이터를 변경하지 않았다는 근거가 있다. 운영 서버에 접속하지 않았으므로 운영 데이터의 사전/사후 checksum 비교나 다른 작업자의 변경 유무까지 확인한 것은 아니다.

OFF 비교 실행기 역시 안전을 위해 이 fixture의 인증 스키마를 사전 확인하지만, 일반 운영 OFF 애플리케이션은 인증 스키마나 AuthUserStore를 요구하지 않는다. 이는 별도 활성화 가드 회귀에서도 확인했다.

인증 테이블은 JPA 엔티티가 아니며 앱 기동 시 DDL/계정 seed가 없다. `auth-dev`는 전용 URL/DB/표식 없이는 거부되고, 운영 모드는 Secure 쿠키·단일 관리자·BCrypt·기본 비밀번호 금지를 확인한다. `admin/admin`은 격리 fixture에만 있다. 개발 설정 파일이 JAR에 있어도 profile은 자동 활성화되지 않는다.

## Nginx·Docker·Jenkins 검토

- Nginx `/api` location이 원래 경로/쿼리·Cookie·CSRF 헤더·Set-Cookie와 API 상태 코드를 전달한다. 로그인/로그아웃 경로 재작성이나 401 HTML 오류 치환, 인증 응답 캐시는 설정하지 않았다. SPA `/login`, `/recommend` 새로고침은 index fallback으로 처리한다.
- 추천 동기 API 450초, 나머지 API 30초 timeout은 기존 값이다. 로그인/로그아웃은 `/api/auth/*`로 그대로 backend:8081에 도달하도록 되어 있다. Nginx의 연결 관리와 이번 Vite 전용 설정은 별개다.
- 운영 Compose는 인증 기본 false, Secure true, 개발 profile/DB/Bootstrap 비밀값 없음이다. 기존 DB/Ollama 볼륨명·연결·DB 라이프사이클은 유지된다. 검증기는 개발 profile/추가 환경변수/비밀값/위험한 command override를 계속 거부한다.
- backend healthcheck `/api/perfumes?size=1`과 frontend `/healthz`는 인증 없이 가능하다. 합성 DB의 backend 경로 200은 확인했지만 **실제 컨테이너 healthcheck 성공은 미검증**이다.
- 현재 Nginx는 HTTP 8088만 제공한다. 인증 ON은 외부 HTTPS 준비 후 적용한다. 동일 공개 출처에서 SPA/API를 제공하고 브라우저의 Secure/HttpOnly/SameSite=Lax 쿠키와 새 CSRF를 확인해야 한다.
- 외부 TLS 프록시 뒤에서 현재 내부 Nginx의 `$scheme`은 http다. 내부 Nginx는 X-Forwarded-Proto를 이 값으로 덮어쓴다. 현재 앱은 Secure 쿠키를 true로 명시하고 로그인 API가 JSON을 반환해 이 헤더로 쿠키 보안을 추론하지 않는다. HTTPS 절대 URL/리다이렉트가 필요한 구성을 추가하면 신뢰할 프록시 경계와 forwarded-header 정책을 별도로 설계·검증해야 한다. 클라이언트의 임의 X-Forwarded-Proto를 그대로 신뢰하도록 바꾸지 않았다.
- Docker API 접속은 로컬 daemon pipe 부재로 실패했고 별도 nginx 실행 파일도 없다. 실제 Nginx 문법 검사/이미지 빌드/컨테이너/HTTPS/Ubuntu/Jenkins 실행을 통과로 표시하지 않는다.

## 최종 테스트 결과

상세 실행 로그/결과/실패 진단은 Git에서 제외되는 `target/auth-review/`, `frontend/test-results/`에 있다.

| 검사 | 결과 |
|---|---|
| Maven 전체 | 1,181건 중 실행 1,164 성공, 17 생략, 실패/오류 0 |
| JAR 패키징 | 실행 중 JAR의 Windows 파일 잠금으로 최초 실패; 해당 검증 Java만 종료 후 패키징 성공 |
| 프런트엔드 Node | 50 성공, 실패/생략 0 |
| Vite production build | 성공 |
| 전체 Playwright mock 회귀 | 99 성공, 실패/생략 0; desktop/tablet/mobile |
| 실제 ON Chrome, Vite 5173 | 4 성공: 150회 반복 × 2 화면 + 로그인/로그아웃 × 2 |
| 실제 ON Chrome, preview 4173 / 직접 8081 | 각각 4 성공; 같은 반복·로그인 시나리오 |
| 실제 OFF Chrome, 18081 | desktop/mobile 2 성공 |
| 실제 ON HTTP | 8081 직접 20, 5173 dev 20, 4173 preview 20 성공 |
| 실제 OFF HTTP | 18081에서 11 성공 |
| Python 배포 보호 검사 | 83 성공, 실패/생략 0; 실제 Compose config 검사 및 외부 실행 mock |
| Python 평가/진단 회귀 | 108 + 12 성공 |
| `git diff --check` | 성공 |
| 런타임 JAR 검사 | 인증 구현 포함, 로컬 실행기/테스트 클래스/H2/Security Test/로그/.env 제외 확인 |

17개 생략은 인증용 PostgreSQL 초기화/통합 2건과 외부 ScentRev/실제 DB를 요구하는 기존 15건이다. 기존 fixture를 지우고 재등록하는 테스트를 현재 DB에 다시 실행하지 않았다. 초기 PostgreSQL 통합 테스트의 이전 통과 이력과 이번 실제 HTTP 읽기/로그인 검증을 구분한다. 실제 AI 추론·임베딩·외부 공급자 import는 실행하지 않았다.

실패/한계: 수정 전 프록시 reset, 중간 수정의 반복 요청 timeout, 실행 JAR 파일 잠금은 위에 기록했다. 5173 EACCES와 과거 유효하다고 가정한 로그아웃 403의 정확한 발생 원인은 미확정이다. 자연적인 30분 세션 만료 대기, 다중 인스턴스 세션 공유, 실제 운영 스키마/HTTPS/Nginx/Docker 배포는 미검증이다.

## Git 후보와 제외할 파일

추적 변경 파일 + untracked 중 ignore 제외 파일을 검사했다. 테스트 DB/로그/개인정보/실제 키·비밀번호·고정 BCrypt 해시는 발견하지 않았다. 자동 패턴 점검의 이메일 형태 한 건은 기존 Git remote 허용 목록의 `git@github.com` 표기였다. 테스트의 합성 capability/CSRF 문자열, 비밀번호 정책 검사용 문자열과 공개 `admin/admin` 안내는 운영 비밀값이 아니다.

다음은 Git에 추가하지 않는다. `git add -f`로 ignore를 우회하지 않는다.

- `target/` 전체: `auth-local-check/data/`, `auth-postgres/data/`, DB 식별 증거/합성 SQL/로그/결과, Maven cache/JAR/classes.
- `.env`, `.env.*` 운영/로컬 값, `application-local.properties`, `application-secret.properties`; 공개 `.env.example`만 예외.
- DB dump/backup, Bootstrap 원문 토큰·해시, 관리자 비밀번호/BCrypt 해시, 인증 쿠키·CSRF 덤프.
- `frontend/test-results/`, `frontend/playwright-report/`, screenshot/trace, `frontend/dist/`, `node_modules/`, `__pycache__/`, `*.pyc`, `*.log`.

위 경로는 ignore 적용을 확인했고 이미 추적된 해당 산출물도 없다. 테스트 source/config, 수동 auth DDL, 비밀값 없는 문서는 검토 대상 소스다.

## 운영 배포 전 필수 작업과 판단

1. 승인된 관리 절차에서 기존 DB를 보존하고 `docs/sql/auth-schema.sql`만 수동 적용한다. app 계정의 auth schema USAGE/table SELECT, Bootstrap 계정의 필요한 INSERT/sequence 권한을 준비한다. 운영 계정에 개발 marker나 합성 데이터를 넣지 않는다.
2. 대화형 Bootstrap CLI로 강한 관리자 비밀번호를 등록한다. 12자 이상/3종류/72 UTF-8 bytes 이하 정책을 따르고 `admin/admin`은 금지한다. 일회성 토큰과 해시는 등록 후 제거하며 웹 컨테이너/Jenkins 로그/명령 인자에 넣지 않는다. 실제 CLI 숨김 입력은 이번에 직접 검증하지 않았다.
3. 공개 HTTPS와 동일 출처 API 프록시를 준비한다. 신뢰할 TLS/forwarding 경계를 정하고 실제 브라우저의 Secure 쿠키·세션 교체·새 CSRF·로그아웃과 401/403을 확인한다. 운영에서 cookie secure=false 또는 auth-dev profile을 쓰지 않는다.
4. 운영자 관리 기존 `.env`에 필요한 플래그만 반영한다. 준비 전 false를 유지하고 준비 완료 후 `PERFUME_AUTH_ENABLED=true`; Compose Secure=true는 유지한다. 기존 값 전체 덮어쓰기/개발 `.env` 복사 금지.
5. 승인된 별도 검증에서 Docker build의 테스트/`nginx -t`, 실제 healthcheck·공개 API·로그인/로그아웃·자연 만료를 확인한다. 다중 backend를 쓰려면 현재 메모리 세션에 맞는 세션 공유/라우팅 설계가 필요하다.

코드의 로컬 Commit 검토는 가능하지만 운영 배포와 동일한 승인으로 보지 않는다. 특히 `Jenkinsfile`은 main을 `pollSCM('H/5 * * * *')`로 감시하므로 **운영 연결 main Push는 위 준비/실제 환경 검증과 배포 승인이 끝날 때까지 보류**한다. 이번 작업에서 Commit/Push/Jenkins는 실행하지 않았다.

## 브라우저에서 다시 확인하는 방법

기존 전용 PostgreSQL이 실행 중이고 JAR/test classes/React dist가 준비된 상태에서 사용한다. DB가 꺼졌으면 [최초 로컬 기록의 전용 pg_ctl 명령](auth-local-verification.md#현재-환경-재실행-명령)으로 **같은 물리 클러스터만** 시작한다. 준비 테스트를 다시 실행하거나 DB/계정을 초기화하지 않는다. 재패키징할 때는 실행 중인 이 검증 Java를 먼저 종료해야 Windows JAR 잠금을 피할 수 있다.

```powershell
cd C:\dev\projects\perfume
# 인증 ON: 별도 터미널. 현재 실행 중이면 중복 기동하지 않는다.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-auth-local-preview.ps1
# 인증 OFF 비교: 또 다른 터미널
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-auth-local-preview.ps1 -AuthEnabled false -Port 18081
# Vite dev: frontend 터미널
cd frontend
npm.cmd run dev
```

- ON: <http://127.0.0.1:5173/recommend> 또는 <http://127.0.0.1:8081/recommend>. 시크릿 창에서 추천 클릭 → 안내 → 로그인 admin/admin → 추천 화면/질문 복귀 → 추천 클릭 시 503 RECOMMEND_DISABLED → 새로고침 로그인 유지 → 로그아웃 200 → 추천 API 401.
- OFF: 별도 시크릿 창에서 <http://127.0.0.1:18081/>의 목록/상세/검색, <http://127.0.0.1:18081/recommend>의 추천 클릭을 확인한다. 안내/로그인 이동 없이 503 RECOMMEND_DISABLED가 표시된다. 실제 AI는 실행되지 않는다.
- 4173 preview: dev와 별개로 `npm.cmd run preview`. <http://127.0.0.1:4173/recommend>에서 동일 ON 절차를 확인한다. 자동 mock 브라우저 테스트가 이 포트를 사용하는 동안에는 중복 시작하지 않는다.
- 쿠키는 포트별로 격리되지 않으므로 ON/OFF 비교는 별도 브라우저 프로필/시크릿 창으로 진행한다.

```powershell
# 루트: 실제 API 검사, AI 실행 안 함
node .\scripts\verify-auth-local.mjs
node .\scripts\verify-auth-local.mjs --via-vite
node .\scripts\verify-auth-local.mjs --via-proxy
# frontend: 실행 중인 로컬 서버를 골라 실제 Chrome 검사
$env:PERFUME_AUTH_LOCAL_CHECK='true'
$env:PERFUME_AUTH_LOCAL_ORIGIN='http://127.0.0.1:5173' # 또는 8081/4173; OFF는 18081
npm.cmd exec playwright test -- --config=playwright.auth-local.config.js
Remove-Item Env:PERFUME_AUTH_LOCAL_CHECK, Env:PERFUME_AUTH_LOCAL_ORIGIN
```
