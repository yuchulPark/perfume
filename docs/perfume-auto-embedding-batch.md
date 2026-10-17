# 자동 증분 임베딩 배치 (RAG 5차)

이 변경은 Windows 로컬 코드와 Mock/오프라인 테스트만 대상으로 한다. 아래 Ubuntu 명령은 **향후 서버 적용 승인을 받은 관리자가 실행하는 절차**다. 이번 작업에서 Commit/Push, Jenkins 실행, SSH, 실제 DB/Ollama 호출, Docker 실행, systemd 설치·활성화를 하지 않는다.

## 1. 실행 구조와 기본 비활성화

```text
systemd timer (운영자가 시각 지정, 설치만으로 활성화되지 않음)
  → oneshot service
  → perfume-auto-embedding.sh: 기존 /srv/perfume/.perfume-deploy.lock에 flock -n
  → Python 감독 프로세스: 로컬 이미지/기존 서비스 확인, 시간 제한, 종료 신호, 안전한 로그
  → docker compose run --rm --no-deps --pull never -T
      --name perfume-dev-embedding-batch --entrypoint java backend ...
  → 자동 runner: 파일 checkpoint/작업 구간 관리
  → 기존 PerfumeEmbeddingBatch: DB advisory lock, SHA-256 비교, 직렬 embed, 원본 재검증, 개별 upsert
```

기존 웹 backend/frontend/DB/Ollama를 시작·재생성·재시작하지 않는다. 새 Compose 상주 서비스, HTTP 실행 API, `@Scheduled`, 의존성, DB 테이블 또는 migration을 추가하지 않는다. 기존 `.env`, pgvector 0.8.7, PostgreSQL 17, 1024차원, `perfume-v1`, Ollama 모델/볼륨/3 CPU·6GB 제한을 유지한다.

세 가지 실행 보호가 있다. Spring의 `batch.enabled=false`, `auto.enabled=false`가 기본이다. 자동 실행 구성 파일도 `ENABLED=false`다. timer에는 예약 시각을 넣지 않았다. 예약 설정 전 timer는 유효한 스케줄이 없는 준비용 단위 파일이며 시작할 수 없다. 아래 설치는 service만 먼저 설치하고, 승인된 `OnCalendar` drop-in과 함께 timer를 설치한다.

## 2. 설정

운영 설정은 Git 외부 `/etc/perfume/embedding-batch.conf`에 저장한다. `.env`는 수정하지 않는다. 설정 형식은 단순 `KEY=value`이며 셸 확장, 따옴표, 중복 키, 미등록 키, 인라인 주석을 허용하지 않는다.

| 키 | 기본값 | 허용 범위 / 의미 |
| --- | --- | --- |
| ENABLED | false | timer 실행 허용; 관리자의 `--once`는 false 상태에서도 실행 가능 |
| MODEL | qwen3-embedding:0.6b | 기존 모델 유지 |
| MODEL_DIGEST | 비어 있음 | 설치된 Ollama 모델의 전체 소문자 SHA-256 digest; 실행 전 필수 |
| MAX_ITEMS | 100 | 1–10000, 실행당 **검사** 건수; 신규 생성 수가 아님 |
| MAX_RUNTIME_SECONDS | 3600 | 1–86400, worker 기동/모델 로딩 포함 감독 시간 예산 |
| PAGE_SIZE | 25 | 1–100 |
| DELAY_MS | 250 | 0–60000; 임베딩 시도 후 대기 |
| READ_TIMEOUT_MS | 300000 | 1–300000; 개별 HTTP 읽기 제한 |

신규·변경 임베딩을 직렬 처리한다. Java heap은 최대 512MB다. worker에는 진도 디렉터리만 추가 bind mount하며 DB 데이터 볼륨을 연결하지 않는다. backend 이미지의 UID/GID 10001이 이 디렉터리에 쓸 수 있어야 한다.

Java는 항목 사이에서 시간 예산/중단 요청을 확인한다. 이미 시작한 HTTP 요청은 읽기 제한 내에서 끝내고, 정상 응답이면 원본 재검증 후 개별 저장한다. 감독 프로세스는 시간 예산 초과나 SIGTERM에서 **자기 소유 worker만** `docker stop --time 400`으로 종료한다. JVM 종료 hook은 최대 390초 동안 현재 작업 완료와 checkpoint 저장을 기다린다. systemd `TimeoutStopSec=450`, `KillMode=mixed`로 감독 프로세스에 먼저 종료 신호를 보낸다. 시간 제한은 즉시 강제 종료 시간이 아니며, 정상 정리에 최대 약 400초와 Docker 명령 대기 시간이 추가될 수 있다. preflight 명령은 각각 30초로 제한한다. 무제한 자동 재시도와 service 자동 재시작은 없다.

