# Elasticsearch 로컬 검증 기록

검증일: 2026-11-02 (Asia/Seoul). 기준 Git revision: `3867436543549c6f877366a6f9f63e099bf61ae5`.

## 작업 범위

로컬 소스 작성·빌드·테스트·문서 작성만 수행했다. 실제 Elasticsearch/Ollama/PostgreSQL에 접속하지 않았고 Git commit/push, Jenkins 실행, Ubuntu SSH, Docker 이미지 pull/build, 컨테이너 생성·재생성·중지, DB/스키마 변경을 수행하지 않았다. Docker CLI 호출은 `compose config --format json` 렌더링뿐이며 Docker daemon을 사용하지 않는다. 기존 배포 테스트는 가짜 Docker/SSH 도구를 사용하는 mock harness다. 그 테스트의 `Removed test container...` 출력은 mock 실행 결과이며 실제 컨테이너 조작이 아니다.

Java는 JDK 17 및 기존 Maven dependency cache를 이용했다. 이 sandbox에서는 Windows 사용자 Maven cache/임시 디렉터리의 경로 정규화·삭제에서 접근 오류가 발생해, dependency cache와 임시 디렉터리를 `target/elasticsearch-m2`, `target/elasticsearch-tmp`로 옮긴 검증 환경을 사용했다. 기존 프로젝트의 `target/classes`와 IDE 출력 대신 별도의 출력 디렉터리·임시 POM을 사용했다. 버전·의존성·실제 소스는 바꾸지 않았다. 일반 환경에서는 `./mvnw test`, `cd frontend && npm test && npm run build`, `python -m unittest discover -s tests/deployment -v`로 검증할 수 있다.

## 적용 전 기준

기존 revision의 추적된 Java 소스/리소스/테스트를 `target/elasticsearch-baseline-source`에 복사하고 변경된 파일은 `git show HEAD:<path>` 내용으로 복원하여 기준 테스트를 실행했다. 추적되지 않은 신규 검색 코드는 기준 소스에 들어가지 않았다.

| 항목 | 적용 전 결과 |
| --- | --- |
| Java 전체 | 1,211개 발견, 1,194개 통과, 17개 조건부 건너뜀, 실패/오류 0 |
| 카탈로그 API/서비스 | 32개 통과 |
| AI embedding/pgvector 검색/RAG | 기존 490개 통과, 실제 모델/DB 호출은 모의 경계로 대체 |
| 인증 | 31개 중 29개 통과, PostgreSQL/로컬 검증용 2개 gate 건너뜀 |
| 고객센터/보안/WebSocket | 기존 18개 통과. 세션·STOMP 통합은 격리된 로컬 테스트 서버/H2 사용 |
| 프런트엔드 | 58개 통과 |
| 배포 보호 | 94개 통과 |

기존 동작은 양쪽 GET 경로, 기본 페이지 크기 20, 최대 100, q/brandSlug 검증, 이름·브랜드·slug LIKE, 특수문자 이스케이프, 이름순/ID순 정렬, 기존 DTO JSON 필드를 기준으로 기록했다. 실제 PostgreSQL 데이터의 검색 건수나 HTTP latency를 측정한 결과는 아니다.

## 변경 후 결과

| 항목 | 결과 |
| --- | --- |
| Java 전체 | 1,281개 발견, 1,263개 통과, 18개 조건부 건너뜀, 실패/오류 0 |
| 신규 검색 테스트 | 70개 중 69개 통과, 실제 ES 통합 테스트 1개 건너뜀 |
| 기존 카탈로그 API/서비스 | 32개 통과 |
| 기존 AI/RAG | 490개 통과 |
| 기존 인증 | 29개 통과, 기존 gate 2개 건너뜀 |
| 기존 고객센터/WebSocket | 18개 통과 |
| React/프런트엔드 | 60개 통과 (기존 58 + 신규 2) |
| Vite 프로덕션 빌드 | 성공 |
| backend 실행 JAR 패키징 | 성공. mapping/검색/CLI class 포함 및 secret 미포함 확인 |
| 배포 보호 | 97개 전부 통과 (기존 94 + 신규 Compose 구성 검증 3) |
| 문서 명령 | 11개 Bash 코드 블록을 `bash -n`으로 구문 검사. 명령 자체는 미실행 |

