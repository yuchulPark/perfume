# 실제 로컬 인증 검증 결과와 재실행

> 이후 최종 검토에서 Vite 연결 처리를 보완했다. 현재 결과·추가 인증 OFF 검증·실행 절차는 [배포 안전성 검토](auth-deployment-review.md)를 참조한다. 이 문서의 Vite 실패 항목과 PID는 최초 검증 당시의 기록이다.

2026-10-23(KST) 검증. 기존 `docs/authentication.md`와 실제 Security/CLI/React 코드를 확인하고 새 격리 DB를 준비했다. 운영 PostgreSQL, SSH 터널, 기존 DB/볼륨, 기존 `.env`, Git commit/push, Jenkins는 사용하거나 변경하지 않았다.

## 지금 브라우저에서 확인

로컬 검증 서버를 실행한 상태로 두었다. 화면과 API는 동일한 Spring 서버에서 제공한다.

- 사이트: <http://127.0.0.1:8081/>
- AI 추천: <http://127.0.0.1:8081/recommend>
- 로그인: <http://127.0.0.1:8081/login>
- 합성 향수 상세: <http://127.0.0.1:8081/perfumes/1>
- 계정: `admin` / `admin` (아래 새 로컬 DB에만 존재)

1. 시크릿 창으로 사이트를 연다. 합성 향수 2개와 상세 화면이 로그인 없이 보이는지 확인한다.
2. `/recommend`에서 질문을 입력하고 검색 개수를 선택한다. **추천받기**를 누르면 “AI 향수 추천 기능을 이용하려면 로그인이 필요합니다.” 안내창이 열려야 한다.
3. **확인**을 누르면 `/login`으로 이동한다. `admin/admin`으로 로그인하면 `/recommend`로 복귀하고 질문/개수가 유지된다.
4. 헤더의 `admin` 표시를 확인한다. 새로고침해도 로그인 상태가 유지된다. 질문은 SPA 메모리에만 있어 새로고침하면 사라질 수 있다.
5. 새로고침으로 질문이 비었다면 다시 입력한 뒤 추천 버튼을 누르면 **AI 추천이 비활성화되어 있어요**가 표시된다. 개발자 도구 Network에서 추천 POST 응답이 `503 / RECOMMEND_DISABLED`인지 확인한다. 인증을 통과했으며 모델은 실행되지 않았다는 뜻이다.
6. 로그아웃 후 개발자 도구 Console에서 아래 코드를 실행하면 `401`이다.

   ```javascript
   (await fetch('/api/ai/perfumes/recommend/jobs', {cache: 'no-store'})).status
   ```

7. 로그아웃 후에도 목록/상세가 열리고 추천 버튼은 로그인 안내를 다시 표시한다.
8. Application → Cookies에서 `JSESSIONID`의 HttpOnly와 SameSite=Lax를 확인할 수 있다. 이 격리 HTTP 모드의 Secure는 false다. 쿠키/CSRF 값이나 비밀번호를 로그·스크린샷에 복사하지 않는다.

## DB 격리 확인

| 항목 | 검증 값 |
|---|---|
| 물리 저장 경로 | `C:\dev\projects\perfume\target\auth-local-check\data` — 이번 검증에서 새로 initdb |
| PostgreSQL | 로컬 PostgreSQL 17.11 바이너리, Docker 미사용 |
| 수신 주소 | `127.0.0.1:25432`만 수신 |
| DB / DB 사용자 | `perfume_auth_dev` / `perfume_auth_dev` |
| JDBC URL | `jdbc:postgresql://127.0.0.1:25432/perfume_auth_dev` |
| 시스템 식별자 | `7699549961318097988` — SQL과 이 디렉터리의 `pg_controldata` 출력 일치 |
| 격리 표식 | `auth.development_only(id=1, isolated=true)` |
| 관리자 | 1명, `admin`, ADMIN, enabled, 실행 시 생성한 BCrypt 해시 |
| 카탈로그 | `LOCAL AUTH CHECK` 브랜드와 합성 향수 2개만 생성 |

DB 변경 전에 접속 서버의 `SHOW data_directory`, 서버 주소/포트, 시스템 식별자와 로컬 제어 파일을 비교했다. 확인 증거는 `target/auth-local-check/isolation.json`에 있다. 최초 주소 검사에서 PostgreSQL inet의 `/32` 표기가 비교를 막았을 때에는 DB 변경을 진행하지 않았고, `host(inet_server_addr())`와 물리 식별자 일치를 확인한 후 진행했다.