## 3. 커서, 신규 유입, 실패·재부팅 복구

DB 스키마를 바꾸지 않고 `/var/lib/perfume-embedding/state.json`을 사용한다. 내용은 버전, 모델명/전체 digest/텍스트 버전, 관측 최대 ID, 미완료 검사 구간 목록, 마지막 성공 시각, 최근 결과다. 원본 텍스트와 벡터는 없다. `worker.lock`으로 자동 실행끼리 파일 읽기/수정을 배타적으로 보호한다. 임시 파일 쓰기→파일 fsync→atomic rename→Linux 디렉터리 fsync로 교체한다. atomic move 미지원, 파일 손상, 모델 식별 불일치 시 자동 초기화하지 않고 실패한다.

각 구간은 `(afterId, upperId]`다. DB에서 현재 건수와 최대 ID를 조회하므로 ID 간격과 향수 수를 하드코딩하지 않는다.

1. 최초 실행은 현재 최대 ID까지 `(0, upperId]`를 등록한다.
2. 실행당 맨 앞 구간 하나에서 최대 `MAX_ITEMS`개를 검사한다. 기존 해시가 같으면 embed 호출/갱신을 하지 않는다.
3. 실패 없는 정상 실행은 완료된 접두 구간만 인정한다. 미검사 suffix는 목록 뒤로 보낸다.
4. 부분 실패·중단 시 검사한 prefix 전체를 원래 `afterId`부터 재검사하도록 보존한다. 나머지 suffix도 별도 구간으로 남긴다. 성공한 개별 upsert를 되돌리거나 삭제하지 않는다.
5. 실패한 prefix와 suffix를 목록 뒤로 순환시킨다. 특정 실패가 나머지 ID를 영구히 막지 않으며, 매 실행에서 관측 상한보다 높은 신규 ID 구간도 추가한다. 재시도는 다음 예약/관리자 실행에서만 한다.
6. 모든 pending 구간이 끝나면 다음 실행은 다시 0부터 새 전체 검사 주기를 시작한다. 낮은 ID의 상세 변경, 늦게 커밋된 낮은 ID도 이 주기에서 반영한다.

예: `(0,900]`에서 2개 검사 후 `lastId=80`, ID 2 실패, ID 80 성공이면 `(80,900]`, `(0,80]`를 남긴다. 실패한 ID 2를 완료로 처리하지 않으면서 이후 ID도 처리할 수 있다. 보고서의 `nextCursor`는 **다음 큐 구간의 시작점**이며 전체 진행의 최대 ID가 아니다. 순환 과정에서 감소할 수 있다. `pendingRanges`는 향수 건수가 아니다.

입력 상한과 pending 목록을 작업 전에 먼저 저장한다. 프로세스/VM이 upsert 후 checkpoint 전에 죽으면 이전 구간부터 다시 읽고 이미 저장한 행은 해시로 skip한다. 실제 VM 전원 종료를 Mock으로 재현했다고 주장하지 않으며, 테스트는 durable checkpoint와 개별 커밋이 남은 재시작 상태를 검증한다.

새 ID가 실행 도중 추가되면 다음 실행에서 상한 증가를 반영한다. 중간의 낮은 ID 추가/수정은 다음 전체 주기에서 반영한다. 유입이 처리 용량보다 많아 전체 주기가 끝나지 않으면 변경 검사의 지연도 커진다. 실패 prefix는 미완료이므로 성공 실행 하나가 있었다고 전체 backlog가 해소됐다고 판단하지 않는다. pending 구간은 10000개를 넘기면 실패하고 관리자가 원인/용량을 확인해야 한다.

## 4. Jenkins·수동 실행 잠금

