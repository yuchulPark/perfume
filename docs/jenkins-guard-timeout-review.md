# Jenkins 배포 보호 테스트 60초 초과 검토

2026-10-24 KST. 대상 커밋은 `7e4acd405a100d0eb63026b8ff9e326ed005255c`이며 실패 지점은 `Deployment guard tests`의 인증 OFF/ON 모의 배포 검사 중 `enabled='false'`다. Commit/Push, Jenkins 실행, 앱 빌드·재배포, 서버 `.env`/스키마/계정 변경은 하지 않았다.

## 확인한 원인과 확인하지 못한 부분

실제 Spring/React 애플리케이션이 실행되기 전, 모든 Docker/Git/curl/flock 명령을 대체한 임시 `deploy.sh`가 제한 시간을 넘겼다. 실제 DB 연결이나 앱 추천 요청이 이 테스트의 실행 경로에 없다. 따라서 이 실패는 HTTP 로그인이나 추천 구현의 오류를 입증하는 결과가 아니다.

기존 하네스는 정상 모의 배포 한 번에 외부 명령 50개를 각각 Python mock으로 실행했다. Python 중계 프로그램도 10회 실행했으며 그중 inline 프로그램 2개는 다시 자식 Python을 실행했다. 즉 OFF 또는 ON 한 경우에 **Python 기동 62회**가 필요했다. OFF/ON을 모두 검사하면 124회다. 각 mock이 표준 라이브러리를 다시 import하고 fixture JSON을 읽었다. `python3 ... --help`와 실제 validator 실행도 매번 별도의 중계를 거쳤다.

계측한 느린 구간은 configuration/database 검증에서 반복하는 명령과 실제 validator 진입이다. 최적화 전 Linux 단계 계측에서는 개별 database 구간에 약 0.58~0.91초의 간격이 반복됐고, OFF/ON 전체는 26.9초였다. 다른 수정 전 Linux 실행은 74.8초였고 내부 셸 한 번의 관측 시간이 약 64초였다. 그 실행에서는 `TimeoutExpired`가 발생하지 않고 반환했다. 관측한 벽시계 시간과 timeout 예외의 발생 여부를 동일하게 취급하지 않는다.

**실패 당시의 60초 초과 예외를 동일하게 재현하지는 못했다.** 당시에는 단계별 기록과 자원 계측이 없어 특정 CPU/I/O/스케줄러 지연으로 확정할 수 없다. 사용자 확인에 따르면 동시에 수행한 임베딩·추론·다른 빌드는 없었다. 확인된 문제는 과도한 프로세스 기동 비용과 실행 시간의 큰 변동이다. 이 비용을 제거하고 다음 실패 시 지연 단계를 남기도록 수정했다. `timeout=60`은 유지했다.

## Windows와 Jenkins 환경 차이

| 항목 | Windows | Jenkins와 같은 Linux 검사 |
|---|---|---|
| Python | 3.13.14, Windows 네이티브 | 기존 `python:3.12-slim`, Python 3.12.15 |
| 셸 | Git Bash/MSYS 경로 변환 필요 | Linux Bash, 직접 Python shebang 사용 가능 |
| CPU | 호스트 16개 논리 CPU | VirtualBox VM 4 vCPU, 실행 제한 100% |
| 임시 파일 | Windows 임시 디렉터리 | 최종 검사는 컨테이너 일반 `/tmp` writable layer |
| Docker CLI | 있음: offline Compose 검사 가능 | 이미지에 없음: 기존 Compose CLI 검사 4건 생략 |

최종 Linux 검사는 Jenkins가 사용한 것과 같은 VM의 캐시된 이미지 ID `sha256:a6e34c598f2467ed0e9a8d349809fcd8b5c603269512df273a0bb1784edc11b1`로 수행했다. UID/GID 1000, 네트워크 없음, capability 제거, 기존 볼륨·Docker 소켓 mount 없음이다. 소스는 비밀 파일을 제외한 tar로 전달했다. Jenkins job이나 그 workspace는 실행/변경하지 않았다. 소스 위치는 Jenkins workspace와 다르므로 실제 Pipeline 재실행과는 구분한다.