검증 로그(모두 Git 제외 `target/`):

- `elasticsearch-baseline.log`: 기준 Java 전체 결과.
- `elasticsearch-final-tests.log`: 변경 후 Java 전체 결과.
- `elasticsearch-final-deployment-tests.log`: 배포 보호 전체 결과.
- `elasticsearch-compose-tests.log`: Compose 신규 3개 단독 검증.
- `elasticsearch-frontend-baseline.log`, `elasticsearch-frontend-tests.log`, `elasticsearch-frontend-build.log`.
- `elasticsearch-package.log`: backend 실행 JAR 패키징 결과.

초기 실행에서 발생한 Windows cache/임시 디렉터리 접근 오류, 신규 테스트의 Mockito 중첩 stubbing 오류, Compose의 메모리·CPU 값을 문자열/숫자로 반환하는 형식 차이는 해결했다. 제품 코드의 검색 실패로 집계하지 않으며 최종 로그를 기준으로 한다.

## 확인한 동작

| 요구사항 | 로컬 검증 | 실제 엔진 검증 |
| --- | --- | --- |
| 이름·브랜드·노트·어코드 검색 | 가중치/쿼리 필드/리터럴 문자열, 스냅샷 투영 검증 | 실제 관련성 결과 미검증 |
| 한국어·영문·동의어·오타 | 쿼리 분리, mapping, UTF-8 전달 검증 | Nori/plugin 설치·실제 토큰·사전 품질 미검증 |
| 브랜드/페이지/건수 | keyword 필터, ES 응답 검증, DTO 순서·metadata 검증 | 실데이터 건수 미검증 |
| SQL 회귀 | 기존 서비스 32개 + 실제 JPQL/H2 회귀 1개 통과 | PostgreSQL 17의 실제 실행 계획·부하 미검증 |
| ES 비활성 | 자격증명 파일/client/DB를 요구하지 않는 기본 설정 검증 | 기본값은 PostgreSQL |
| ES 장애 | 모의 connection/HTTP timeout/401/403/429/5xx/잘못된 JSON/부분 응답, cooldown·동시성 검증 | 실제 네트워크 차단 실험 미실행 |
| 데이터 불일치 | 삭제/브랜드 변경 시 페이지 전체 SQL 복귀, 정상 0건 유지 | 신규 누락·검색 조건 변경은 수동 재색인 필요 |
| 수동 색인 | read-only transaction 설정, lock·keyset·노트 배열, Bulk 실패·count 불일치·alias race·모호한 게시 실패 검증 | 실제 원본 전체 색인·스냅샷 격리 미검증 |
| 기존 인증/RAG/고객센터 | 기존 전체 회귀 테스트 통과 | Ubuntu 브라우저/실제 모델/STOMP 배포 점검 미실행 |
| 실행 환경 | Compose를 실제 CLI로 렌더링해 기존 서비스/볼륨 불변, secret/network/profile 확인 | 이미지 pull/build·TLS handshake·UID 권한·부하 미검증 |

`ElasticsearchLiveTests`는 gate가 기본 닫혀 있으며 이번에는 실행하지 않았다. 별도 승인된 폐기 가능한 노드에서 `PERFUME_ELASTICSEARCH_LIVE_TEST=true`와 테스트 전용 URL/CA/key를 제공하면 실제 이름·브랜드·노트·어코드·Nori·동의어·오타·필터·페이징을 확인하도록 준비했다. 이 테스트도 프로덕션 데이터를 읽거나 변경하지 않도록 테스트 전용 key를 사용해야 한다.

## 신규·수정 파일