별도 보호 셸이 기존 `.perfume-deploy.lock` FD 9에 `flock -n`을 획득한다. FD는 Python과 Docker Compose 자식에게 상속되고 종료 정리가 끝날 때까지 유지된다. 잠금 실패는 `SKIPPED/DEPLOY_BUSY`, 종료 코드 75다. systemd는 75를 허용하지만 journal에서 건너뜀을 구분한다. 유지보수 셸을 중첩 호출하지 않는다. 기존 DB session advisory lock은 수동/자동 배치, 모델이 다른 배치 사이에도 유지한다. DB 잠금 실패는 checkpoint에 `SKIPPED/BATCH_BUSY`로 기록한다.

감독 프로세스는 worker 생성 전에 `.perfume-embedding-active` 복구 표식을 만든다. Jenkins는 같은 파일 잠금을 획득한 후 이 표식이 있으면 Git/Docker 배포 전에 중단한다. 강제 종료로 Docker daemon의 worker만 살아남아도 배포를 차단한다. Jenkins의 기존 잠금이나 DB 보호를 해제하지 않는다. 표식은 **잠금 파일과 다른 파일**이며 worker 부재를 확인한 배치 감독 프로세스만 자동 정리한다.

동일한 `perfume-dev-embedding-batch` 컨테이너가 있으면 새 실행은 `SKIPPED/WORKER_EXISTS`로 종료하고 자동 인수·삭제하지 않는다. worker 이름, Compose project/service/oneoff label과 invocation 소유 label이 모두 일치해야 감독 프로세스가 stop할 수 있다. Docker 상태를 확실히 확인하지 못하면 표식을 남기고 실패한다.

기존 수동 명령과 `batch.after-id`, `batch.max-items` 의미는 유지한다. 자동 상태는 수동 실행으로 임의 이동하지 않는다. 수동 배치 역시 기존 유지보수 셸의 Jenkins 잠금과 고정 worker 이름을 사용한다. 원시 수동 Docker 명령을 보호 셸 없이 실행하면 Jenkins 파일 잠금까지 보장하지 못한다. DB advisory lock은 계속 작동한다. 자동 worker 실행 중 유지보수 셸을 다시 열거나 FD 9를 재개방하지 않는다. **잠금 파일을 삭제하거나 강제로 unlock하지 않는다.**

## 5. Windows ScentRev Import 조정과 제한

`ScentRevSingleBrandImportService`는 `NOT_SUPPORTED`에서 discovery/상세 API를 직렬 호출한다. 저장은 `ScentRevPhase1ImportService.importPageForSeededBrand`의 페이지별 `REQUIRES_NEW` 트랜잭션으로 커밋한다. 기존 `importProfile`은 `REQUIRED`, on-demand 저장은 외부 `REQUIRES_NEW`에 참여한다. `ScentRevProfileDataService.save`는 `MANDATORY`로 같은 저장 트랜잭션에 참여한다. curated Brand seed/이름 변경도 별도 트랜잭션이다. 따라서 Ubuntu 파일 잠금으로 Windows 프로세스까지 동기화할 수 없다.

최소 변경으로 실제 향수 저장 두 진입점과 Brand seed 진입점에 `ScentRevCatalogWriteGuard`를 주입했다. 저장 트랜잭션 안에서 기존 배치와 **같은 bigint 키**로 `pg_try_advisory_xact_lock_shared`를 획득한다. 임베딩 배치는 기존 exclusive session lock을 유지한다.

- Import 쓰기 트랜잭션이 먼저면 배치는 DB 잠금 실패로 skip한다.
- 배치가 먼저면 Import 현재 쓰기 트랜잭션이 `EMBEDDING_BUSY`로 실패/rollback한다. 이미 커밋한 이전 페이지는 보존된다. 무제한 대기/재시도하지 않는다.
- Import의 네트워크 호출 전체를 잠그지는 않는다. 배치가 그 사이 시작하면 이후 Import 페이지 저장이 거부될 수 있다. Preview/skip-existing 조회 경로는 그대로다.

이 조정은 **새 코드가 반영된 Spring Import 실행본**에만 적용된다. 이전 Eclipse 프로세스, 수동 SQL, 다른 쓰기 프로그램은 이 advisory lock을 따르지 않을 수 있다. 최초 활성화는 Windows Import/Eclipse 실행을 종료하고 작업 시간을 분리하는 운영 절차를 전제로 한다. Import가 없다고 시간/건수/테이블 활동으로 추정하지 않는다. 모든 쓰기를 DB 차원에서 강제 차단하는 trigger/DDL은 도입하지 않았다.