초기 측정은 tmpfs였지만 최종 5회 반복 및 전체 회귀는 Jenkins처럼 일반 컨테이너 `/tmp`를 사용했다. 최종 반복 실행의 cgroup 기록에서 CPU throttling은 0이었다. 이는 해당 검사의 상태이며 실패 당시 상태를 증명하지 않는다.

SSH 전송 도중에는 별도의 배너/keepalive 시간 초과가 있었다. 사용자가 확인한 ssh.service/ssh.socket은 active이고 22 포트는 listen이었다. HTTP healthz도 정상 응답했다. Windows SSH 명령에 IPv4/IPQoS=none을 적용한 연결에서 다시 검증을 완료했다. 서버 SSH 및 Jenkins SSH 설정은 수정하지 않았고, 이 현상을 원래 모의 테스트 실패의 원인으로 단정하지 않았다. 모의 테스트 자체는 SSH를 호출하지 않는다.

## 수정 범위

- `tests/deployment/test_jenkins_db_preservation.py`: 실제 배포 셸을 계속 실행하되 mock 명령은 격리된 Bash subshell 함수로 연결한다. 외부 자식 셸이 호출하는 경우에도 PATH mock이 남아 실제 Docker/Git으로 넘어가지 않는다.
- `tests/deployment/mock_deploy_tools.py`: 응답 fixture를 한 번 준비하고 Bash builtin으로 읽는다. 모의 명령의 Python 기동을 제거했다. Python은 실제 validator와 inline 프로그램에만 사용하며 중복 자식 interpreter 실행을 없앴다. 한 정상 모의 배포에서 Python 기동은 **62회 → 10회**다. Windows의 승인 파일 경로 변환과 실제 validator의 검증·CLI·종료 코드는 유지한다.
- `tests/deployment/mock_deploy_diagnostics.py`: 별도 FD로 고정 단계 이름·줄 번호·시간만 기록한다. `set -x`, 원문 명령, 환경변수 값, SQL/JSON/비밀번호를 진단 출력에 넣지 않는다. 실패/시간 초과 시 마지막 단계와 가장 긴 간격을 표시한다. timeout에는 생성한 mock 프로세스 트리만 정리하도록 요청하고, 자식이 capture pipe를 붙잡아 대기가 연장되지 않도록 파일로 stdout/stderr를 받는다.
- `tests/deployment/test_mock_deploy_harness.py`: 비밀값 비출력, 결정적인 timeout/정리 요청, 알 수 없는 mock 명령 거부와 argv 보존, inline Python의 globals/argv/stdin 동작을 검사하는 4건을 추가했다. timeout 검사는 가짜 프로세스로 처리해 부하에 의존하는 sleep 검사를 만들지 않았다.

기존 인증 ON/OFF, 상속된 인증/AI/profile 환경변수 제거, HTTP override 선택, DB ID·볼륨·포트·`.env`·준비 표식·승인 파일 보존, backend/frontend만 교체, 임베딩 marker 차단, 위험한 이미지/입력 거부 검사는 그대로다. 알 수 없는 mock 명령은 실패 처리한다. 실제 `scripts/jenkins-deploy.sh`, validator, Jenkinsfile, Compose, Java/React 인증·추천·임베딩 코드는 변경하지 않았다.

진단 예시:

```text
Mock deploy exceeded 60s; mock-deploy elapsed=60.123s last=database:line-123 slowest=[...]
mock-processes commands=50 python-starts=10
```

단계는 setup/configuration/database/git-fetch/git-reset/build-apps/replace-apps/healthcheck 중 하나다. 줄 번호는 임시 fixture 셸 또는 mock 경계의 번호다. 실패 진단은 항상 붙고, 성공 실행의 계측 출력은 `PERFUME_TEST_DEPLOY_TIMINGS=1`일 때만 출력된다.

## 최종 테스트 시간

| 검사 | 결과 | 시간 |
|---|---|---|
| 수정 전 Windows 문제 테스트 OFF+ON | 통과 | 42.973초 |
| 수정 전 Linux 문제 테스트 OFF+ON | 통과, 시간 변동 관측 | 74.792초; 별도 단계 계측 실행 26.907초 |
| 수정 후 Linux 반복 1~5 | 매회 OFF와 ON 모두 통과 | 4.839 / 6.459 / 8.118 / 9.852 / 5.871초 |
| 수정 후 Linux 전체 | 91건: 87 통과, 4 생략, 실패/오류 0 | 38.717초 |
| 수정 후 Windows 전체 | 91 통과, 생략/실패/오류 0 | 220.322초 |
| Windows 최종 관련 재검사 | 문제 테스트 OFF/ON 통과, 진단 4건 통과 | 29.965초 / 2.153초 |

