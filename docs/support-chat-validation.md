# 고객센터 구현·검증 기록

기준: 2026-10-24, 기존 HEAD `5025e8360f37e49860c3bfaf11c34a5433562bb4`. Commit/Push, Jenkins 실행, Ubuntu 접속·재배포, 기존 DB/관리자/.env/볼륨 변경은 수행하지 않았다. 기능·보안·수동 SQL 적용 절차는 [support-chat.md](support-chat.md)에 설명한다.

## 최종 검증

| 검사 | 결과 | 범위·한계 |
|---|---|---|
| Java 전체 | 1,211개, 실패/오류 0, 기존 조건부 생략 17개, 2분 30초 | 동일 소스/의존성, IDE 공유 출력 대신 `target/support-validation` 사용 |
| PostgreSQL/지원 기능 | 18개, 실패/오류/생략 0, 35.834초 | HTTP/STOMP 통합 4개는 실제 PostgreSQL 17.11, 저장소 7개는 독립 H2, 보안 7개는 단위 검사 |
| React/Node | 58개 전부 통과, 생략 0, 약 2.3초 | 기존 50개 + 신규 8개, 연결/재연결·정리·중복 제거·HTTP UUID·안전한 텍스트 |
| 기존 Playwright | 99개 전부 통과, 3.9분 | PC/태블릿/모바일, 독립 Context, 3 worker; 로그인·카탈로그·추천 Job/취소는 모의 API |
| 고객센터 Playwright | 4개 전부 통과, 1.7분 | PC/모바일 × 실시간 상담/경합·IME. 실제 PostgreSQL, 실제 STOMP, 방문자 A/B/관리자 각각 독립 Context |
| 배포 보호 | 94개 전부 통과, 실패/오류/생략 0, 358.812초 | 기존 91개 + 신규 3개. 인증 ON/OFF 모두 상속 support 플래그 차단, DB/볼륨/.env/Healthcheck 보존 검사 유지 |
| React production build | 성공 | 고정 STOMP 의존성 및 CSS/라우팅 포함 |
| Java JAR package | 성공, 11.833초 | 패키징 단계는 앞서 완료한 검사를 중복 실행하지 않도록 skipTests 사용 |
| Runtime JAR 내용 | 확인 | support 클래스 25개/메시징 의존성 포함, 테스트 서버·테스트 계정 fixture·암호화 자격증명·수동 SQL 제외 |
| PowerShell helper | 파서 및 실제 DB 정지/재시작/물리 검증 통과 | 기존 클러스터와 별개인 26432만 사용, 데이터 삭제 없음 |
| Git/비밀 파일 | 검사 | 실제 `.env`/개인키/인증서/DB/로그 변경 파일 없음, 고정 BCrypt·개인키 문자열 없음, staged 파일 없음 |

생략 17개는 기존 외부 서비스·DB 등에 대한 환경변수 게이트를 유지한 결과다. 통과로 합산하지 않았다. JUnit 전체 통과 수는 생략을 제외하면 1,194개다. 위 행의 PostgreSQL 18개는 Java 전체와 별도로 실행한 중복 검증이며 전체 테스트 수에 다시 더하지 않는다.

## 확인한 시나리오

- 익명 상담방 생성·같은 세션 복구, 세션당 동일 방, 다른 방문자의 방 조회/전송 404.
- 방문자의 관리자 REST/개인별 관리자 채널 접근 거부, 허용 목록 밖 destination 차단, payload 발신자/역할 사칭 차단.
- CONNECT CSRF 누락 거부, 외부 Origin 거부, REST CSRF 누락 거부, 기존 로그인·세션 ID 교체·로그아웃 보존.
- PostgreSQL 커밋 후 A↔관리자 실시간 전달, B 대화 분리, 관리자 목록/미확인 수/방 전환/종료.
- 중복 UUID 재전송, 병렬 중복 저장 방지, 단조 sequence/페이지 조회/읽음 위치, 종료 후 신규 메시지 거부와 기존 ACK 복구.
- 길이·빈 메시지·제어 문자·빈도 검사, 모호한 전송 실패를 성공으로 표시하지 않는 상태 관리.
- WebSocket 단절 상태 입력 차단, 자동 재연결, 오프라인 중 저장된 답변의 REST 복구, 닫기/새로고침 후 대화 복구, 중복 표시 없음.
- 오래된 빈 `current` 응답이 새로 생성한 상담방을 지우지 않음. IME 조합 중 Enter 보호와 Shift+Enter 줄바꿈.
- 관리자 로그아웃 후 기존 연결의 송·수신 권한 차단 및 로그인 페이지 이동. 세션 비활성 시간 검사와 outbound 권한 제거 단위 검사.
- PC/모바일 폭 넘침 없음, 입력 HTML을 문자열로 표시, localStorage 비밀값 저장 없음.