기존 생성 후 SHA-256 재검증과 `SOURCE_CHANGED`를 유지한다. 재검증 직후 비협조 쓰기가 발생하는 작은 경합 구간까지 없앴다고 주장하지 않는다. 다음 전체 재검사가 변경을 반영한다. 최신 Import 실행본 적용과 실제 PostgreSQL 두 세션 잠금 검증은 서버 적용 단계에서 별도로 수행한다.

## 6. 최초 백로그와 일일 처리량

사용자 실측 20개/949초를 단순 환산하면 **47.45초/신규 1개, 약 75.9개/시간**이다. 이는 해당 실행의 평균이며 모델 로딩/내용 길이/CPU 경합/타임아웃에 따라 달라진다. 동일 해시 skip 속도는 별도로 실측해야 한다.

| 작업 | 단순 추정 (모두 신규인 경우) |
| --- | --- |
| 100개 | 약 79분 5초 |
| 200개/일 | 약 2시간 38분 10초/일 |
| 500개/일 | 약 6시간 35분 25초/일 |
| 실측 시점 2294−35=2259개 잔여 | 약 29시간 46분 30초 |

이 수치는 설정값이나 고정 목표가 아니다. 실제 잔여 건수는 `--status-db`로 조회한다. 최초 적재는 백로그 전용 운영 기간에 건수·시간 제한을 둔 여러 번 실행으로 나눈다. 기본 100건/1시간 설정은 보수적인 준비값이며 신규만 있으면 약 75건 전후에서 시간 제한에 도달할 수 있다. 1시간 초과는 실패/중단으로 기록하고 성공 완료로 바꾸지 않는다. 예를 들어 100건 구간을 완료하려면 관측 평균 79분에 모델 로딩/변동 여유를 추가한 예산을 관리자가 결정해야 한다.

매일 200–500개 유입을 따라가려면 이 단순 평균에서 신규 생성에만 하루 약 2.64–6.59시간 이상을 확보해야 한다. 하루 1시간이면 약 76개 이하로, 유입에 따라 일일 약 124–424개 이상 backlog가 늘 수 있다. 전체 기존 향수 hash 검사와 변경분 생성, 실패 재검사, 시작 비용도 추가된다. `MAX_ITEMS=500`을 주더라도 기존 500개를 skip하면 그 실행의 신규 생성 수는 0일 수 있다. 큐는 기존 구간과 높은 신규 구간을 순환하므로 신규가 검사 커서 뒤에 영구히 숨는 문제를 피하지만 처리 용량 부족을 해결하지는 않는다.

journal의 일일 `new+changed`, 실경과 시간, `--status-db`의 total/missing 변화, pending 구간과 전체 주기 완료 여부를 함께 관찰한다. 전일 대비 missing 증가, 지속 TIME_LIMIT, 실패 구간 증가, 전체 재검사 주기 지연은 설정/용량 조정 근거다. 실측하지 않은 CPU 개선이나 병렬화 성능을 보장하지 않는다. 더 빠른 서버에서도 동일한 모델 identity·checkpoint 형식과 건수/시간 설정을 사용할 수 있다. 이전 시 worker 정지, 이미지/UID/경로 확인과 checkpoint 백업이 필요하다.

## 7. 승인 후 설치와 기본 비활성 확인

선행 조건: 이번 코드가 포함된 backend 이미지가 별도 승인된 기존 배포 방식으로 반영돼 있어야 한다. 운영 DB/모델은 이미 준비된 상태를 사용한다. 기존 임베딩 SQL을 재적용하지 않는다. Import를 종료하고 현재 DB 백업/복원 검증은 [기존 백업 절차](pgvector-production-deployment.md)를 따른다. 이미지 전환/extension 생성/운영 DB 복원 단계는 실행하지 않는다.

```bash
cd /srv/perfume
sudo install -d -m 0755 /etc/perfume
sudo install -m 0600 deploy/systemd/embedding-batch.conf.example /etc/perfume/embedding-batch.conf
sudo install -d -m 0700 -o 10001 -g 10001 /var/lib/perfume-embedding
sudo install -m 0644 deploy/systemd/perfume-embedding.service /etc/systemd/system/perfume-embedding.service
sudo systemctl daemon-reload
sudo python3 /srv/perfume/scripts/perfume-auto-embedding.py --check
systemctl is-enabled perfume-embedding.service || true
systemctl is-active perfume-embedding.service || true
```