총 37개: 기존 6개 수정, 신규 31개. Git commit/push는 하지 않았다.

수정 파일:

- `.gitignore`: `.secrets/` 제외.
- `src/main/java/com/perfume/PerfumeApplication.java`: 명시적 수동 ES 색인 CLI 선분기.
- `src/main/java/com/perfume/api/service/PerfumeQueryService.java`: 선택 검색과 PostgreSQL DTO hydration/복귀.
- `src/main/java/com/perfume/repository/PerfumeRepository.java`: ID 목록+브랜드 조건의 요약 projection 조회.
- `src/main/resources/application.properties`: 기본 비활성 flag.
- `src/test/java/com/perfume/api/service/CatalogQueryServiceTests.java`: 비활성 검색 경계 주입. 기존 검증 내용 유지.

신규 backend 파일 (`src/main/java/com/perfume/search/`):

- `CatalogSearch.java`: 선택 검색 경계·ID/건수.
- `ElasticsearchConfiguration.java`: 활성화와 안전한 설정 실패 처리.
- `ElasticsearchOptions.java`: HTTPS/alias/secret 경로 검증.
- `ElasticTransport.java`, `ElasticHttpTransport.java`: REST 경계·인증·TLS·시간 제한.
- `ElasticsearchQuery.java`: 관련성/한글/동의어/오타/필터/페이지 쿼리.
- `ElasticsearchCatalogSearch.java`: 응답 검증·장애 복귀·동시성/cooldown.
- `PerfumeSearchDocument.java`: 11개 검색 문서 필드.
- `CatalogSnapshot.java`, `JdbcCatalogSnapshot.java`: 읽기 전용 원본 스냅샷.
- `ElasticsearchReindexer.java`: 버전 인덱스·Bulk·건수·Alias 게시.
- `ElasticsearchReindexCli.java`: 명시적 전체 색인 CLI.

기타 신규 파일:

- `src/main/resources/elasticsearch/perfumes-v1.json`: 분석기·동의어·필드 mapping.
- `compose.elasticsearch.yaml`: ES 단독 opt-in 구성.
- `compose.elasticsearch-app.yaml`: backend 수동 활성화 override.
- `compose.elasticsearch-reindex.yaml`: 수동 일회성 색인 job.
- `docker/elasticsearch/Dockerfile`: 공식 8.19.23 + Nori.
- `docker/elasticsearch/search-api-key.json`, `index-api-key.json`: 비밀 값 없는 권한 명세.
- `src/test/java/com/perfume/search/CatalogRepositoryRegressionTests.java`.
- `src/test/java/com/perfume/search/CatalogSearchRoutingTests.java`.
- `src/test/java/com/perfume/search/ElasticHttpTransportTests.java`.
- `src/test/java/com/perfume/search/ElasticsearchConfigurationTests.java`.
- `src/test/java/com/perfume/search/ElasticsearchLiveTests.java`.
- `src/test/java/com/perfume/search/ElasticsearchReindexTests.java`.
- `src/test/java/com/perfume/search/ElasticsearchSearchTests.java`.
- `src/test/java/com/perfume/search/JdbcCatalogSnapshotTests.java`.
- `frontend/tests/catalog-search.test.js`: 기존 요청/응답 계약으로 한글 검색·복귀 확인.
- `tests/deployment/test_elasticsearch_configuration.py`: 컨테이너 조작 없이 실제 Compose 렌더링 비교.
- `docs/elasticsearch-search.md`: 구조·자원·색인/동기화·Ubuntu 설치/활성화·복귀/롤백·면접 설명.
- `docs/elasticsearch-validation.md`: 이 검증 기록.

기존 `compose.yaml`, `Jenkinsfile`, `pom.xml`, Dockerfile, React 제품 소스, Security/WebSocket/RAG/pgvector 코드와 SQL migration은 변경하지 않았다. Ubuntu 적용과 PostgreSQL 복귀 명령은 [운영 가이드](elasticsearch-search.md)의 6~7절에 있다.