기존 로컬 `perfume`, 이전 검증 클러스터 `target/auth-postgres`, Ubuntu DB, 15432 SSH 전달 포트, Docker 볼륨은 수정하지 않았다. 개발 DB는 새 로컬 클러스터이며 loopback trust 접속을 사용한다. 기존 DB 비밀번호는 사용하지 않는다.

`AuthLocalVerificationSetupTests`는 별도 환경변수 게이트와 물리 경로·DB 이름·표식을 확인하고, 기존 public/ai 테이블이나 관리자가 하나라도 있으면 준비를 거부한다. JPA DDL을 파일로 내보낸 후 이 새 DB에만 적용했다. 관리자도 기존 BCrypt 저장/Bootstrap 검증 코드를 이용해 준비했다. 일반 앱 기동 시 DDL/seed를 실행하는 방식은 추가하지 않았다.

## 이번 검증 결과

| 검사 | 최종 결과 |
|---|---|
| 새 PostgreSQL 격리·계정·합성 카탈로그 준비 | JUnit 1건 통과 |
| 실제 HTTP 인증·공개 API | 20건 통과 |
| 실제 Chrome 데스크톱·모바일 전체 흐름 | 2건 통과, API 응답 모킹 없음 |
| 인증/공개 카탈로그/기본 앱 기동 회귀 | JUnit·MockMvc 24건 통과 |
| 프런트엔드 기존 단위 테스트 | 50건 통과, Vite 빌드 성공 |
| 로그인·로그아웃·새로고침 세션 | 통과 |
| 세션 고정 방어 | 로그인 전후 실제 세션 ID 변경 확인 |
| CSRF | 누락/로그인 전 토큰 403, 로그인/로그아웃 후 갱신 확인 |
| 잘못된 비밀번호·없는 사용자 | 동일한 안전한 401 응답 |
| 비로그인 추천 5개 API | 모두 JSON 401, 리다이렉트 없음 |
| 로그인 후 추천 5개 API | 모두 인증 경계를 통과하여 `RECOMMEND_DISABLED` 503 |
| 로그아웃 후 기존 세션 재사용 | 추천 API 401 |
| 비로그인 향수 목록·상세·브랜드·검색 | 실제 DB/API 및 화면 통과 |
| backend healthcheck 요청 `/api/perfumes?size=1` | 실제 HTTP 200 |

상세 HTTP 결과는 `target/auth-local-check/http-results.json`, 준비 테스트는 `setup-test.log`, 브라우저 캡처는 `frontend/test-results/auth-live/`에 저장된다. 세션 ID·비밀번호·CSRF 값은 보고서에 기록하지 않는다. Playwright trace도 끄고 인증 요청 상태 코드만 기록했다.

### 실패하거나 다른 경로로 검증한 항목

- Windows에서 Vite 5173 포트 시작이 `EACCES`로 실패했다.
- Vite 개발/preview 프록시를 경유한 검증에서는 `ECONNRESET`과 로그아웃 403이 발생했다. IPv4 주소 지정, 별도 계정 실행 등으로 원인을 분리했으나 이 경로의 안정성은 확인하지 못했다. 이를 서버 인증 결함으로 단정하지 않는다.
- 설치된 AdGuard가 페이지에 외부 스크립트 요청을 추가했다. 최종 자동 브라우저 검증에서는 같은 로컬 origin 외의 요청을 차단했다. 시스템의 AdGuard 설정은 변경하지 않았다.
- 최종 검증은 `8081`에서 Spring이 실제 API와 빌드된 React를 함께 제공하는 **테스트 클래스 전용 실행기**로 통과했다. 별도 Vite 서버가 필요 없다. 로그인 화면·인증 필터·DB 조회·CSRF·추천 컨트롤러는 실제 구현을 사용한다.
- 실행기 첫 구성에서는 테스트 클래스패스의 다른 설정이 repository 탐색에 섞여 기동에 실패했다. 운영 컴포넌트만 탐색하고 실제 repository/entity 범위를 지정한 후 15개 repository로 정상 기동했다. 실행기는 `src/test`에만 있어 운영 JAR에는 포함되지 않는다.

### 미검증