`CONFIG_VALID_DISABLED`와 inactive를 확인한다. `--check`는 로컬 설정만 검사하고 Docker/DB/Ollama를 호출하지 않는다. service에는 `[Install]`이 없으며 timer를 통해서만 예약한다. 설정 파일은 자격증명을 넣는 용도가 아니다.

## 8. 승인 후 수동 1회 검증 (자동 실행은 계속 false)

기존 [수동 배치 문서](perfume-embedding-batch.md)의 읽기 전용 `/api/tags` 확인으로 **실제 설치 모델 전체 digest**를 확인한다. 새 다운로드/pull을 하지 않는다. 설정 파일에 digest를 넣고 `MAX_ITEMS=5`, 충분한 최초 검증 시간 예산(예: 관리자가 승인한 900초)을 지정한다. `ENABLED=false`를 유지한다.

```bash
sudoedit /etc/perfume/embedding-batch.conf
sudo python3 /srv/perfume/scripts/perfume-auto-embedding.py --check
sudo systemd-run --unit=perfume-embedding-once --collect \
  --property=Type=oneshot --property=TimeoutStartSec=infinity \
  --property=TimeoutStopSec=450 --property=KillMode=mixed \
  --property=StandardOutput=journal --property=StandardError=journal \
  /usr/bin/bash /srv/perfume/scripts/perfume-auto-embedding.sh --once
sudo journalctl -u perfume-embedding-once -f
```

실행 요청 후 SSH가 끊겨도 systemd가 작업을 관리한다. journal의 `-f`는 Ctrl+C로 나와도 worker를 중단하지 않는다. `--once`는 관리자 명시 실행이므로 timer 허용 플래그만 우회하고 모든 파일/DB/모델/시간/건수 보호는 그대로 적용한다. 진도 파일이 이미 있다면 저장된 구간을 이어서 검사하며 무조건 ID 0에서 시작하는 명령이 아니다.

```bash
sudo python3 /srv/perfume/scripts/perfume-auto-embedding.py --status-db
sudo journalctl -u perfume-embedding-once -n 100 --no-pager
sudo ls -l /var/lib/perfume-embedding
```

service/worker 실제 종료, 신규/변경/skip/실패, 기존 DB·웹 상태와 모델 identity를 확인한다. `--status-db`는 기존 DB 컨테이너에 count SELECT만 실행한다. 모델별 적용률/미생성 추정치를 출력하며 내용이 바뀐 오래된 임베딩은 이 count만으로 판별하지 못한다. 실패 테스트를 위해 운영 DB/모델을 고의 변경하지 않는다. 동일 원본 재검사는 pending 구간을 모두 완료한 뒤 새 전체 주기 또는 기존 수동 `after-id=0`의 승인된 소규모 명령으로 확인한다.

## 9. 승인된 예약 시각을 지정하고 활성화

관리자가 유입량, CPU 예산, Import/Jenkins 작업 시간, 실제 모델 로딩/검사 속도와 서버 시간대를 먼저 결정한다. 임의의 매일 시각이나 부팅 즉시 실행을 기본 제공하지 않는다.

```bash
timedatectl
read -r -p '승인된 systemd OnCalendar 표현식: ' EMBEDDING_CALENDAR
systemd-analyze calendar "$EMBEDDING_CALENDAR"
# 위 표현식의 파싱과 다음 시각을 확인한 뒤 진행
sudo install -d -m 0755 /etc/systemd/system/perfume-embedding.timer.d
printf '[Timer]\nOnCalendar=\nOnCalendar=%s\n' "$EMBEDDING_CALENDAR" | \
  sudo tee /etc/systemd/system/perfume-embedding.timer.d/schedule.conf > /dev/null
sudo install -m 0644 /srv/perfume/deploy/systemd/perfume-embedding.timer /etc/systemd/system/perfume-embedding.timer
sudoedit /etc/perfume/embedding-batch.conf
# MODEL_DIGEST / MAX_ITEMS / MAX_RUNTIME_SECONDS를 확정하고 ENABLED=true로 설정
sudo python3 /srv/perfume/scripts/perfume-auto-embedding.py --check
sudo systemctl daemon-reload
sudo systemd-analyze verify perfume-embedding.service perfume-embedding.timer
sudo systemctl enable --now perfume-embedding.timer
systemctl list-timers --all perfume-embedding.timer
```