## 개발 중 발견하고 해결한 검사 문제

1. 공유 `target/classes`/`target/test-classes`의 파일이 실행 중 누락되어 최초 전체 실행에서 `ClassNotFound/NoClassDefFound`가 발생했다. 기존 `.classpath`가 같은 출력을 사용한다. 원본 pom/IDE 설정을 변경하지 않고 검증용 출력만 분리한 후 전체 1,211개가 통과했다.
2. 신규 `/api/support/config` 조회가 카탈로그 fixture의 API 호출 집계에 섞여 PC/태블릿/모바일 상세 검사 3개가 실패했다. 기존 assert를 약화하지 않고 인증·카탈로그·추천 fixture에서 기능 OFF 응답을 명시한 후 99개를 모두 재실행해 통과했다.
3. 최초 병렬 Playwright 실행은 같은 산출물 폴더를 사용해 trace 정리 ENOENT 1건이 발생했다. 고객센터 전용 outputDir을 `target/support-local/playwright-results`로 분리한 뒤 두 실행 모두 통과했다.
4. Windows `Start-Process`의 짧은 pg_ctl 프로세스에서 ExitCode가 null로 관측되어 정상 시작을 실패로 판단했다. 핸들 보존/갱신과 필수 물리 SQL 검증을 사용하고, 실제 정지→시작→검증을 재실행해 통과했다.
5. 상담 생성 이전에 보낸 빈 조회가 늦게 돌아오는 경합을 generation으로 차단했다. PC/모바일 실제 브라우저 회귀 검사를 추가했다.

최초 실패는 최종 통과로 숨기지 않고 위에 기록했다. 이 작업 중 Ubuntu 애플리케이션이나 기존 향수/AI 기능의 실제 장애를 확인한 것은 아니다.

## 데이터와 배포 보호

실제 변경한 PostgreSQL은 새 로컬 `127.0.0.1:26432/perfume_support_test`뿐이다. 물리 경로는 `C:/dev/projects/perfume/target/support-local/data`로 연결 후 검증했다. 실제 SQL 확인에서 이 DB의 `public/ai` 사용자 테이블은 0개, 격리 테스트 관리자 계정은 1개였다. 기존 5432/25432 DB와 18081 인증 preview 서버를 덮어쓰거나 중지하지 않았다.

검증 종료 후 이번 18082 테스트 서버와 26432 테스트 클러스터는 종료했다. 테스트 데이터 파일은 보존했다. 마지막 포트 검사에서 기존 5432/25432/8081/18081 프로세스 ID가 이전과 같음을 확인했다.

Ubuntu의 향수 4,048개·임베딩 678개는 최근 배포 기준값이다. 이번 작업에서는 개발서버에 접속하지 않아 현재 수치를 재측정하지 않았다. 서버 SQL·.env·관리자·PostgreSQL/Ollama 볼륨·모델·체크포인트·Jenkins 설정을 변경하는 작업은 없었다. 배포 보호 검사의 삭제/복원 출력은 모의 명령의 fixture 문구이며 실제 DB나 볼륨 작업이 아니다.

고객센터는 기본 OFF다. 기존 로그인·추천 코드 중 변경한 것은 신규 고객센터 경로의 인가 규칙뿐이며 RAG/검색/임베딩/추천 알고리즘과 AuthUserStore는 변경하지 않았다. Compose는 기존 backend에 설정 두 개를 추가하며 기본값 OFF/빈 Origin을 사용한다. 새 서비스·포트·볼륨은 없다. Jenkinsfile은 변경하지 않았다. 배포 스크립트에는 지원 설정 두 개의 상속 해제만 추가하고, 검증기는 해당 두 키/명시적 Origin/기존 인증 여부만 확장했다.

