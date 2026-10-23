# Ubuntu 개발서버 HTTP 로그인과 HTTPS 취소

2026-10-24의 소스·GitHub main·실행 JAR 비교 및 재배포 준비 결과는 [Git 동기화 검토](auth-git-sync-review.md)를 참조한다. 아래 2026-10-23 적용 기록의 Ollama unhealthy 상태는 당시 결과이며, 최신 읽기 전용 검사에서는 healthy와 기존 모델 두 개를 확인했다.

최종 주소는 **http://192.168.0.19:8088**이다. 사용자의 **HTTP 전환·HTTPS 정리 승인, Windows CA 보존**에 따라 서버 적용을 완료했다. `.env`에 HTTP 허용 키 한 줄을 추가하고 backend만 교체했으며 실제 HTTP 로그인 검증 후 HTTPS gateway·전용 구성·서버와 Windows의 프로젝트 leaf 인증서/키·portable mkcert를 제거했다. **Windows CA 신뢰 등록과 CA 파일은 보존했다.** 아래 변경 명령은 이번 실행 기록이며 재배포·추가 변경은 별도 승인 없이 실행하지 않는다.

## 현재 읽기 전용 확인 결과

2026-10-23 `devops@192.168.0.19:22`, `/srv/perfume`에서 확인했다.

| 항목 | 확인 결과 |
|---|---|
| HTTP 8088 / HTTPS 8443 | HTTP 200; 8443 연결 불가·listener 없음·gateway 제거 |
| 인증 | enabled=true, Secure=false, auth-dev-http 단독 profile |
| 인증 테이블·계정 | 기존 auth.app_users와 활성 ADMIN 1개, 지원 BCrypt 확인; 보존 |
| backend/frontend/DB | healthy; backend만 74a6a2eceaf7로 교체 |
| public/ai 16개 테이블 | 모든 행 수가 HTTPS 작업 전 기준값과 일치 |
| 향수 / 임베딩 | 4,048 / 678행 |
| DB | 컨테이너 8219655794b5, perfume-dev_pgdata 유지 |
| Ollama | 컨테이너 bccad792dd64·perfume-dev_ollama-data 유지; 변경 전부터 unhealthy, 현재 모델 목록 조회 시간 초과 |
| 체크포인트 | 권한 때문에 내부 파일 존재·해시 미확인; 변경하지 않음 |
| VM 상태 | 적용 직전 CPU 99% idle·가용 메모리 약 13GB 확인; SSH 인증/명령 지연은 간헐적으로 재발 |

DB system identifier는 `7694641528401440806`이다. 기존 계정은 이전 CLI로 생성한 강한 비밀번호의 admin/ADMIN이다. 이번에는 SQL DDL이나 Bootstrap CLI를 실행하지 않았다. 전후 계정 내용 digest, 기존 컨테이너/이미지/mount, public/ai 16개 테이블 행 수를 비교했다. 자료는 Git 제외 `target/auth-http-review/`의 `rollout-state.json`, `after-http-migration.log`, `final-preservation-results.json`, `short-final-proof.json`에 저장했다.

이전에는 Secure=true 쿠키 때문에 LAN IP의 HTTP 세션이 유지되지 않았다. 현재는 HTTP 전용 쿠키로 실제 admin 로그인·추천 페이지 복귀·질문 유지·새로고침·로그아웃을 검증했다. 이전 HTTPS 성공 결과와 구분되는 새 HTTP 검증이다.

## HTTP 전용 보호 조건과 한계

기본 `compose.yaml`과 일반 설정은 계속 Secure=true다. 새 `auth-dev-http` 프로필과 `compose.auth-dev-http.yaml`만 개인 VM 예외를 제공한다.

```dotenv
PERFUME_AUTH_ENABLED=true
PERFUME_AUTH_DEV_HTTP_ENABLED=true
```

첫 번째 키는 서버에 이미 있었으므로 두 번째 키만 추가했다. 추가한 한 줄을 제외한 `.env` 원본 바이트·소유자·권한이 그대로임을 확인했다. `.env.example`의 두 플래그 기본값은 false다. 비밀번호나 Bootstrap 토큰은 추가하지 않았다.