`Persistent=false`이므로 서버가 꺼져 있던 예약을 부팅 직후 몰아서 실행하지 않는다. 부팅 후 다음 승인된 시각에 checkpoint를 이어간다. 실행 중 timer가 다시 만료되어도 같은 oneshot service를 중복 실행하지 않는다. 다른 관리자의 직접 실행은 flock/고정 worker 이름/DB 잠금으로 보호한다. 잠금 충돌과 실패는 다음 예약까지 기다리며 즉시 반복하지 않는다.

## 10. 일시 중지, 중단, 재개, 비활성화

```bash
# 다음 예약만 중지; 현재 작업은 계속
sudo systemctl stop perfume-embedding.timer

# 현재 worker도 정상 종료 요청. 최대 정리 시간을 기다린다.
sudo systemctl stop perfume-embedding.service
# 별도 --once 작업을 실행 중이면 해당 단위도 종료
sudo systemctl stop perfume-embedding-once.service

# 설정/진도를 유지하고 예약 재개
sudo systemctl start perfume-embedding.timer

# 완전 비활성화
sudo systemctl disable --now perfume-embedding.timer
sudo systemctl stop perfume-embedding.service
sudoedit /etc/perfume/embedding-batch.conf
# ENABLED=false 저장
```

실행한 적 없는 `once` 단위의 stop은 unit-not-found가 될 수 있다. 비활성화 후 `--status`와 Docker 고정 worker 부재를 확인한다. 설정을 false로 바꾸는 것만으로 이미 실행 중인 작업이 취소되지는 않는다. 정상 중단도 부분 커밋이 있을 수 있으며 다음 실행은 checkpoint 구간을 재검사한다.

## 11. 강제 종료, 재부팅과 복구 표식

우선 예약을 중지하고 기존 잠금이 비었는지 확인한 뒤, 이전 worker 상태를 읽기 전용으로 조사한다. 다음 명령은 명명된 worker 하나만 대상으로 한다.

```bash
sudo systemctl stop perfume-embedding.timer
sudo docker ps --all --filter 'name=^/perfume-dev-embedding-batch$'
sudo docker inspect --format '{{.State.Status}} {{json .Config.Labels}}' perfume-dev-embedding-batch
sudo journalctl -u perfume-embedding.service -n 100 --no-pager
```

컨테이너가 없다면 inspect 실패는 예상 가능하다. 남아 있는 컨테이너의 `project=perfume-dev`, `service=backend`, `oneoff=True`, `com.perfume.embedding.owner`와 표식의 소유값을 확인한다. 자동으로 강제 인수하지 않는다. 정상 실행 중이면 두 번째 worker를 만들지 않는다. 관리자가 해당 **고아 worker 종료를 승인하고 소유 확인한 뒤** 아래 명령만 사용한다.

```bash
sudo docker stop --time 400 perfume-dev-embedding-batch
# --rm worker가 자동 제거됐는지 확인
sudo docker ps --all --quiet --filter 'name=^/perfume-dev-embedding-batch$'
```

중지된 컨테이너가 남으면 원인을 검토한 뒤 그 컨테이너 하나만 별도로 정리한다. DB/backend 웹 컨테이너를 stop/rm하지 않는다. 다음 정상 배치 실행은 파일 잠금을 잡고 worker 부재를 확인한 다음 stale `.perfume-embedding-active` 표식을 정리한다. **`.perfume-deploy.lock`이나 `worker.lock` 파일은 삭제하지 않는다.** Docker 상태 조회 실패나 worker가 남은 상태에서 표식을 수동 삭제하지 않는다.

진도 파일이 손상됐으면 원본을 보관하고 실행을 중단한다. 정상 checkpoint 백업을 복원하거나, 관리자가 전체 재검사를 명시적으로 선택한 경우에만 기존 state.json을 별도 백업 이름으로 옮겨 새 주기를 시작한다. 임베딩 행은 삭제/초기화하지 않는다. 새 모델 digest로 checkpoint를 덮어쓰지 않는다.

## 12. 로그와 상태, 백업

```bash
sudo python3 /srv/perfume/scripts/perfume-auto-embedding.py --status
sudo python3 /srv/perfume/scripts/perfume-auto-embedding.py --status-db
sudo journalctl -u perfume-embedding.service -u perfume-embedding-once -n 100 --no-pager
systemctl list-timers --all perfume-embedding.timer
```