## 미검증 항목

- 로컬 Docker Desktop daemon pipe가 없어 실제 Docker 이미지 빌드·컨테이너 동작·`nginx -t`·Nginx 경유 WebSocket 101/쿠키/장시간 heartbeat는 실행하지 못했다. 정적 설정 검사와 모의 배포 검사는 실제 환경 통과로 표시하지 않는다.
- Vite의 `/ws/support` 프록시 설정을 추가했지만 실제 Vite 경유 WebSocket 업그레이드는 별도 확인 대상이다. 자동 채팅 브라우저 검사는 같은 origin의 Spring Boot 테스트 서버를 직접 사용했다.
- Ubuntu/Jenkins 실제 빌드·배포·Healthcheck·실제 계정 로그인·기존 데이터 수치는 이번 작업에서 재검증하지 않았다.
- 실제 Ollama 추론은 이 기능의 범위 밖이며 실행하지 않았다. 기존 추천 회귀는 기존 Java/React 모의 검사로 확인했다.
- 장시간 실제 세션 만료, 높은 부하, 다중 인스턴스, 메시지 보존·삭제 정책 운영은 별도 검증/설계 대상이다. `-Serve`의 장시간 수동 대기는 자동 검사에 포함하지 않았다.

## 변경 파일

기존 수정 18개, 신규 34개, 합계 52개. `target/`, 데이터, 로그, DPAPI 자격증명, node_modules와 Playwright 산출물은 제외한다.

기존 파일:

```text
.dockerignore
.env.example
Dockerfile
compose.yaml
frontend/.dockerignore
frontend/nginx.conf
frontend/package-lock.json
frontend/package.json
frontend/src/App.jsx
frontend/tests/browser/auth.spec.js
frontend/tests/browser/catalog.spec.js
frontend/tests/browser/recommendation.spec.js
frontend/vite.config.js
pom.xml
scripts/jenkins-deploy.sh
scripts/validate-perfume-compose.py
src/main/java/com/perfume/auth/AuthSecurityConfiguration.java
src/main/resources/application.properties
```

신규 파일:

```text
docs/sql/support-schema.sql
docs/support-chat.md
docs/support-chat-validation.md
frontend/playwright.support.config.js
frontend/src/support/AdminSupportPage.jsx
frontend/src/support/SupportChatWidget.jsx
frontend/src/support/SupportContext.jsx
frontend/src/support/SupportMessageInput.jsx
frontend/src/support/SupportMessageList.jsx
frontend/src/support/api.js
frontend/src/support/channel.js
frontend/src/support/support.css
frontend/src/support/useSupportConversation.js
frontend/src/support/useSupportWebSocket.js
frontend/tests/support-browser/races.spec.js
frontend/tests/support-browser/support.spec.js
frontend/tests/support.test.js
scripts/test-support-postgres.ps1
scripts/test-support.ps1
src/main/java/com/perfume/support/SupportAdmission.java
src/main/java/com/perfume/support/SupportConfiguration.java
src/main/java/com/perfume/support/SupportConnections.java
src/main/java/com/perfume/support/SupportController.java
src/main/java/com/perfume/support/SupportFailure.java
src/main/java/com/perfume/support/SupportMessaging.java
src/main/java/com/perfume/support/SupportModels.java
src/main/java/com/perfume/support/SupportSessions.java
src/main/java/com/perfume/support/SupportSocketConfiguration.java
src/main/java/com/perfume/support/SupportStore.java
src/test/java/com/perfume/support/SupportApiTests.java
src/test/java/com/perfume/support/SupportSecurityTests.java
src/test/java/com/perfume/support/SupportStoreTests.java
src/test/java/com/perfume/support/SupportTestApplication.java
tests/deployment/test_support_configuration.py
```

배포 전에는 별도 승인 후 `support-schema.sql`만 수동 적용하고, 서버 기존 `.env`를 보존하면서 지원 설정 두 키를 명시한 뒤 기존 Jenkins 경로로 앱만 배포해야 한다. 기존 auth SQL/bootstrap, DB 초기화/복원/삭제, 볼륨 재생성, Ollama 다운로드, TLS/8443 도입은 필요 없다.