- HTTP flag와 `auth-dev-http` 단독 profile, auth.enabled=true를 모두 요구한다. 다른 profile 혼합·flag/profile 단독 설정은 시작을 거부한다.
- Origin/Host `http://192.168.0.19:8088`, DB URL `jdbc:postgresql://db:5432/perfume`로 한정한다. 공용 도메인·다른 DB를 거부한다.
- Secure=false, HttpOnly=true, SameSite=Lax, 쿠키 전용 세션 추적을 검사한다. CSRF·세션 ID 교체·로그아웃 무효화·추천 인증 검사는 유지한다.
- HTTP 전용 쿠키 이름은 `PERFUME_DEV_SESSION`이다. 이전 HTTPS의 Secure `JSESSIONID`와 충돌하지 않으며 로그아웃은 설정된 쿠키 이름을 지운다. 일반 환경의 이름은 유지한다. [Secure 쿠키 덮어쓰기 제한](https://httpwg.org/http-extensions/draft-ietf-httpbis-rfc6265bis.html#name-storage-model).
- 인증·추천 경로에서 사설/loopback 프록시와 클라이언트, 정확한 Host/http/Origin을 검사한다. 기존 Nginx가 덮어쓰는 X-Real-IP/X-Forwarded-Proto만 사용하며 X-Forwarded-For를 클라이언트 IP로 신뢰하지 않는다. backend 호스트 포트는 게시하지 않는다.
- 공개 목록·상세·검색·healthcheck는 유지하고 비로그인 추천은 JSON 401을 반환한다.
- 기존 `auth-dev`의 `127.0.0.1:25432/perfume_auth_dev`·marker 제한을 변경하지 않았다. HTTP VM에서도 기본 비밀번호 거부·단일 관리자·BCrypt 검사를 유지한다.

HTTP는 비밀번호와 세션을 전송 중 암호화하지 않는다. HttpOnly·SameSite·CSRF는 암호화를 대신하지 않는다. 신뢰하는 개인 LAN에서만 사용하고 인터넷 포트포워딩/공개 프록시를 두지 않는다. 사설 IP 검사만으로 NAT 뒤 외부 접근 여부까지 증명하지는 못한다. 다른 공개 서비스에 이 프로필을 재사용하지 않는다. [Spring Boot 쿠키 설정](https://docs.spring.io/spring-boot/3.5/reference/web/servlet.html).

## 로컬 변경과 삭제

- `AuthDevelopmentHttp.java`, `AuthDataConfiguration.java`, `AuthSecurityConfiguration.java`: HTTP 경계와 시작 검증 연결.
- `application.properties`, `application-auth-dev-http.properties`, `.env.example`, `compose.auth-dev-http.yaml`: 기본 OFF와 전용 설정.
- `scripts/validate-perfume-compose.py`, `scripts/jenkins-deploy.sh`: 정확한 HTTP 설정 조합만 허용하고 상속 환경을 제거한다. 서버 `.env`의 명시적 true일 때만 HTTP override를 선택한다. Jenkinsfile·Credentials·서버 Jenkins 설정은 변경하지 않았다.
- `deploy/auth-dev-http/Dockerfile.runtime`: 검증 JAR만 기존 runtime에 복사하는 수동 적용용 파일. 다운로드·DB mount가 없다.
- HTTP 보호·API·실제 Tomcat 세션·Compose·모의 배포 회귀 테스트 추가.
- 로컬 `deploy/auth-https/` 두 파일과 HTTPS 전용 테스트는 제거했다. 기존 HTTP Nginx·기본 Compose·AI 알고리즘·임베딩 배치는 변경하지 않았다.

## 적용한 HTTP 설정과 명령 기록

기존 배포 잠금을 확보하고 파일의 소유·권한·해시를 재확인한 뒤 실행했다. 기존 DB/계정은 변경하지 않았다. 향후 재실행 시에도 새 승인과 상태 확인이 필요하다.

1. 검증 JAR `target/perfume-auth-dev-http.jar`와 runtime Dockerfile을 새 서버 디렉터리 `/srv/perfume/.env.auth-http-build/`에 전송한다. 디렉터리는 0700이며 기존 경로가 있으면 덮어쓰지 않는다. JAR은 `image/app.jar`, Dockerfile은 `Dockerfile.runtime`으로 둔다. build context인 `image/`에는 JAR만 둔다. `compose.auth-dev-http.yaml`과 새 validator도 전송하며 기존 validator는 staging 최상위에 보존한다.
2. 기존 backend 이미지 ID를 기록하고 이 이미지를 기반으로 새 backend 이미지만 빌드한다.

```bash
cd /srv/perfume
set +x
set -euo pipefail
exec 9>.perfume-deploy.lock
flock -n 9
test ! -e .perfume-embedding-active
unset COMPOSE_FILE COMPOSE_PROJECT_NAME COMPOSE_PROFILES
unset DB_URL DB_NAME DB_USERNAME DB_PASSWORD SCENTREV_API_KEY PERFUME_DB_IMAGE
unset PERFUME_AUTH_ENABLED PERFUME_AUTH_COOKIE_SECURE PERFUME_AUTH_DEV_HTTP_ENABLED
unset PERFUME_AUTH_DEV_HTTP_ORIGIN SPRING_PROFILES_ACTIVE
unset PERFUME_AI_SEARCH_ENABLED PERFUME_AI_SEARCH_MODEL_DIGEST
unset PERFUME_AI_RECOMMEND_ENABLED PERFUME_AI_RECOMMEND_MODEL_DIGEST
backend_id="$(docker ps --quiet --filter label=com.docker.compose.project=perfume-dev --filter label=com.docker.compose.service=backend)"
original_backend_image="$(docker inspect --format '{{.Image}}' "$backend_id")"
auth_base_tag=perfume-dev-backend:auth-http-base-9cdf73c482ad
docker tag "$original_backend_image" "$auth_base_tag"
docker build --network=none --pull=false \
  --build-arg "PERFUME_AUTH_BASE_IMAGE=$auth_base_tag" \
  --tag perfume-dev-backend \
  --file .env.auth-http-build/Dockerfile.runtime .env.auth-http-build/image
```

3. 기존 `.env`의 해시/소유자/권한을 확인해 HTTP flag 한 줄만 원자적으로 추가한다. 다른 키·비밀번호·기존 인증 true는 보존한다. 예제 전체로 덮어쓰지 않는다. profile/Origin/Secure는 전용 Compose에 고정되므로 `.env`에 넣지 않는다.
4. 두 Compose 파일을 함께 검증하고 backend만 재생성한다.

```bash
compose_http() {
  docker compose --project-name perfume-dev --project-directory /srv/perfume \
    --env-file /srv/perfume/.env --file /srv/perfume/compose.yaml \
    --file /srv/perfume/compose.auth-dev-http.yaml "$@"
}
compose_http config --quiet
# 기존 DB 비밀번호는 서버 메모리로만 전달하고 출력하지 않는다.
PERFUME_EXPECTED_DB_PASSWORD="$(docker exec "$backend_id" printenv DB_PASSWORD)"
export PERFUME_EXPECTED_DB_PASSWORD
compose_http config --format json | python3 scripts/validate-perfume-compose.py
unset PERFUME_EXPECTED_DB_PASSWORD
compose_http up -d --no-deps --no-build --pull never backend
compose_http ps --all
```

5. HTTP 로그인·세션·CSRF·로그아웃·공개 조회·추천 접근 제어를 검증한다. 실패하면 추가한 HTTP 키를 원복하고 기록한 기존 이미지를 다시 태그해 기본 Compose로 backend만 복구한다. 기존 auth 계정은 그대로 둔다.
6. 위 검증이 통과한 뒤에만 HTTPS gateway와 전용 TLS 파일을 정리한다. DB/Ollama/frontend는 재생성하지 않는다.

runtime 이미지 빌드·실제 서버 교체·HTTP 검증을 완료했다. 전송 JAR SHA-256은 `9cdf73c482ad24744cce47a7c349b4b3d31f21221849560ddc017577af0d4b68`이며 전송 전후 일치를 확인했다. 기존 기본 Compose의 내용은 변경하지 않았다. 현재 운영 명령도 **기본 Compose와 HTTP override 두 파일을 함께** 사용해야 한다. 새 로컬 코드가 원격 저장소에 반영되기 전에 기존 Jenkins가 재배포하면 HTTP 예외가 사라질 수 있다. 이번에는 Commit/Push나 Jenkins 실행을 하지 않았다.

## 제거 완료한 HTTPS 구성

이 TLS 경로를 쓰는 컨테이너는 gateway 하나뿐임을 확인했다. 기존 HTTP frontend에는 TLS mount가 없다. 서버 mkcert 실행 파일은 발견되지 않았고 CA 개인키는 서버로 전송하지 않았다.

| 제거 대상 | 범위 |
|---|---|
| perfume-auth-https-https-1 | project=perfume-auth-https, service=https, 8443만 사용 |
| /srv/perfume/deploy/auth-https/compose.yaml, nginx.conf | 이번 gateway 구성만 |
| /srv/perfume/.env.auth-https | gateway 전용 변수; 기존 .env와 다른 파일 |
| /srv/perfume/.env.auth-tls/server.pem, server-key.pem | 이번 서버 leaf 인증서·개인키만 |
| Windows target/auth-dev-tls/server.pem, server-key.pem | 이번 프로젝트 leaf 인증서·개인키만 |
| Windows target/auth-dev-tools/mkcert.exe | 이번에 받은 프로젝트 전용 portable 도구 |

승인 범위 안에서 label·mount·파일 해시를 재확인한 뒤 위 항목을 모두 제거했다. 서버의 정확한 파일 5개와 Windows 프로젝트 leaf 두 개·portable 도구만 삭제했다. 다른 서비스의 TLS 설정이나 Windows CA는 제거하지 않았다. 다음은 실행한 gateway 제거 명령의 기록이다.

```bash
https_id="$(docker ps --all --quiet --filter label=com.docker.compose.project=perfume-auth-https --filter label=com.docker.compose.service=https)"
[[ "$https_id" =~ ^[0-9a-f]{12,64}$ ]]
docker inspect --format '{{json .Mounts}}' "$https_id"
# 검증된 단일 gateway만 정지·제거; -v와 네트워크 삭제는 없다.
docker stop "$https_id"
docker rm "$https_id"
```

파일은 위 표의 정확한 경로·해시·소유자를 전부 검증한 뒤 하나씩 unlink한다. 심볼릭 링크·예상치 못한 변경을 거부하고 디렉터리는 비어 있을 때만 제거한다. 기존 `.env`, DB/Ollama 볼륨, public/ai/auth 스키마, 관리자, 체크포인트, 기존 Nginx, Jenkins는 보존한다. CA 관련 파일은 아래 별도 절차 외에 삭제하지 않는다.

## Windows CA 보존 확인과 별도 제거 절차

전용 CAROOT는 `%LOCALAPPDATA%\mkcert-perfume-dev`다. CurrentUser\Root의 CA 신뢰 등록, `rootCA.pem`, `rootCA-key.pem`을 보존했다. CA 파일과 관리자 DPAPI 파일의 정리 전후 바이트 해시도 동일하다. 확인한 용도는 프로젝트 leaf 발급이며 같은 지문은 LocalMachine\Root에서 발견되지 않았다. 사용자/시스템 CAROOT 환경변수와 전역 mkcert PATH 등록도 없다. 다른 앱 전체의 재사용 여부까지 증명한 것은 아니므로 향후 제거를 요청하면 재확인한다.

별도 승인 후 해당 CA 신뢰만 제거하는 절차이며 아직 실행하지 않았다. [Microsoft certutil 문서](https://learn.microsoft.com/en-us/windows-server/administration/windows-commands/certutil#-delstore).

```powershell
$devCaThumbprint = 'DFD1BEC761CE5575058BD1DBC49F7A3614C76295'
Get-Item -LiteralPath "Cert:\CurrentUser\Root\$devCaThumbprint"
# 별도 CA 제거 승인 및 프로젝트 전용 사용 재확인 후에만 실행:
certutil.exe -user -delstore Root $devCaThumbprint
```

`rootCA.pem`과 `rootCA-key.pem` 파일 삭제도 별도 승인 후 전용 경로에서만 수행한다. 다른 CA·공용 mkcert 디렉터리·전체 신뢰 저장소를 일괄 삭제하지 않는다.

## 계정과 실제 브라우저 확인

기존 admin/ADMIN을 유지한다. 비밀번호는 같은 Windows 계정에서 DPAPI 파일로 확인한다. 파일명의 8443은 과거 이름이며 인증서가 아니므로 HTTPS 정리 때 삭제하지 않는다.

```powershell
$cred = Import-Clixml "$env:LOCALAPPDATA\PerfumeDev\admin-192.168.0.19-8443.clixml"
$cred.GetNetworkCredential().Password
```

사용자는 `http://192.168.0.19:8088`에서 다음 순서로 재확인할 수 있다. 1~3·5 및 4의 인증 경계는 실제 Chrome에서 검증했으며, 4의 모델 추론·취소는 미검증이다.

1. 목록·검색·상세 공개 조회 → `/recommend` 질문 입력 → 추천받기 → 안내 확인 → `/login` 이동.
2. admin 로그인 → 추천 페이지 복귀·질문 유지 → 새로고침 후 로그인 유지.
3. 쿠키 Secure 미지정·HttpOnly·SameSite=Lax, 누락/이전 CSRF 403 확인.
4. 로그인한 추천 API의 인증 경계 통과를 추론 없이 확인. 실제 추천 완료·Job 취소는 VM/Ollama가 안정된 뒤 별도 검증.
5. 로그아웃 200 → 추천 API 401, 공개 조회 유지.
6. 8443 접속 불가·gateway 부재, 기존 네 서비스 health, DB/Ollama ID·볼륨·행 수·모델을 재비교.

## 이번 검증 결과

| 검증 | 결과 |
|---|---|
| 전체 Java | 1,193건: 1,176 통과, 실패/오류 0, 외부·실DB·수동 gate 17 생략 |
| 로컬 실제 HTTP/Tomcat | H2 계정 fixture로 쿠키·로그인·세션 회전·CSRF·로그아웃 후 401 통과 |
| HTTP 경계 | 공개 Host/Origin/IP·profile 혼합·기본 비밀번호 거부 통과 |
| frontend 단위 | 50건 통과 |
| Compose offline 검사 | 29건 통과 |
| Jenkins 모의 배포 | 10건 통과; 실제 Jenkins/Docker/Git 실행 없이 검증 |
| 실제 HTTP Chrome | 11개 검사 그룹 모두 통과; 공개 화면·로그인·질문/개수 유지·세션·CSRF·로그아웃·모바일 안내 |
| 실행 JAR | target/perfume-auth-dev-http.jar 패키징·전송·기존 runtime 기반 적용 성공 |
| 인증된 추천 접근 | 유효 세션/CSRF의 잘못된 입력은 400 INVALID_REQUEST, Job lookup은 404 RECOMMEND_JOB_NOT_FOUND; 인증 경계 통과, 추론 없음 |
| HTTP 보호·쿠키 마이그레이션 | 잘못된 Host/Origin 403; 기존 Secure JSESSIONID와 새 HTTP 쿠키 공존·로그인 성공 |
| 로그아웃 | HTTP 200; 기존 인증 세션 재사용은 user=null, 추천 API는 401 |
| HTTPS gateway·TLS/Windows leaf 파일 제거 | 완료; 서버 파일 부재·gateway 부재·8443 listener 없음·클라이언트 연결 불가 확인 |
| 데이터·설정 보존 | 16개 테이블 행 수·관리자 내용·다른 컨테이너/볼륨·기본 Compose 동일; .env는 HTTP 키 한 줄만 추가 |
| 실제 Ollama 추천·취소 | 변경 전부터 Ollama unhealthy/모델 조회 시간 초과이므로 미실행·미검증 |
| Windows CA | 요청대로 신뢰 등록·CA 파일 보존; 제거 명령은 실행하지 않음 |

초기 HTTP fixture의 Spring 설정 탐색 오류를 수정하고 전체 테스트를 통과했다. 기존 로컬 preview가 참조하는 기본 JAR의 repackage 이름 변경은 실패해, 동일 코드·의존성을 별도 finalName으로 패키징했다. 패키징 재시도만 테스트를 생략했고 기존 preview JVM은 종료하지 않았다.

SSH 인용 오류로 생긴 진단 파일 하나는 Docker 컨테이너 이름 출력과 내용·소유자·시각이 일치함을 확인해 그 파일만 제거했다. 서버 설정·DB는 변경하지 않았다.

최종 진단 중 간헐적 SSH 인증/명령 시간 초과가 있었다. 최초 루프백 Host의 `/api/auth/me` 403은 새 Host 보호 정책에 따른 응답이므로 진단 URL을 지정된 LAN 주소로 수정했다. 불응답 Ollama는 진단에서 건너뛰도록 했으며 별도의 짧은 읽기 검증으로 `.env` 나머지 바이트·기본 Compose·전송 JAR·컨테이너 ID·TLS 파일/포트 제거를 확인했다. SSH/모델 조회 실패를 정상으로 표시하지 않는다. Ollama·VM 재시작은 수행하지 않았다.

Git 제외 대상은 실제 `.env*`, target의 JAR/로그/임시 데이터, AppData의 CA·관리자 DPAPI 파일이다. 승인된 backend 교체와 단일 gateway 제거 외에 다른 서비스는 재시작하지 않았다. Commit/Push·Jenkins 실행·DB 스키마/계정 변경·Windows CA 제거는 수행하지 않았다.