`--status`는 timer 활성/실행 여부, service 상태/종료 결과, 다음 예정 시각, checkpoint 최근 결과/마지막 성공, worker/복구 표식 유무를 보여준다. `--status-db`는 추가로 현재 total, 해당 모델의 embedded, coverage_percent, missing_estimate를 읽기 전용 조회한다. 기존 원본의 변경 건수는 실제 해시 검사 전 알 수 없다. 새 실행이 JVM 시작 전에 실패하면 checkpoint의 이전 결과가 남으므로 service `Result/ExecMainStatus/시각`과 journal을 함께 확인한다. timer 외 직접 `--once`는 별도 unit journal이 기준이다.

Java 보고서에는 시작/종료 UTC, SUCCESS/FAILED/INTERRUPTED/SKIPPED, 고정 오류 코드, 전체 대상 건수, available/target/processed/new/changed/skipped/failed/lastId/upperId/hasMore/interrupted, nextCursor/pendingRanges, elapsedMs가 있다. 표준 journal에도 같은 숫자를 기록한다. 모델/DB 오류는 기존 고정 코드, 부분 실패는 PARTIAL_FAILURE, 정상 중단은 STOPPED/TIME_LIMIT로 구분한다. 조회 불가 숫자는 -1이다. 모델/DB 사전 검사 실패는 processed=0, failed=0일 수 있지만 실행 상태는 FAILED다. `SUCCESS`는 그 실행만 완료한 의미이며 pending 전체 소진을 뜻하지 않는다.

감독 프로세스는 Docker/Spring의 원시 예외 출력을 그대로 전달하지 않고 형식이 완전히 일치한 안전한 배치/Ollama 진단 메시지만 journal에 전달한다. 원문, 벡터, API 키, DB 비밀번호, Ollama 응답 본문과 예외 메시지는 로그에 넣지 않는다. zero exit만으로 성공을 판단하지 않고 이번 실행의 최신 durable 결과까지 확인한다. journal 보존 기간/용량은 운영자가 journald 정책으로 정하며 무한 로그 파일을 별도로 만들지 않는다.

DB 백업은 기존 승인된 `pg_dump`/복원 검증 방식을 유지한다. checkpoint 백업은 worker가 멈추고 Docker 고정 worker가 없는 것을 확인한 뒤 복사한다. timer만 stop하는 것은 현재 worker 정지 확인을 대신하지 못한다.

```bash
sudo systemctl stop perfume-embedding.timer
sudo systemctl stop perfume-embedding.service
# 별도 once 단위도 종료됐고 worker가 없음을 확인한 다음
sudo install -d -m 0700 /srv/perfume/backups/perfume-auto-embedding
sudo cp -a /var/lib/perfume-embedding/state.json \
  "/srv/perfume/backups/perfume-auto-embedding/state-$(date -u +%Y%m%dT%H%M%SZ).json"
```

첫 실행 전에는 state.json이 없을 수 있다. checkpoint의 오래된 백업을 복원해도 이미 저장한 임베딩은 해시 비교로 보존된다. DB 백업과 checkpoint 시점이 다를 때는 더 이른 안전한 구간/전체 재검사를 택한다. 운영 백업 파일을 Git이나 CI artifact에 넣지 않는다.

## 13. 파일과 오프라인 검증

| 파일 | 역할 |
| --- | --- |
| `ai/embedding/AutoEmbeddingOptions.java` | 자동 실행 경로·시간 제한 검증 |
| `ai/embedding/AutoEmbeddingState.java` | 파일 잠금, 모델 identity, atomic checkpoint와 보고서 |
| `ai/embedding/AutoPerfumeEmbeddingJob.java` | bounded 구간 큐, 신규 범위 등록, 재시작/실패 복구 |
| `ai/embedding/AutoPerfumeEmbeddingRunner.java` | 일회성 Spring runner와 종료 신호 대기 |
| 기존 `PerfumeEmbeddingBatch/Store/JdbcPerfumeEmbeddingStore` | 수동 파이프라인 재사용, 고정 upperId와 중단 조건 추가 |
| 기존 `PerfumeEmbeddingBatchRunner/Configuration`, `application.properties` | 명시적 CLI 보호, 수동/자동 runner 하나 선택, 기본 false |
| `scentrev/service/ScentRevCatalogWriteGuard.java`와 기존 Import 두 서비스 | 저장 트랜잭션 공유 advisory lock |
| `scripts/perfume-auto-embedding.sh`, `.py` | Jenkins flock, Docker worker 감독, 설정·상태 조회 |
| `scripts/jenkins-deploy.sh` | 고아 worker 복구 표식에서 배포 중단 |
| `deploy/systemd/*` | service, 예약 시각 없는 timer, 비활성 설정 예시 |
| `Auto*Tests`, `ScentRevCatalogWriteGuardTests`, 기존 트랜잭션/배포 회귀 테스트 | Mock·임시 파일 검증 |
| `.gitignore`, `.gitattributes` | 표식 추적 제외와 Bash/systemd LF 보존 |
| 기존 `docs/perfume-embedding-batch.md` | 후속 자동 실행 문서 연결 |