- 30분 유휴 세션의 자연 만료를 기다리는 테스트는 하지 않았다. 새로고침 복원·세션 교체·로그아웃 무효화는 실제로 확인했다.
- 실제 Ollama 추론, AI Job 완료/원격 취소는 실행하지 않았다. search/recommend/embedding 기능을 모두 false로 고정했다.
- 운영 HTTPS Secure 쿠키, Nginx/Docker 컨테이너 실행 상태, Ubuntu/Jenkins 배포는 검증하지 않았다.
- Bootstrap CLI의 실제 터미널 숨김 입력은 이번 검증에서 수행하지 않았다. 격리 준비 테스트에서 동일한 Bootstrap 검증/BCrypt 저장 코드를 실행했다.
- 사용자의 일반 브라우저 설정/AdGuard를 포함한 수동 확인은 위 절차로 진행할 수 있다.

## 현재 환경 재실행 명령

이미 실행 중이면 같은 포트로 다시 시작하지 않는다. 아래는 준비된 새 테스트 DB를 재사용하는 명령이며 테이블/계정을 덮어쓰지 않는다.

1. 루트에서 DB가 종료된 경우에만 전용 클러스터를 시작한다.

   ```powershell
   cd C:\dev\projects\perfume
   & 'C:\Program Files\PostgreSQL\17\bin\pg_ctl.exe' `
     -D 'C:\dev\projects\perfume\target\auth-local-check\data' `
     -l 'C:\dev\projects\perfume\target\auth-local-check\restart.log' `
     -o '-h 127.0.0.1 -p 25432' -w start
   ```

2. Spring/React 검증 서버를 시작한다. 스크립트는 현재 자식 프로세스에서 `PERFUME_AUTH_ENABLED=true`, `PERFUME_AUTH_LOCAL_CHECK=true`, 전용 DB URL을 설정하고 종료 시 환경변수를 복원한다. 기존 인증정보나 `.env` 파일은 수정하지 않는다. PowerShell 실행 정책 옵션도 이 프로세스에만 적용된다.

   ```powershell
   powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-auth-local-preview.ps1
   ```

   `auth-dev` 프로파일, loopback 서버, `ddl-auto=validate`, `sql.init=never`, AI OFF를 명시한다. 실행기는 DB 물리 경로와 격리 표식이 맞지 않으면 기동을 거부한다. 별도 Vite 명령은 필요 없다.

3. 자동 검사를 재실행한다.

   ```powershell
   node .\scripts\verify-auth-local.mjs
   cd frontend
   $env:PERFUME_AUTH_LOCAL_CHECK='true'
   npm.cmd exec playwright test -- --config=playwright.auth-local.config.js
   Remove-Item Env:PERFUME_AUTH_LOCAL_CHECK
   ```

4. 직접 터미널에서 시작한 Spring은 Ctrl+C로 종료한다. 검증용 DB만 종료하려면 아래 명령을 사용한다. 데이터/볼륨 삭제 명령은 없다.

   ```powershell
   & 'C:\Program Files\PostgreSQL\17\bin\pg_ctl.exe' `
     -D 'C:\dev\projects\perfume\target\auth-local-check\data' -m fast -w stop
   ```

현재 백그라운드 실행 프로세스는 PostgreSQL PID `94220`, Java PID `94436`이다. 이번 실행에 한해서 Java를 종료하려면 해당 PID가 여전히 이 검증 서버인지 확인한 후 `Stop-Process -Id 94436`을 사용할 수 있다. 재시작 후 PID는 달라진다.

### 빌드 산출물이 없어진 경우

일반 `mvn package`와 `mvn test-compile`, `frontend`의 `npm run build`가 필요하다. 이번 Windows 환경에서는 기존 Maven wrapper 경로 판별 문제 때문에 workspace에 이미 있던 Maven을 사용했다.

```powershell
& '.\target\phase1-validation\apache-maven-3.9.16\bin\mvn.cmd' `
  -o -B -ntp '-Dmaven.repo.local=C:\dev\projects\perfume\target\phase1-validation\repository' `
  -DskipTests package test-compile
cd frontend
npm.cmd run build
```

DB 준비 테스트는 **새 클러스터에 한 번만** 실행하도록 설계됐다. 현재 DB에는 이미 계정과 합성 테이블이 있으므로 다시 실행하면 보호 검사로 거부된다. 새 클러스터 준비가 필요하면 물리 경로/시스템 식별자를 먼저 확인하고 별도로 준비한다. 기존 DB를 초기화하거나 기존 계정을 삭제해 테스트를 통과시키지 않는다.