Windows 전체 검사 후 출력 캡처와 진단 줄 번호를 보강했고 관련 검사 5건을 다시 통과했다. 최종 Linux 전체 검사는 이 보강까지 포함한 코드로 실행했다. Linux 전체 안에서도 문제 테스트의 OFF/ON 두 경우가 추가로 통과했다. 기존 87개는 모두 회귀 실행했으며 원래 실패한 테스트에 skip을 추가하지 않았다.

Linux의 4건 생략은 기존 Docker CLI 의존 검사다: HTTP override 렌더링, 기본 Compose/guard 일치, image ID override 렌더링, pgvector rehearsal Compose 렌더링. Jenkins 실패 로그의 4건 생략과 같은 조건이다. 이 네 건은 Windows의 실제 Docker CLI offline 렌더링에서 통과했다. Docker daemon은 필요하지 않다.

주요 실행 명령:

```powershell
& "$env:LOCALAPPDATA\Programs\pgAdmin 4\python\python.exe" -B -m unittest discover -s tests/deployment -v
```

Linux 컨테이너 내부의 재검증 명령은 다음과 같다. 프로젝트 소스가 준비된 격리 디렉터리에서 실행하며 실제 서버의 배포 스크립트를 직접 호출하는 명령이 아니다.

```bash
PERFUME_TEST_DEPLOY_TIMINGS=1 PYTHONDONTWRITEBYTECODE=1 \
  python -m unittest discover -s tests/deployment \
  -p test_jenkins_db_preservation.py -k authentication_modes_ignore_inherited_flags -v
PYTHONDONTWRITEBYTECODE=1 python -m unittest discover -s tests/deployment -v
```

원시 계측/전체 결과와 재현 도구는 Git에서 제외되는 `target/jenkins-guard-review/`에 있다. 하네스 helper 두 파일·새 회귀 테스트·기존 회귀 테스트 수정과 이 문서만 반영 대상이다. 임시 tar·fixture·로그·실제 환경/자격증명은 추가하지 않는다.

## 서버 및 데이터 보존

최종 읽기 전용 검사에서 다음을 확인했다.

- backend/frontend/DB/Ollama/Jenkins의 컨테이너 ID·이미지·mount가 검사 전과 같고 네 애플리케이션 서비스는 모두 healthy다.
- DB system identifier `7694641528401440806`, 향수 4,048개, 임베딩 678개, 관리자 1개다. 기존 관리자 테이블 내용의 집계 digest도 이전 HTTP 적용 기록과 같다. 평문이나 BCrypt 원문은 출력하지 않았다.
- `perfume-dev_pgdata`, `perfume-dev_ollama-data`와 기존 `.env` SHA-256이 보존되었다. 인증 true, 개발 HTTP true, Secure=false, `auth-dev-http` profile이 유지된다.
- 기존 `qwen3-embedding:0.6b`, `qwen3:4b-instruct` 모델이 남아 있다. 추론·다운로드·임베딩을 실행하지 않았다.
- 실제 HTTP healthz는 200, 비로그인 추천 POST는 401이다. 추천 추론은 발생하지 않는다.

명명된 데이터 볼륨을 삭제/재생성하거나 기존 컨테이너를 재시작하지 않았다. 일회용 테스트 컨테이너의 writable layer만 사용하고 종료 후 제거했다. rehearsal 테스트 로그의 test volume 제거/복구 메시지는 mock 출력이며 실제 볼륨 작업이 아니다. 위 행 수·ID·digest는 해당 범위의 보존 근거이며 모든 DB 블록의 checksum 비교는 아니다.

실제 Jenkins 재실행, Spring/React 이미지 빌드 및 재배포는 이번에 수행하지 않았다. 다음 승인된 CI 실행에서 이 변경을 반영한 결과를 확인해야 한다. 기존 HTTP 로그인과 AI 추천 구현 변경은 필요하지 않았다.