Java 경로는 `src/main/java/com/perfume/` 기준이다. `pom.xml`, Compose, Dockerfile, frontend, `.env`, SQL schema 파일은 변경하지 않는다.

```bash
./mvnw -B -ntp test
python3 -B -m unittest discover -s tests/deployment -v
python3 -B -m unittest discover -s tests/diagnostics -v
bash -n scripts/perfume-auto-embedding.sh
```

테스트는 신규/기존/변경/동일 해시, ID 빈 구간, 한도, 실행 도중 신규 추가, 전체 주기, 실패 prefix 재검사와 신규 진행, durable state 재시작, 모델 mismatch, timeout/HTTP, DB 장애, busy skip, 비활성/잘못된 설정, 안전한 로그, Jenkins 표식 거부, Import 트랜잭션과 기존 API 회귀를 검사한다. 실제 Linux flock/pgvector/두 세션 advisory lock, Docker daemon의 신호·강제 종료 경합, systemd 부팅/SSH 단절, VM 전원 중단은 서버 승인 후 별도 검증 대상이다.

관리자가 활성화 전에 결정할 항목: 실제 전체 모델 digest, 예약 표현식/시간대, 최초 backlog와 일일 운영의 건수·시간 예산, Import/Jenkins 작업 창, 실패 알림과 journal 보존, DB/checkpoint 백업 및 복원 책임자, 실측 처리량에 따른 용량 조정 기준.

## 14. 로컬 검증 결과 (2026-10-18)

- Java 17 / Maven 3.9.16 캐시를 사용한 오프라인 Maven 전체 `test`: **1104건, 실패 0, 오류 0, 기존 live/manual 15건 skipped, 1089건 통과**. 자동 실행/설정/파일 복구/Import 보호 관련 신규 Java 테스트 22건을 포함한다. 공개 카탈로그·의미 검색·LLM API와 기존 Import/Preview/skip-existing 회귀도 전체 suite에 포함된다.
- 배포 보호 Python suite: **70건 통과**. 새 자동 실행 스크립트 테스트 13건과 Jenkins 고아 worker 표식 테스트 1건을 포함한다. 모든 Docker/Git/systemd 외부 동작은 Mock 또는 임시 디렉터리의 가짜 도구다.
- 기존 Ollama 진단 Python suite **12건**, React 단위 테스트 **8건** 통과.
- 보호 Bash 스크립트 2개와 이 문서의 Bash 블록 10개 문법 검사, Python 구문 검사, `git diff --check` 통과. 문서 명령을 실행한 결과가 아니며 실제 systemd unit 검증은 위 승인 후 설치 단계에서 한다.

Windows 기본 Python 실행 별칭 대신 기존 pgAdmin 내 Python을 사용했다. JUnit 임시 경로는 workspace 내부로 지정했다. 기존 `target/classes`의 `ClassFormatError` 때문에, 원본 POM의 의존성/소스는 그대로 두고 빌드 출력·리소스 경로·테스트 작업 디렉터리·임시 경로만 분리한 `target/auto-embedding-validation-pom.xml`로 Maven 전체 suite를 검증했다. 원본 `pom.xml`은 변경하지 않았다.

검증 로그는 `target/phase1-validation/auto-embedding-full-tests.log`, `auto-embedding-deployment.log`, `auto-embedding-python-focused.log`, `auto-embedding-diagnostics.log`, `auto-embedding-frontend-tests.log`, `auto-embedding-syntax.log`에 있다. Java 상세 XML 보고서는 `target/auto-embedding-validation/surefire-reports/`에 있다. 실제 DB·Ollama·Ubuntu·Docker daemon과 연결하지 않았고 Git Commit/Push, Jenkins 실행, systemd 설치·활성화도 수행하지 않았다.
